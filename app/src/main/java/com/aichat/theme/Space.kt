package com.aichat.theme

import androidx.compose.ui.unit.dp

/**
 * 间距。**只有这几档，不许写别的数。**
 *
 * 界面上「看着不整齐」十次里有八次是间距不成体系：这里 12dp、那里 13dp、
 * 另一个地方 10dp —— 单看每一处都说得过去，合起来就是没对齐。
 *
 * 全部取 4 的倍数（也就是 Material 的 8dp 网格的细分），
 * 于是任意两个间距相加还是网格上的值，嵌套布局不会累积出零头。
 */
object Space {
  /** 4dp —— 图标和它旁边的字之间 */
  val xs = 4.dp

  /** 8dp —— 紧密相关的一对元素 */
  val sm = 8.dp

  /** 12dp —— 默认内边距 */
  val md = 12.dp

  /** 16dp —— 屏幕左右边距、卡片内边距 */
  val lg = 16.dp

  /** 24dp —— 区块之间 */
  val xl = 24.dp

  /** 32dp —— 大段落之间、空状态上下的留白 */
  val xxl = 32.dp
}
