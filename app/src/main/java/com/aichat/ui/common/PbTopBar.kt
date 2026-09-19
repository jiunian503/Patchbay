package com.aichat.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

/**
 * 二级页面的顶栏。
 *
 * ## 为什么必须抽出来
 *
 * 这个 App 里一度有**两套返回样式**：
 *
 * - `Icon(PbIcons.ArrowBack)` —— 对话页、服务商列表、联网搜索设置
 * - `TextButton { Text("返回") }` —— 搜索页、插件列表、插件详情、插件安装、服务商编辑
 *
 * 两套都「能用」，所以写的时候谁都没觉得有问题 —— 直到把两个页面并排放在一起看。
 * 一次 `grep '"返回"'` 就把这五处全揪出来了：**同一个动作，两种长相**。
 *
 * 这不是审美偏好。返回是每个二级页面都有的**同一个动作**，它长什么样不该取决于
 * 当初是谁写的那个页面。用户不会说「这两个返回按钮不一样」，他只会觉得
 * 「这个 App 没做完」—— 而这种印象一旦形成，是没法指出具体哪里不对的。
 *
 * ## 为什么选箭头而不是文字
 *
 * - 文字「返回」占掉顶栏左侧约 80dp，而那里本该留给标题（长标题会被挤到截断）
 * - 蓝色文字在这个 App 里是「可点的强调」（`TextButton` 用 primary 色）。
 *   顶栏左上角放一个蓝字，会和标题抢注意力 —— 而标题才是这一屏的名字
 * - 箭头是**方向**，文字是**动作**。返回只需要表达方向
 *
 * ## 与 `ChatScreen` 顶栏的关系
 *
 * 对话页的顶栏左侧是**两种含义**（栈里只有它自己时是汉堡、否则是返回箭头，
 * 见 §55 ③），中间是带下拉的服务商/模型两行标题，右边是设置齿轮 —— 它比这个
 * 组件复杂得多，所以没有共用。但**左侧那个箭头本身是同一个** `PbIcons.ArrowBack`，
 * 这一点是一致的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PbTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            // onBack 为 null 时不占位：那是「根页面」，左侧本来就该是空的
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = PbIcons.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
