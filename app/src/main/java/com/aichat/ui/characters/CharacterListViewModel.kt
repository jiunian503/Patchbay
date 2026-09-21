package com.aichat.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.CharacterEntity
import com.aichat.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 列表页上的一行。
 *
 * **不复用 `CharacterEntity`**：界面要显示词条数，而那个数不在实体里。
 * 硬塞进去的话，实体就得带一个「不是 `character` 表里那一列」的字段，
 * 于是「实体 = 一行数据」这个前提就破了 —— 而那个前提正是
 * `AppDatabaseMigrationTest` 能拿实体去比对建表 SQL 的依据。
 */
data class CharacterRow(
    val id: String,
    val name: String,
    val description: String,
    val persona: String,
    val entryCount: Int,
)

data class CharacterListUiState(
    val loading: Boolean = true,
    val items: List<CharacterRow> = emptyList(),
    /** 等待确认删除的那一行。null = 没有待确认的。 */
    val pendingDelete: CharacterRow? = null,
)

/**
 * 角色列表。
 *
 * ## 删除要确认，而且确认框里要写清后果
 *
 * 删一张角色卡会连带删掉它的世界书，还会把用它的会话的引用清空。
 * 前者不可恢复，后者会让那些会话**从此不再注入任何东西** ——
 * 用户如果没意识到第二件事，他会以为「换了角色之后这个会话变笨了」。
 * 所以确认框的正文要同时说这两件（见 `CharacterListScreen`）。
 */
class CharacterListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CharacterListUiState())
    val state: StateFlow<CharacterListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 用 observe 而不是 list()：删完/改完回来，列表要自己更新。
            // 角色数量是个位到几十，每行多查一次词条数是可以接受的代价 ——
            // 换成一条聚合 SQL 会让这个页面被迫认识 `world_book_entry` 表结构
            container.characters.observe().collect { rows ->
                _state.update { current ->
                    current.copy(loading = false, items = rows.map { it.toRow() })
                }
            }
        }
    }

    private suspend fun CharacterEntity.toRow() = CharacterRow(
        id = id,
        name = name,
        description = description,
        persona = persona,
        entryCount = container.characters.entriesFor(id).size,
    )

    fun askDelete(row: CharacterRow) = _state.update { it.copy(pendingDelete = row) }

    fun dismissDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val row = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch { container.characters.delete(row.id) }
    }
}
