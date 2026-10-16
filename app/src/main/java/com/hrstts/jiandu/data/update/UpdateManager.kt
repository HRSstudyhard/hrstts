package com.hrstts.jiandu.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.hrstts.jiandu.BuildConfig
import com.hrstts.jiandu.R
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.UiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 远端更新清单（JSON） */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String?,
)

/** 更新流程状态 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val downloaded: Long, val total: Long) : UpdateState {
        val fraction: Float get() = if (total <= 0) 0f else (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** APK 已就绪（已下载或用户本地选择），可交给系统安装器 */
    data class ReadyToInstall(val file: File, val versionName: String, val note: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 应用内自更新。
 *
 * 关键点（也是「装了但功能没更新」的排查依据）：
 *  - 只有 **versionCode 更大** 才会被视为新版本（同版本号覆盖安装不会带来新功能语义）；
 *  - 安装前校验：包名一致、versionCode ≥ 当前、**签名证书与已安装应用一致**
 *    （签名不一致会被系统拒绝为 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，这就是「装不上/没更新」的常见原因）；
 *  - 安装走系统安装器（`REQUEST_INSTALL_PACKAGES` 需用户在设置中开启），APK 通过 FileProvider 共享。
 */
class UpdateManager(
    private val context: Context,
    private val strings: UiStrings = AndroidStrings(context),
) {

    val currentVersionCode: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentBuildTime: String = BuildConfig.BUILD_TIME

    /** 拉取更新清单（url 必须是 http/https 的 JSON） */
    suspend fun fetchManifest(url: String): UpdateManifest = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JianDu/${BuildConfig.VERSION_NAME} (Android)")
        }
        val body = try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException(strings.get(R.string.update_server_http, code))
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
        UpdateManifest(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", strings.get(R.string.update_unknown_version)),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
            notes = json.optString("notes").takeIf { it.isNotBlank() },
        )
    }

    /** 下载 APK 到私有缓存目录（支持断点续传） */
    suspend fun downloadApk(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val part = File(dir, "app-update.apk.part")
        val target = File(dir, "app-update.apk")
        var existing = if (part.isFile) part.length() else 0L

        val connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JianDu/${BuildConfig.VERSION_NAME} (Android)")
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException(strings.get(R.string.update_download_http, code))
            if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
                existing = 0L
                part.delete()
            }
            val total = manifest.let { connection.contentLengthLong.takeIf { length -> length > 0 } ?: 0L }
            connection.inputStream.use { input ->
                java.io.RandomAccessFile(part, "rw").use { output ->
                    output.seek(existing)
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, if (total > 0) total + existing else 0L)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        target
    }

    /**
     * 安装前校验：包名、版本号、签名证书。
     * 返回 null 表示通过，否则返回失败原因。
     */
    @Suppress("DEPRECATION")
    fun verifyApk(file: File, expectVersionCode: Int? = null): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: return strings.get(R.string.update_not_apk)
        val archivePackage = archive.packageName ?: return strings.get(R.string.update_no_package_name)
        if (archivePackage != context.packageName) {
            return strings.get(R.string.update_package_mismatch, archivePackage, context.packageName)
        }
        @Suppress("DEPRECATION")
        val archiveVersion = archive.versionCode
        if (archiveVersion < currentVersionCode) {
            return strings.get(R.string.update_version_lower, archiveVersion, currentVersionCode)
        }
        if (expectVersionCode != null && archiveVersion != expectVersionCode) {
            return strings.get(R.string.update_version_mismatch, archiveVersion, expectVersionCode)
        }
        if (!sameSignature(archive.signatures, installedSignatures())) {
            return strings.get(R.string.update_signature_mismatch)
        }
        return null
    }

    /** 校验 SHA-256（清单里给了才校验） */
    fun verifySha256(file: File, expected: String?): String? {
        if (expected.isNullOrBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expected.trim(), ignoreCase = true)) {
            null
        } else {
            strings.get(R.string.update_sha_mismatch)
        }
    }

    /** 保存用户本地选择的 APK 到缓存目录，便于统一校验与安装 */
    suspend fun importLocalApk(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "app-local.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException(strings.get(R.string.update_read_selected_apk_failed))
        target
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 交给系统安装器（需要「安装未知应用」权限） */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun cachedApk(): File? = File(File(context.cacheDir, "update"), "app-update.apk").takeIf { it.isFile }

    // ---- 内部 ----

    private fun installedSignatures(): Array<android.content.pm.Signature>? {
        val pm = context.packageManager
        return try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun sameSignature(
        archive: Array<android.content.pm.Signature>?,
        installed: Array<android.content.pm.Signature>?,
    ): Boolean {
        if (archive == null || installed == null) return true // 读不到就不拦，交给系统判断
        val a = archive.map { it.toCharsString() }.toSet()
        val b = installed.map { it.toCharsString() }.toSet()
        return a == b
    }
}
