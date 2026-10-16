package com.hrstts.jiandu.i18n

import androidx.annotation.StringRes

/**
 * 状态里携带的「待显示文本」：只存资源 ID 与参数。
 *
 * 好处：ViewModel 不必持有 Context、不必依赖 Compose；切换系统语言后，
 * 已产生的消息也会按新语言渲染（而不是留下旧语言的字符串）。
 * Compose 层用 `com.hrstts.jiandu.ui.text.resolve()` 还原成字符串。
 */
data class UiText(
    @param:StringRes val id: Int,
    val args: List<Any> = emptyList(),
)

/** 构造便捷函数：`uiText(R.string.x, a, b)` */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText(id, args.toList())

/** 常用一次性提示的集中引用，避免各处重复写 `R.string.xxx` */
object TextIds {
    val importTruncated = com.hrstts.jiandu.R.string.import_truncated
    val importFailed = com.hrstts.jiandu.R.string.import_failed
    val importUnknownError = com.hrstts.jiandu.R.string.import_unknown_error
    val importUriUnavailable = com.hrstts.jiandu.R.string.import_uri_unavailable
    val importEmptyFile = com.hrstts.jiandu.R.string.import_empty_file
    val engineFallbackToSystem = com.hrstts.jiandu.R.string.engine_fallback_to_system
    val engineSwitchedToQwen3 = com.hrstts.jiandu.R.string.engine_switched_to_qwen3
    val engineStorageInsufficient = com.hrstts.jiandu.R.string.engine_storage_insufficient
    val engineDownloadIncomplete = com.hrstts.jiandu.R.string.engine_download_incomplete
    val engineDeleted = com.hrstts.jiandu.R.string.engine_deleted
    val engineProbeRunning = com.hrstts.jiandu.R.string.engine_probe_running
    val engineProbeFailed = com.hrstts.jiandu.R.string.engine_probe_failed
    val updateManifestMissing = com.hrstts.jiandu.R.string.update_manifest_missing
    val updateCheckFailed = com.hrstts.jiandu.R.string.update_check_failed
    val updateDownloadFailed = com.hrstts.jiandu.R.string.update_download_failed
    val updateReadApkFailed = com.hrstts.jiandu.R.string.update_read_apk_failed
    val updateNeedPermissionFirst = com.hrstts.jiandu.R.string.update_need_permission_first
    val updateHandedToInstaller = com.hrstts.jiandu.R.string.update_handed_to_installer
    val updateInstallerFailed = com.hrstts.jiandu.R.string.update_installer_failed
    val errorOpenSettingsFailed = com.hrstts.jiandu.R.string.error_open_settings_failed
    val errorOpenPickerFailed = com.hrstts.jiandu.R.string.error_open_picker_failed
}
