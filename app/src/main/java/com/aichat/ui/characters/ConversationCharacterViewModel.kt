package com.aichat.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「这个会话用哪个角色」。
 *
 * ## 为什么独立于 `ChatViewModel`
 *
 * 那个类只依赖 `ChatDeps` 这个窄接口 —— 它认识的只有「服务商、历史、发送」。
 * 角色是**宿主侧**的概念（`:chat` 连 Room 都不认识），塞进去等于让对话
 * ViewModel 去认识一张它不该知道的表。分开之后，角色选择器出问题也不会
 * 影响发送这条主路径。
 *
 * ## 选中的角色被删掉时会怎样
 *
 * `CharacterRepository.delete` 会把引用它的会话的 `character_id` 清空，
 * 所以 [UiState.selectedId] 下一次 [reload] 就会变成 null —— 界面自动回到
 * 「不用角色」，而不是显示一个已经不存在的名字。**这正是那个设计的目的**：
 * 删角色不该让会话停摆，只该让它变回默认。
 */
class ConversationCharacterViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    data class UiState(
        val selectedId: String? = null,
        /** 选中角色的显示名。角色被删掉之后会变回 null。 */
        val selectedName: String? = null,
        val characters: List<CharacterRow> = emptyList(),
        /** 选择器是否展开。 */
        val picking: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 监听整个角色表而不是「查一次」：用户在别处改了名字、删了角色，
            // 这个弹窗里的列表要跟着变 —— 否则他会点到一个已经没了的角色
            container.characters.observe().collect { rows ->
                _state.update { current ->
                    current.copy(
                        characters =
                            rows.map {
                                CharacterRow(
                                    id = it.id,
                                    name = it.name,
                                    description = it.description,
                                    persona = it.persona,
                                    // 选择器不显示词条数，省掉一次查询
                                    entryCount = 0,
                                )
                            },
                        selectedName = rows.firstOrNull { it.id == current.selectedId }?.name,
                    )
                }
            }
        }
        reload()
    }

    /**
     * 重读当前会话选的角色。
     *
     * 从设置页回来时要调 —— 用户可能在那边把正在用的角色删了或改了名，
     * 而 ViewModel 不会因为「回到前台」重建。
     *
     * 走 [AppContainer.effectiveCharacterId] 而不是直接读会话表：会话行
     * 还没建出来时，选择暂存在设置里，只有那个方法知道。用 `get()` 的话，
     * 新建对话里选完角色、界面会立刻跳回「不用角色」—— 看着像没选上。
     */
    fun reload() {
        viewModelScope.launch {
            val id = container.effectiveCharacterId(conversationId)
            _state.update {
                it.copy(selectedId = id, selectedName = id?.let { key -> container.characters.get(key)?.name })
            }
        }
    }

    fun openPicker() = _state.update { it.copy(picking = true) }

    fun closePicker() = _state.update { it.copy(picking = false) }

    /**
     * 选中一个角色，`null` = 不用角色。
     *
     * **先关弹窗再落盘**：落盘是一次数据库往返，等它回来才关的话，
     * 用户会看到一个「点了没反应」的弹窗。落盘失败的可能性极低
     * （一次单列 UPDATE），而且 [reload] 会把界面拉回真实值。
     */
    fun select(characterId: String?) {
        _state.update { it.copy(picking = false, selectedId = characterId) }
        viewModelScope.launch {
            container.selectCharacter(conversationId, characterId)
            reload()
        }
    }
}
