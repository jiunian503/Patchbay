package com.aichat.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号层级。
 *
 * ## 改了两件事
 *
 * 1. **`letterSpacing` 全部压到 0**。Material 的默认值是按拉丁字母调的（正文 +0.5sp），
 *    而中文是方块字，本身就有字身框的间隙 —— 再拉开半磅，整段话会显得松散、不齐。
 *    这也是之前界面上中文看起来「散」的原因之一。
 * 2. **给标题加上字重**。原来 `titleLarge` 是 22sp / Normal，一个大号细体字看着像
 *    占位符而不是标题。标题要 `SemiBold` 才有「这是一屏的名字」的分量。
 *
 * 正文定在 15sp：16sp 在 6.7 寸屏上一屏装不下几句话，14sp 又偏小；
 * 行高 23sp（约 1.53 倍）是中文长段落比较舒服的密度。
 */
val Typography =
  Typography(
    headlineSmall =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
      ),
    titleLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
      ),
    titleMedium =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
      ),
    titleSmall =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
      ),
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
      ),
    bodySmall =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
      ),
    labelLarge =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
      ),
    labelMedium =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
      ),
    labelSmall =
      TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
      ),
  )

/**
 * 等宽样式。**跳线板是设备，设备上的数字是等宽的。**
 *
 * 用在：工具输出、工具参数、条数/时间这类元数据。
 *
 * 这些地方用等宽不只是为了好看 —— 工具返回的 JSON、坐标、时间戳，
 * 等宽下每一行对齐，扫一眼就能看出结构；比例字体下数字宽度不一，反而更难读。
 * 另外它天然把「机器给的东西」和「人写的话」在视觉上分开了。
 */
val MonoTextStyle =
  TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
  )

/** 元数据里的等宽小字（条数、耗时之类）。 */
val MonoLabelStyle =
  TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
  )
