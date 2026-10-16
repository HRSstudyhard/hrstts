package com.hrstts.jiandu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 全局字体样式（MD3 标准角色的轻量定制） */
val JianDuTypography = Typography()

/** 正文阅读样式：字号与行距按中文长文阅读调优（阶段 P2 起在阅读区使用） */
val ReadingBodyStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp,
    lineHeight = 32.sp,
    letterSpacing = 0.5.sp,
)

/** 章节标题样式 */
val ChapterTitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 34.sp,
)
