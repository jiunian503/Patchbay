package com.aichat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * 历史消息检索。
 *
 * 放在主界面这一支而不是「设置」下面：搜索是**日常动作**，
 * 用户一天可能用十几次；插件那种配置才是装一次就不再看的。
 *
 * 名字和 `com.aichat.domain.search.ConversationSearch`（检索接口）一样。
 * 两者不会在同一份 import 里出现 —— 导航层不碰检索接口，检索实现也不认识
 * 导航键。真要在同一处用上时用全限定名，别为了这个把其中一个改名叫
 * `ConversationSearchRoute` 之类的：两个名字都各得其所。
 */
@Serializable data object ConversationSearch : NavKey

/**
 * 对话页。**这是 App 的首页**（起始目的地）。
 *
 * ## 会话列表去哪了
 *
 * 原来有一个独立的 `ConversationList` 目的地，现在没有了 ——
 * 列表变成了 [Chat] 页面左边的抽屉（`ConversationDrawer`）。
 *
 * 理由：「换会话」和「接着聊」是同一个动作的两面。两级结构逼用户在
 * 想换会话的那一刻先退出对话，而那时他人在对话里。
 *
 * 连带的好处是**冷启动可以直接落在会话里**：起始目的地就是
 * `Chat(上次退出的那个会话)`，不用先看一眼列表（见 `MainNavigation`）。
 *
 * 只带 id，不带消息内容 —— 消息从数据库读。把内容塞进导航参数会让
 * 返回栈里躺着一份可能过期的副本，而且进程被回收后恢复出来的还是旧数据。
 *
 * [highlightMessageId] 是「从搜索结果点进来时，要定位到的那条消息」。
 * 默认 `null` 就是普通的打开会话（落到底部）。
 *
 * [highlightQuery] 是**同一个来源**带过来的查询词，用来在那条消息里
 * 把命中的词标出来。它和 [highlightMessageId] 一起出现、一起消失 ——
 * 只有一个的话这个功能就是半截的：定位到了却还得自己找那个词。
 *
 * 两个都是**一次性的定位意图**，不是会话状态：用户进去之后继续聊，
 * 它们就不该再有任何作用（所以都不参与 `ChatUiState`）。
 */
@Serializable data class Chat(
    val conversationId: String,
    val highlightMessageId: Long? = null,
    val highlightQuery: String? = null,
) : NavKey

/** 服务商列表（BYOK 设置）。 */
@Serializable data object ProviderList : NavKey

/**
 * 角色卡列表。
 *
 * 放在「设置」这一支下面，但它和 [PluginList] 那种「装一次就不再看」的配置
 * 不同：**角色是用户会反复换的**。之所以还是放设置里而不是主界面，
 * 是因为「换角色」不是每句话都要做的事 —— 一个会话选定之后通常一直用它，
 * 会话内的切换入口在对话页顶栏。
 */
@Serializable data object CharacterList : NavKey

/** 角色卡编辑。[characterId] 为 null 表示新建。 */
@Serializable data class CharacterEdit(val characterId: String? = null) : NavKey

/**
 * 联网搜索的设置页。
 *
 * 名字里带 `Settings` 是因为它确实只是一组配置（后端 + 地址 + 密钥），
 * 不是一个功能页面 —— 搜索这个功能本身没有自己的界面，它是模型手里的一个工具。
 */
@Serializable data object WebSearchSettings : NavKey

/** 服务商编辑。[providerId] 为 null 表示新建。 */
@Serializable data class ProviderEdit(val providerId: String? = null) : NavKey

/**
 * 插件列表。
 *
 * 放在「设置」那一支下面而不是主界面：插件是**配置**，不是日常动作。
 * 用户装一次、填一次密钥，之后就不该再看到它。
 */
@Serializable data object PluginList : NavKey

/** 安装插件（粘贴清单）。 */
@Serializable data object PluginInstall : NavKey

/** 插件详情与配置。 */
@Serializable data class PluginDetail(val pluginId: String) : NavKey

/**
 * 崩溃记录。
 *
 * 放在「设置」那一支下面，和 [PluginList] 同理：它是**诊断**，不是日常动作 ——
 * 多数用户一辈子只进来一次，而那次往往是为了把堆栈复制给别人看。
 *
 * 页面上会写明这些堆栈只存在本机、不含会话内容与密钥。因为「崩溃记录」
 * 这四个字的第一反应是「我的东西被传走了吗」，而答案要由界面自己给出，
 * 不能指望用户去翻文档（见 `com.aichat.crash` 那两个文件的 KDoc）。
 */
@Serializable data object CrashLogs : NavKey
