package com.hrstts.jiandu.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hrstts.jiandu.i18n.UiText

/**
 * 把 [UiText]（资源 ID + 参数）渲染成当前语言下的字符串。
 *
 * 放在 ui 层是因为它依赖 Compose 的 [stringResource]；数据层只依赖
 * `com.hrstts.jiandu.i18n` 的纯 Kotlin 接口，避免 data → ui 的反向依赖。
 */
@Composable
fun UiText.resolve(): String =
    if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())

/** 可空版本：null 时返回 null，便于 `state.message?.let { ... }` 场景 */
@Composable
fun UiText?.resolveOrNull(): String? = this?.resolve()
