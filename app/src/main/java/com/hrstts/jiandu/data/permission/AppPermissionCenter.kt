package com.hrstts.jiandu.data.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 启动时权限引导弹窗里每一项需要的动作 */
enum class PermissionAction {
    /** 运行时弹窗申请（Android 12 及以下的存储权限） */
    REQUEST_STORAGE,

    /** 跳「所有文件访问」设置页（Android 11+） */
    OPEN_ALL_FILES,

    /** 跳「安装未知应用」设置页（Android 8+） */
    OPEN_UNKNOWN_SOURCES,

    /** 跳 WLAN/网络设置（当前无网络时） */
    OPEN_NETWORK_SETTINGS,

    /** 已满足，无需动作 */
    NONE,
}

/** 权限与联网状态快照 */
data class PermissionSnapshot(
    val storageGranted: Boolean,
    /** 存储需要走「所有文件访问」设置页（Android 11+）而非运行时弹窗 */
    val storageNeedsSettingsPage: Boolean,
    /** 当前设备是否有可用网络 */
    val networkOnline: Boolean,
    /** 「安装未知应用」是否已允许 */
    val installAllowed: Boolean,
    /** 该 Android 版本是否需要「安装未知应用」授权（API 26+） */
    val installSupported: Boolean,
) {
    val storageAction: PermissionAction
        get() = when {
            storageGranted -> PermissionAction.NONE
            storageNeedsSettingsPage -> PermissionAction.OPEN_ALL_FILES
            else -> PermissionAction.REQUEST_STORAGE
        }

    val networkAction: PermissionAction
        get() = if (networkOnline) PermissionAction.NONE else PermissionAction.OPEN_NETWORK_SETTINGS

    val installAction: PermissionAction
        get() = when {
            !installSupported || installAllowed -> PermissionAction.NONE
            else -> PermissionAction.OPEN_UNKNOWN_SOURCES
        }

    val allSatisfied: Boolean
        get() = storageGranted && networkOnline && (!installSupported || installAllowed)
}

/**
 * 权限与联网状态中心。
 *
 * 说明（各版本的真实机制）：
 *  - **网络**：`INTERNET` 是安装时自动授予的普通权限，无法（也不需要）弹窗申请；
 *    这里检测的是「当前是否真的联网」，未联网时引导用户打开 WLAN/移动数据。
 *  - **存储**：Android 12 及以下 `READ/WRITE_EXTERNAL_STORAGE` 可运行时弹窗；
 *    Android 13+ 没有通用存储运行时权限，只能引导用户开启「所有文件访问」特殊权限。
 *    应用导入文本本身走 SAF，不依赖该权限（不授权也能用）。
 *  - **安装未知应用**：Android 8+ 起是特殊权限，只能跳设置页由用户手动开启。
 */
object AppPermissionCenter {

    /** Android 11（API 30）起「所有文件访问」是设置页开关 */
    private const val ALL_FILES_API = Build.VERSION_CODES.R

    fun snapshot(context: Context): PermissionSnapshot = PermissionSnapshot(
        storageGranted = isStorageGranted(context),
        storageNeedsSettingsPage = Build.VERSION.SDK_INT >= ALL_FILES_API,
        networkOnline = isNetworkOnline(context),
        installAllowed = isInstallAllowed(context),
        installSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
    )

    /** 运行时需要申请的存储权限（仅 Android 12 及以下有效） */
    fun legacyStoragePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun isStorageGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= ALL_FILES_API ->
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNetworkOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        // 以「具备 INTERNET 能力」为准；VALIDATED（系统真的验证过外网可达）仅作为加强信号，
        // 因为不少模拟器/内网环境下 VALIDATED 恒为 false，用它会导致误判为「无网络」。
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isInstallAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    // ---- 跳转设置页（无法弹窗申请的特殊权限） ----

    /** 「所有文件访问」设置页；个别 ROM 没有单应用页时回退到总列表页 */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** 「安装未知应用」设置页（Android 8+） */
    fun unknownSourcesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /** 网络设置页 */
    fun networkSettingsIntent(): Intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)

    /** 应用详情页（权限总入口，兜底用） */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
}
