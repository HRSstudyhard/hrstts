package com.hrstts.jiandu.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrstts.jiandu.data.update.UpdateManager
import com.hrstts.jiandu.data.update.UpdateManifest
import com.hrstts.jiandu.data.update.UpdateState
import com.hrstts.jiandu.i18n.AndroidStrings
import com.hrstts.jiandu.i18n.TextIds
import com.hrstts.jiandu.i18n.UiText
import com.hrstts.jiandu.i18n.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用内自更新状态机。
 *
 * 流程：检查清单 → 比较 versionCode → 下载 → 校验（包名/版本/签名/SHA-256）→ 交系统安装器。
 * 也支持「从本地选择 APK」安装（不经网络，便于手动分发）。
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val strings = AndroidStrings(application)
    private val manager = UpdateManager(application, strings)
    private val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    val currentVersionName: String = manager.currentVersionName
    val currentVersionCode: Int = manager.currentVersionCode
    val buildTime: String = manager.currentBuildTime

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    /** 更新清单地址（用户可改，持久化） */
    var manifestUrl: String
        get() = prefs.getString(KEY_MANIFEST_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MANIFEST_URL, value.trim()).apply()

    fun show() {
        _visible.value = true
    }

    fun hide() {
        _visible.value = false
        _state.value = UpdateState.Idle
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun canRequestPackageInstalls(): Boolean = manager.canRequestPackageInstalls()

    /** 检查更新：读取清单并与当前 versionCode 比较 */
    fun check() {
        val url = manifestUrl
        if (url.isBlank()) {
            _message.value = uiText(TextIds.updateManifestMissing)
            return
        }
        if (_state.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            try {
                val manifest = manager.fetchManifest(url)
                _state.value = if (manifest.versionCode > currentVersionCode) {
                    UpdateState.Available(manifest)
                } else {
                    UpdateState.UpToDate(currentVersionName)
                }
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(
                    t.message ?: strings.get(TextIds.updateCheckFailed),
                )
            }
        }
    }

    /** 下载清单里的新版本 APK */
    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _state.value = UpdateState.Downloading(0, 0)
            try {
                val file = manager.downloadApk(manifest) { downloaded, total ->
                    _state.value = UpdateState.Downloading(downloaded, total)
                }
                val problem = manager.verifySha256(file, manifest.sha256)
                    ?: manager.verifyApk(file, manifest.versionCode)
                _state.value = if (problem == null) {
                    UpdateState.ReadyToInstall(file, manifest.versionName, manifest.notes ?: "")
                } else {
                    UpdateState.Failed(problem)
                }
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(
                    t.message ?: strings.get(TextIds.updateDownloadFailed),
                )
            }
        }
    }

    /** 使用本地选择的 APK（不经网络） */
    fun useLocalApk(uri: Uri) {
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            try {
                val file = manager.importLocalApk(uri)
                val problem = manager.verifyApk(file)
                _state.value = if (problem == null) {
                    UpdateState.ReadyToInstall(
                        file,
                        strings.get(com.hrstts.jiandu.R.string.update_local_apk_name),
                        "",
                    )
                } else {
                    UpdateState.Failed(problem)
                }
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(
                    t.message ?: strings.get(TextIds.updateReadApkFailed),
                )
            }
        }
    }

    /** 拉起系统安装器 */
    fun install(file: File, versionName: String) {
        if (!manager.canRequestPackageInstalls()) {
            _message.value = uiText(TextIds.updateNeedPermissionFirst)
            return
        }
        val problem = manager.verifyApk(file)
        if (problem != null) {
            _state.value = UpdateState.Failed(problem)
            return
        }
        runCatching { manager.installApk(file) }
            .onSuccess { _message.value = uiText(TextIds.updateHandedToInstaller, versionName) }
            .onFailure {
                _state.value = UpdateState.Failed(
                    strings.get(TextIds.updateInstallerFailed, it.message.orEmpty()),
                )
            }
    }

    private companion object {
        const val PREFS = "jiandu_update"
        const val KEY_MANIFEST_URL = "manifest_url"
    }
}
