package com.hrstts.jiandu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hrstts.jiandu.BuildConfig
import com.hrstts.jiandu.data.permission.AppPermissionCenter
import com.hrstts.jiandu.data.permission.PermissionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 启动权限引导。
 *
 * 弹出策略（P5 调整，避免每次冷启动都挡住正文）：
 *  - **首次运行**，或**版本号变化后的第一次运行** → 自动弹出一次；
 *  - 用户点过「完成 / 不再提示」后，本版本内不再自动弹出；
 *  - 任何时候都能用抽屉底部的「权限与联网」手动打开。
 *
 * 特殊权限（所有文件访问、安装未知应用）无法弹窗授予，只能跳系统设置页，返回后刷新状态。
 */
class PermissionViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(AppPermissionCenter.snapshot(application))
    val snapshot: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    init {
        val lastSeenVersion = prefs.getInt(KEY_LAST_VERSION_CODE, 0)
        val shownForVersion = prefs.getInt(KEY_SHOWN_VERSION_CODE, 0)
        val dontAsk = prefs.getBoolean(KEY_DONT_ASK, false)

        if (lastSeenVersion != BuildConfig.VERSION_CODE) {
            // 版本更新：重置「不再提示」，让新版本的引导一定出现一次
            prefs.edit()
                .putInt(KEY_LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
                .putBoolean(KEY_DONT_ASK, false)
                .apply()
            _dialogVisible.value = true
        } else if (!dontAsk && shownForVersion != BuildConfig.VERSION_CODE) {
            // 同一版本内只在第一次运行时自动弹出
            _dialogVisible.value = true
        }
    }

    /** 从设置页返回后刷新状态；若全部满足则自动关闭弹窗 */
    fun refresh() {
        val latest = AppPermissionCenter.snapshot(getApplication())
        _snapshot.value = latest
        if (latest.allSatisfied) _dialogVisible.value = false
    }

    fun requestStoragePermissions(): Array<String> =
        AppPermissionCenter.legacyStoragePermissions()

    fun onRuntimePermissionResult() {
        markAskedAndShown()
        refresh()
    }

    fun show() {
        refresh()
        _dialogVisible.value = true
    }

    fun dismiss() {
        markAskedAndShown()
        _dialogVisible.value = false
    }

    fun dontAskAgain() {
        prefs.edit()
            .putBoolean(KEY_DONT_ASK, true)
            .putBoolean(KEY_ASKED, true)
            .putInt(KEY_SHOWN_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
        _dialogVisible.value = false
    }

    /** 记录「本版本已经引导过一次」，避免每次冷启动都挡住正文 */
    private fun markAskedAndShown() {
        prefs.edit()
            .putBoolean(KEY_ASKED, true)
            .putInt(KEY_SHOWN_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    private companion object {
        const val PREFS = "jiandu_permissions"
        const val KEY_DONT_ASK = "dont_ask_permissions"
        const val KEY_ASKED = "asked_permissions"
        const val KEY_LAST_VERSION_CODE = "last_version_code"
        const val KEY_SHOWN_VERSION_CODE = "shown_version_code"
    }
}
