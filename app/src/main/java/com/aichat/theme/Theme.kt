package com.aichat.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 浅色。
 *
 * 底色 `Ink25`（近乎白但偏冷）而卡片是纯白 —— **卡片比背景亮**，
 * 这是「纸叠在桌面上」的层次。反过来的话（灰卡片压在白底上）卡片会显得
 * 像一块凹陷的补丁，之前就是这个观感。
 *
 * 各层 surface 拉开了明确的阶梯（lowest → highest），
 * 于是「同一块区域里嵌套一层」不需要靠阴影来表达，靠明度差就够。
 */
private val LightColors =
  lightColorScheme(
    primary = Indigo600,
    onPrimary = Ink0,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo950,
    inversePrimary = Indigo300,
    secondary = Ink600,
    onSecondary = Ink0,
    secondaryContainer = Ink100,
    onSecondaryContainer = Ink800,
    tertiary = Teal700,
    onTertiary = Ink0,
    tertiaryContainer = Teal100,
    onTertiaryContainer = Teal900,
    background = Ink25,
    onBackground = Ink900,
    surface = Ink0,
    onSurface = Ink900,
    surfaceVariant = Ink100,
    onSurfaceVariant = Ink500,
    surfaceTint = Indigo600,
    inverseSurface = Ink800,
    inverseOnSurface = Ink50,
    error = Red700,
    onError = Ink0,
    errorContainer = Red100,
    onErrorContainer = Red900,
    outline = Ink300,
    outlineVariant = Ink200,
    scrim = Color(0xFF000000),
    surfaceBright = Ink0,
    surfaceDim = Ink200,
    surfaceContainerLowest = Ink0,
    surfaceContainerLow = Ink25,
    surfaceContainer = Ink50,
    surfaceContainerHigh = Ink100,
    surfaceContainerHighest = Ink200,
  )

/**
 * 深色。**这才是这台设备本来的样子** —— 跳线板的面板是深色的。
 *
 * 主色换成 [Indigo300]：`Indigo600` 压在 `Ink900` 上对比度只有 3 左右，
 * 按钮上的白字会糊。深色主题下主色必须**变浅**，这是 M3 的规矩，不是审美偏好。
 */
private val DarkColors =
  darkColorScheme(
    primary = Indigo300,
    onPrimary = Indigo950,
    primaryContainer = Indigo900,
    onPrimaryContainer = Indigo100,
    inversePrimary = Indigo600,
    secondary = Color(0xFFB9BECD),
    onSecondary = Color(0xFF23283A),
    secondaryContainer = Ink700,
    onSecondaryContainer = Color(0xFFDDE1EA),
    tertiary = Teal300,
    onTertiary = Teal900,
    tertiaryContainer = Color(0xFF115E59),
    onTertiaryContainer = Teal100,
    background = Ink950,
    onBackground = Color(0xFFE6E8EE),
    surface = Ink900,
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFB4B9C6),
    surfaceTint = Indigo300,
    inverseSurface = Color(0xFFE6E8EE),
    inverseOnSurface = Ink800,
    error = Red300,
    onError = Red900,
    errorContainer = RedContainerDark,
    onErrorContainer = Red100,
    outline = Color(0xFF4A5060),
    outlineVariant = Ink700,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF262A33),
    surfaceDim = Ink900,
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Ink950,
    surfaceContainer = Color(0xFF171A21),
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Color(0xFF262A33),
  )

/**
 * 全 App 的主题。
 *
 * ## [dynamicColor] 为什么默认是 false
 *
 * Material You 的动态取色是给「手机自带应用」用的 —— 它让界面跟着壁纸走，
 * 好处是「这是你的手机」。但对一个**有自己身份的工具**，代价是：
 *
 * - 应用图标是深靛蓝的跳线板，界面却是壁纸里那个说不清的淡紫色 —— **图标和界面互相不认识**
 * - 用户换一张壁纸，整个 App 换一个样子。一个「本地 BYOK、数据都在自己机器上」的工具，
 *   外观不该由一个云端壁纸决定
 * - 动态取色的对比度是**不可预测**的：某些壁纸派生出的 primary 压在 surface 上只有 2 点几，
 *   按钮文字就糊了。而这是系统算出来的，我们连改都改不了
 *
 * 保留这个开关是为了留条后路（将来也许提供一个「跟随壁纸」的选项给喜欢的人），
 * 但默认必须是自己的颜色。
 */
@Composable
fun PatchbayTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColors
      else -> LightColors
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = Shapes, content = content)
}
