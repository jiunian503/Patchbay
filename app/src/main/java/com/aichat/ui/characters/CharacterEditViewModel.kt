package com.aichat.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.CharacterDraft
import com.aichat.core.data.WorldBookEntryDraft
import com.aichat.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 表单里的一条词条。
 *
 * ## 触发词为什么是**一个**输入框
 *
 * 「同一个设定的几种叫法」在用户脑子里就是一句话（「老王、王叔、王建国」），
 * 拆成多个输入框会让人以为它们之间有主次关系 —— 而实际上任意一个命中
 * 都是等价的。分隔符在 [parseKeys] 里统一处理。
 */
data class WorldBookEntryForm(
    /** null = 这次新加的。 */
    val id: String? = null,
    val keysText: String = "",
    val content: String = "",
    val enabled: Boolean = true,
    val caseSensitive: Boolean = false,
)

data class CharacterEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val name: String = "",
    val description: String = "",
    val persona: String = "",
    val entries: List<WorldBookEntryForm> = emptyList(),
    /** 保存成功。界面据此返回上一页。 */
    val saved: Boolean = false,
    /** 保存失败的原因，直接展示。 */
    val error: String? = null,
)

class CharacterEditViewModel(
    private val container: AppContainer,
    private val characterId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(CharacterEditUiState())
    val state: StateFlow<CharacterEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (characterId == null) {
                _state.update { it.copy(loading = false, isNew = true) }
                return@launch
            }
            val row = container.characters.get(characterId)
            val entries = container.characters.entriesFor(characterId)
            _state.update {
                it.copy(
                    loading = false,
                    isNew = false,
                    name = row?.name.orEmpty(),
                    description = row?.description.orEmpty(),
                    persona = row?.persona.orEmpty(),
                    entries = entries.map { entry ->
                        WorldBookEntryForm(
                            id = entry.id,
                            // 用中文顿号回填：这是中文用户最顺手的写法，
                            // 而回填成英文逗号会让人以为只有那一种能用
                            keysText = entry.keys.joinToString("、"),
                            content = entry.content,
                            enabled = entry.enabled,
                            caseSensitive = entry.caseSensitive,
                        )
                    },
                )
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    fun setPersona(value: String) = _state.update { it.copy(persona = value) }

    fun addEntry() = _state.update { it.copy(entries = it.entries + WorldBookEntryForm()) }

    fun updateEntry(index: Int, transform: (WorldBookEntryForm) -> WorldBookEntryForm) =
        _state.update { current ->
            current.copy(
                entries =
                    current.entries.mapIndexed { i, entry ->
                        if (i == index) transform(entry) else entry
                    }
            )
        }

    fun removeEntry(index: Int) =
        _state.update { it.copy(entries = it.entries.filterIndexed { i, _ -> i != index }) }

    /**
     * 上移 / 下移一条词条。
     *
     * 顺序是有意义的（注入顺序），所以必须能调。但**不做拖拽**：
     * Compose 里实现一个可靠的拖拽排序要一整套手势与位移动画，
     * 而这里最多也就十几条 —— 两个按钮够了，而且它不会误触。
     *
     * 越界时静默不动（第一行点「上移」不该有任何反应，也不该崩）。
     */
    fun moveEntry(index: Int, delta: Int) = _state.update { current ->
        val target = index + delta
        if (index !in current.entries.indices || target !in current.entries.indices) {
            current
        } else {
            val reordered = current.entries.toMutableList()
            reordered[index] = reordered[target].also { reordered[target] = reordered[index] }
            current.copy(entries = reordered)
        }
    }

    fun save() {
        val snapshot = _state.value
        viewModelScope.launch {
            runCatching {
                    container.characters.save(
                        CharacterDraft(
                            id = characterId,
                            name = snapshot.name,
                            description = snapshot.description,
                            persona = snapshot.persona,
                            entries = snapshot.entries.map { it.toDraft() },
                        )
                    )
                }
                .onSuccess { _state.update { it.copy(saved = true, error = null) } }
                .onFailure { failure ->
                    // 失败原因直接给用户看（「角色名不能为空」这种话本来就是
                    // 写给人看的）。包一层「保存失败」反而把有用的那半句藏起来了
                    _state.update { it.copy(error = failure.message ?: "保存失败") }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

/**
 * 触发词输入框 → 列表。
 *
 * 中英文逗号、顿号、换行都算分隔符，空白段丢掉。
 *
 * 之所以要支持这么多种：用户在手机上打「老王、王叔」用的是中文顿号，
 * 从别处粘过来的是英文逗号，而手写一段设定时更习惯一行一个。
 * 只认其中一种的话，另外两种会被当成**一个超长的触发词**，
 * 于是它永远匹配不上 —— 而界面上看不出任何异常。
 */
internal fun parseKeys(text: String): List<String> =
    text.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun WorldBookEntryForm.toDraft() = WorldBookEntryDraft(
    id = id,
    keys = parseKeys(keysText),
    content = content,
    enabled = enabled,
    caseSensitive = caseSensitive,
)
