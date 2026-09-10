package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory_write 的端到端链路：工具 schema 入参 → 解析 → 仓储写库 → 返回体与 diff 元数据。
 *
 * 纯函数与仓储层分别有各自的测试，这里只固定它们之间的接线（模型实际看到的东西）。
 */
class LayeredMemoryWriteToolTest {

    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableListOf<MemoryEntity>()
        var nextId = 1

        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
            flowOf(rows.filter { it.assistantId == assistantId })

        override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId }.sortedByDescending { it.id }

        override suspend fun getMemoriesOfAssistantForPrompt(assistantId: String, limit: Int): List<MemoryEntity> =
            getMemoriesOfAssistant(assistantId).take(limit)

        override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = flowOf(rows)

        override suspend fun getAllMemories(): List<MemoryEntity> = rows.toList()

        override suspend fun getMemoryById(id: Int): MemoryEntity? = rows.firstOrNull { it.id == id }

        override suspend fun getMemoriesForLayeredIndex(assistantId: String, limit: Int): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.status == "active" }.take(limit)

        override suspend fun getActiveMemoriesForPrompt(assistantId: String, limit: Int): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.category == "ACTIVE" }.take(limit)

        override suspend fun searchMemories(
            assistantId: String,
            keyword: String,
            category: String?,
            confidencePattern: String?,
            status: String,
            limit: Int,
        ): List<MemoryEntity> = rows.filter { it.assistantId == assistantId && it.status == status }.take(limit)

        override suspend fun getMemoriesByStatus(assistantId: String, status: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.status == status }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val entity = memory.copy(id = nextId++)
            rows += entity
            return entity.id.toLong()
        }

        override suspend fun updateMemory(memory: MemoryEntity) {
            val index = rows.indexOfFirst { it.id == memory.id }
            check(index >= 0) { "row ${memory.id} not found" }
            rows[index] = memory
        }

        override suspend fun deleteMemory(id: Int) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun setup(
        content: String = "# 主轴参数\n主轴转速: 12000 rpm\n冷却方式: 水冷",
        assistantId: String = "a",
    ): Triple<Tool, FakeMemoryDAO, Int> {
        val dao = FakeMemoryDAO()
        dao.rows += MemoryEntity(
            id = dao.nextId++,
            assistantId = assistantId,
            content = content,
            category = "PARAMS",
            title = "主轴参数",
            updated = "2020-01-01",
            confidenceFlags = """["⚠️单板待确认"]""",
        )
        val repo = MemoryRepository(dao)
        val tool = buildLayeredMemoryTools(json = json, scopeId = "a", repo = repo)
            .single { it.name == "memory_write" }
        return Triple(tool, dao, dao.rows.single().id)
    }

    private fun run(tool: Tool, input: String): String = runBlocking {
        tool.execute(json.parseToJsonElement(input))
            .filterIsInstance<UIMessagePart.Text>()
            .first().text
    }

    private fun outputOf(tool: Tool, input: String): UIMessagePart.Text = runBlocking {
        tool.execute(json.parseToJsonElement(input)).filterIsInstance<UIMessagePart.Text>().first()
    }

    @Test
    fun `local edit changes one spot and reports diff metadata`() {
        val (tool, dao, id) = setup()

        val part = outputOf(
            tool,
            """{"action":"edit","id":$id,"edits":[{"old_text":"主轴转速: 12000 rpm","new_text":"主轴转速: 12500 rpm"}]}""",
        )
        val payload = json.parseToJsonElement(part.text).jsonObject

        assertEquals(true, payload["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("edit", payload["mode"]!!.jsonPrimitive.content)
        assertEquals(1, payload["replacements"]!!.jsonPrimitive.content.toInt())
        assertTrue(payload["fragment"]!!.jsonPrimitive.content.contains("12500"))

        // 其余部分逐字节不变
        assertEquals("# 主轴参数\n主轴转速: 12500 rpm\n冷却方式: 水冷", dao.rows.single().content)
        // 回执是摘要，不把整条正文塞回上下文
        val memory = payload["memory"]!!.jsonObject
        assertEquals(id, memory["id"]!!.jsonPrimitive.content.toInt())
        assertTrue(!memory.containsKey("content"))
        // diff 元数据供聊天界面渲染
        val diff = part.metadataAs<DiffMetadata>()?.diff
        assertTrue(diff != null && diff.contains("12500"))
    }

    @Test
    fun `several edits in one call all land`() {
        val (tool, dao, id) = setup()

        run(
            tool,
            """{"action":"edit","id":$id,"edits":[
                {"old_text":"主轴转速: 12000 rpm","new_text":"主轴转速: 12500 rpm"},
                {"old_text":"冷却方式: 水冷","new_text":"冷却方式: 风冷"}]}""",
        )

        assertEquals("# 主轴参数\n主轴转速: 12500 rpm\n冷却方式: 风冷", dao.rows.single().content)
    }

    @Test
    fun `missing or ambiguous anchor leaves the record untouched`() {
        val (tool, dao, id) = setup()

        assertThrows(RuntimeException::class.java) {
            run(tool, """{"action":"edit","id":$id,"edits":[{"old_text":"不存在的锚点","new_text":"x"}]}""")
        }
        assertEquals("# 主轴参数\n主轴转速: 12000 rpm\n冷却方式: 水冷", dao.rows.single().content)

        // "冷却方式" 在正文里只出现一次，再复制一行制造重复锚点
        dao.rows[0] = dao.rows[0].copy(content = "冷却方式: 水冷\n冷却方式: 水冷")
        assertThrows(RuntimeException::class.java) {
            run(tool, """{"action":"edit","id":$id,"edits":[{"old_text":"冷却方式: 水冷","new_text":"冷却方式: 风冷"}]}""")
        }
        assertEquals("冷却方式: 水冷\n冷却方式: 水冷", dao.rows.single().content)
    }

    @Test
    fun `edit with content is rejected and replace works`() {
        val (tool, dao, id) = setup()

        assertThrows(RuntimeException::class.java) {
            run(tool, """{"action":"edit","id":$id,"content":"整段新内容"}""")
        }
        assertEquals("# 主轴参数\n主轴转速: 12000 rpm\n冷却方式: 水冷", dao.rows.single().content)

        val payload = json.parseToJsonElement(
            run(tool, """{"action":"replace","id":$id,"content":"整理后的正文"}""")
        ).jsonObject
        assertEquals("replace", payload["mode"]!!.jsonPrimitive.content)
        assertEquals("整理后的正文", dao.rows.single().content)
    }

    @Test
    fun `edit without any change is rejected and keeps the old date`() {
        val (tool, dao, id) = setup()

        assertThrows(RuntimeException::class.java) { run(tool, """{"action":"edit","id":$id}""") }
        assertEquals("2020-01-01", dao.rows.single().updated)
    }

    @Test
    fun `records of another scope are rejected`() {
        val (tool, dao, _) = setup(assistantId = "b")

        assertThrows(RuntimeException::class.java) {
            run(tool, """{"action":"edit","id":${dao.rows.single().id},"edits":[{"old_text":"冷却方式: 水冷","new_text":"x"}]}""")
        }
        assertEquals("# 主轴参数\n主轴转速: 12000 rpm\n冷却方式: 水冷", dao.rows.single().content)
    }

    @Test
    fun `field-only edit applies a confidence upgrade without touching content`() {
        val (tool, dao, id) = setup()

        run(
            tool,
            """{"action":"edit","id":$id,"confidence_flags":["✅实测已验证"]}""",
        )

        assertEquals("""["✅实测已验证"]""", dao.rows.single().confidenceFlags)
        assertEquals("# 主轴参数\n主轴转速: 12000 rpm\n冷却方式: 水冷", dao.rows.single().content)
    }

    @Test
    fun `create returns the new id and no diff metadata`() {
        val (tool, dao, _) = setup()

        val part = outputOf(
            tool,
            """{"action":"create","category":"PARAMS","title":"进给","content":"进给速度: 3000",
               "confidence_flags":["✅实测已验证"]}""",
        )
        val payload = json.parseToJsonElement(part.text).jsonObject

        assertEquals("create", payload["mode"]!!.jsonPrimitive.content)
        assertEquals(2, payload["memory"]!!.jsonObject["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, dao.rows.size)
        assertEquals(null, part.metadataAs<DiffMetadata>()?.diff)
    }

    @Test
    fun `tool description advertises the local edit contract`() {
        val (tool, _, _) = setup()
        val description = tool.description

        assertTrue(description.contains("LOCAL by default"))
        assertTrue(description.contains("exactly once"))
        assertTrue(description.contains("all-or-nothing"))
        assertTrue(description.contains("action=replace"))
    }
}
