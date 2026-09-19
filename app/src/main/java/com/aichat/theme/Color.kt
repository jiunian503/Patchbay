package com.aichat.theme

import androidx.compose.ui.graphics.Color

/**
 * Patchbay 的调色板。
 *
 * ## 为什么不用 Material You 的动态取色
 *
 * 这个 App 之前是 `dynamicColor = true` —— 也就是说界面颜色**由用户的壁纸决定**。
 * 于是出现了一件很怪的事：应用图标是深靛蓝的跳线板，界面却是壁纸里那个说不清的
 * 淡紫色。图标和界面是两套语言，而且换个壁纸整个 App 就变个样。
 *
 * 「跳线板」是一台**设备**：面板是深色石墨、插孔是金属、指示灯是青绿。
 * 一台设备的颜色不该随房间墙纸变。所以这里定死一套色，动态取色保留成开关但默认关。
 *
 * ## 三层结构
 *
 * - **Indigo**（靛蓝）：品牌色，取自图标底色 [Indigo900]。主按钮、选中态、用户气泡
 * - **Ink**（石墨）：中性色。**刻意偏冷**（蓝灰而不是纯灰）—— 纯灰在深色下会发脏，
 *   而且和靛蓝放一起显得没配过色
 * - **Teal**（青绿）：信号色。只用在「已连接 / 成功」这类**状态**上。
 *   一台设备的指示灯就一个颜色，多了就没人看
 */

// ── 品牌：靛蓝 ────────────────────────────────────────────────────────────
val Indigo50 = Color(0xFFEEF2FF)
val Indigo100 = Color(0xFFE0E7FF)
val Indigo200 = Color(0xFFC7D2FE)
val Indigo300 = Color(0xFFA5B4FC)
val Indigo600 = Color(0xFF4F46E5)
val Indigo700 = Color(0xFF4338CA)
val Indigo900 = Color(0xFF312E81) // ← 图标底色
val Indigo950 = Color(0xFF1E1B4B)

// ── 中性：冷石墨 ──────────────────────────────────────────────────────────
val Ink0 = Color(0xFFFFFFFF)
val Ink25 = Color(0xFFFAFAFC)
val Ink50 = Color(0xFFF4F5F8)
val Ink100 = Color(0xFFEDEFF4)
val Ink200 = Color(0xFFDFE2EA)
val Ink300 = Color(0xFFC7CBD8)
val Ink500 = Color(0xFF6B7185)
val Ink600 = Color(0xFF4B5162)
val Ink700 = Color(0xFF333846)
val Ink800 = Color(0xFF1E2129)
val Ink900 = Color(0xFF14161C)
val Ink950 = Color(0xFF0E1015)

// ── 信号：青绿 ────────────────────────────────────────────────────────────
val Teal100 = Color(0xFFCCFBF1)
val Teal300 = Color(0xFF5EEAD4)
val Teal700 = Color(0xFF0F766E)
val Teal900 = Color(0xFF134E4A)

// ── 错误 ─────────────────────────────────────────────────────────────────
val Red100 = Color(0xFFF9DEDC)
val Red300 = Color(0xFFF2B8B5)
val Red700 = Color(0xFFB3261E)
val Red900 = Color(0xFF601410)
val RedContainerDark = Color(0xFF93000A)
