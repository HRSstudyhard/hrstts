package com.hrstts.jiandu.i18n

import android.content.Context
import androidx.annotation.StringRes

/**
 * 需要本地化的 UI 文案的统一入口。
 *
 * 存在的理由：`ViewModel` 与 `data` 层（TTS 引擎、模型管理、自更新）都要产出**用户可见**的文本，
 * 但它们不该依赖 Compose，也不该把中文硬编码在业务里。这里用一个「资源 ID → 文案」的查找接口
 * 把「取哪条文案」与「从哪取」解耦：
 *  - 应用内实现用 Android 资源（`values/`、`values-en/`），跟随系统语言；
 *  - 纯逻辑/单元测试可以传 [StaticStrings]，无需 Android 运行环境。
 *
 * 约定：资源里**不要**用 `%d` 做纯数字格式化（阿拉伯语等区域会输出本地数字形态），
 * 一律用位置参数 `%1$d` 并按位置传参。
 */
interface UiStrings {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** Android 资源实现（跟随系统语言 / 区域） */
class AndroidStrings(private val context: Context) : UiStrings {
    private val resources get() = context.resources

    override fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
}

/** 不依赖 Android 的实现：给固定文案，供单元测试或极早期构造使用 */
class StaticStrings(private val text: (Int, List<Any>) -> String = { _, _ -> "" }) : UiStrings {
    override fun get(id: Int, vararg args: Any): String = text(id, args.toList())
}

/** 便于在 data/domain 层默认参数里使用 */
fun Context.uiStrings(): UiStrings = AndroidStrings(this)
