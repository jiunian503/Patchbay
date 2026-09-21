package com.aichat.ui.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.core.data.CharacterDraft
import com.aichat.core.data.CharacterEntity
import com.aichat.core.data.WorldBookEntryDraft
import com.aichat.di.AppContainer
import com.aichat.domain.character.CharacterCardException
import com.aichat.domain.character.CharacterCardParser
import com.aichat.domain.character.ImportedCharacter
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
    /** 一次导入的结果，展示完就清掉。null = 没有要说的。 */
    val importResult: ImportResult? = null,
)

/**
 * 一次导入的结果。
 *
 * 分「成功」「失败」两支而不是一个可空字符串：成功时除了名字和词条数，
 * 还要带上那张卡里**没导进来的东西** —— 那几条提示是这次导入里最该被看到
 * 的部分（用户会觉得「导入的东西不全」，却不知道缺了什么）。
 */
sealed interface ImportResult {

    data class Done(
        /** 落库后的角色 id，用来给「去编辑」跳转。 */
        val id: String,
        val name: String,
        val entryCount: Int,
        /** 卡里有、这个 App 装不下的东西。空 = 全导进来了。 */
        val warnings: List<String>,
    ) : ImportResult

    /** [reason] 是给用户看的一句话。 */
    data class Failed(val reason: String) : ImportResult
}

/**
 * 角色列表。
 *
 * ## 删除要确认，而且确认框里要写清后果
 *
 * 删一张角色卡会连带删掉它的世界书，还会把用它的会话的引用清空。
 * 前者不可恢复，后者会让那些会话**从此不再注入任何东西** ——
 * 用户如果没意识到第二件事，他会以为「换了角色之后这个会话变笨了」。
 * 所以确认框的正文要同时说这两件（见 `CharacterListScreen`）。
 *
 * ## 导入只接字节，不碰 `Uri`
 *
 * 读文件（`ContentResolver` + 那道大小上限）留在 Composable 那边。
 * 这个 ViewModel 拿到的是**已经读出来的字节**，于是「解析 → 落库 → 报告结果」
 * 这三步在 JVM 上就能跑 —— 而这三步恰好是最容易出错的部分。
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

    /**
     * 从一份角色卡文件导入。
     *
     * 导入是**直接落库**的，不做「先填进编辑页让用户确认」那一步：
     * 用户点「从文件导入」选了一张卡，意图已经明确；而多一步中间态就要多一个
     * 「草稿放在哪」的传递机制（跨页面传一个 `CharacterDraft`）。
     * 落库之后角色就在列表里，点「编辑」就能改 —— 不满意删掉也就是一次点击。
     *
     * 解析和保存分开 catch：**它们失败的后果完全不同**。「这张卡读不出来」
     * 是文件的问题，用户换一张就行；「保存失败」是库的问题，换卡没用。
     * 合成一个 catch 的话，两种情况下给的是同一句话。
     */
    fun importCard(bytes: ByteArray) {
        viewModelScope.launch {
            val imported = try {
                CharacterCardParser.parse(bytes)
            } catch (e: CharacterCardException) {
                // 解析器的消息本来就是写给人看的，直接展示，不再包一层
                report(ImportResult.Failed(e.message ?: "这张卡读不出来。"))
                return@launch
            }

            try {
                val id = container.characters.save(imported.toDraft())
                report(ImportResult.Done(id, imported.name, imported.entries.size, imported.warnings))
            } catch (e: Exception) {
                report(ImportResult.Failed(e.message ?: "保存失败。"))
            }
        }
    }

    /**
     * 文件**根本没读出来**（没有权限 / 文件已被移走 / 太大）。
     *
     * 和「读出来了但内容不对」是两件事：前者用户要去看文件本身，
     * 后者他要去看卡的内容。所以不走 [importCard]，单独一个入口。
     */
    fun importFailed(reason: String) = report(ImportResult.Failed(reason))

    fun dismissImportResult() = _state.update { it.copy(importResult = null) }

    private fun report(result: ImportResult) = _state.update { it.copy(importResult = result) }
}

/**
 * 导入结果 → 保存用的草稿。
 *
 * 逐字段搬运，没有判断。名字不能为空这件事在解析那一侧已经挡过一次
 * （见 `CharacterCardParser.mapCard`），`save` 里那道 `require` 只是兜底。
 */
private fun ImportedCharacter.toDraft() = CharacterDraft(
    name = name,
    description = description,
    persona = persona,
    firstMessage = firstMessage,
    alternateGreetings = alternateGreetings,
    entries = entries.map {
        WorldBookEntryDraft(
            keys = it.keys,
            content = it.content,
            enabled = it.enabled,
            caseSensitive = it.caseSensitive,
        )
    },
)
