package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用内存 fake DAO 验证 MemoryRepository 的分层记忆行为：
 * keyword LIKE 转义、writeLayeredMemory 的 create/edit/清空语义、archiveMemories 归属校验。
 */
class LayeredMemoryRepositoryTest {

    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableListOf<MemoryEntity>()
        var nextId = 1

        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
            flowOf(rows.filter { it.assistantId == assistantId }.sortedByDescending { it.id })

        override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId }.sortedByDescending { it.id }

        override suspend fun getMemoriesOfAssistantForPrompt(assistantId: String, limit: Int): List<MemoryEntity> =
            getMemoriesOfAssistant(assistantId).take(limit)

        override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = flowOf(rows)

        override suspend fun getAllMemories(): List<MemoryEntity> = rows.toList()

        override suspend fun getMemoryById(id: Int): MemoryEntity? = rows.firstOrNull { it.id == id }

        override suspend fun getMemoriesForLayeredIndex(assistantId: String, limit: Int): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.status == "active" }
                .sortedWith(compareByDescending<MemoryEntity> { it.updated }.thenByDescending { it.id })
                .take(limit)

        override suspend fun getActiveMemoriesForPrompt(assistantId: String, limit: Int): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.status == "active" && it.category == "ACTIVE" }
                .sortedWith(compareByDescending<MemoryEntity> { it.updated }.thenByDescending { it.id })
                .take(limit)

        override suspend fun searchMemories(
            assistantId: String,
            keyword: String,
            category: String?,
            confidencePattern: String?,
            status: String,
            limit: Int,
        ): List<MemoryEntity> {
            val pattern = "%$keyword%"
            return rows.filter { row ->
                row.assistantId == assistantId &&
                    row.status == status &&
                    (category == null || row.category == category) &&
                    (confidencePattern == null || likeMatch(row.confidenceFlags, confidencePattern)) &&
                    (keyword.isEmpty() || likeMatch(row.title, pattern) ||
                        likeMatch(row.tags, pattern) || likeMatch(row.content, pattern))
            }.sortedWith(compareByDescending<MemoryEntity> { it.updated }.thenByDescending { it.id }).take(limit)
        }

        override suspend fun getMemoriesByStatus(assistantId: String, status: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId && it.status == status }.sortedByDescending { it.id }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val entity = memory.copy(id = nextId++)
            rows += entity
            return entity.id.toLong()
        }

        override suspend fun updateMemory(memory: MemoryEntity) {
            val idx = rows.indexOfFirst { it.id == memory.id }
            check(idx >= 0) { "row ${memory.id} not found" }
            rows[idx] = memory
        }

        override suspend fun deleteMemory(id: Int) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
        }

        /** 模拟 SQL LIKE（% 通配任意、\\% 字面 %），用于验证仓储层已正确转义 keyword。 */
        private fun likeMatch(value: String, pattern: String): Boolean {
            val regex = buildString {
                append('^')
                var i = 0
                while (i < pattern.length) {
                    val c = pattern[i]
                    when {
                        c == '\\' && i + 1 < pattern.length -> {
                            append(Regex.escape(pattern[i + 1].toString()))
                            i++
                        }
                        c == '%' -> append(".*")
                        c == '_' -> append('.')
                        else -> append(Regex.escape(c.toString()))
                    }
                    i++
                }
                append('$')
            }
            return Regex(regex).matches(value)
        }
    }

    private fun repoWith(vararg rows: MemoryEntity): Pair<MemoryRepository, FakeMemoryDAO> {
        val dao = FakeMemoryDAO()
        rows.forEach { row ->
            dao.rows += row.copy(id = dao.nextId++)
        }
        return MemoryRepository(dao) to dao
    }

    @Test
    fun `searchMemories escapes wildcard keyword so percent is literal`() {
        val (repo, dao) = repoWith(
            MemoryEntity(id = 0, assistantId = "a", content = "discount 50% off", title = "sale"),
            MemoryEntity(id = 0, assistantId = "a", content = "full price list", title = "prices"),
        )
        val results = runBlocking { repo.searchMemories("a", "%", null, null, "active", 10) }
        // 转义后 "%" 只匹配字面含 % 的记录，而不是通配全部
        assertEquals(listOf(1), results.map { it.id })
    }

    @Test
    fun `searchMemories escapes underscore keyword`() {
        val (repo, _) = repoWith(
            MemoryEntity(id = 0, assistantId = "a", content = "param a_b", title = "t1"),
            MemoryEntity(id = 0, assistantId = "a", content = "param axb", title = "t2"),
        )
        val results = runBlocking { repo.searchMemories("a", "a_b", null, null, "active", 10) }
        assertEquals(listOf(1), results.map { it.id })
    }

    @Test
    fun `writeLayeredMemory create fills defaults and today date`() {
        val (repo, dao) = repoWith()
        val created = runBlocking {
            repo.writeLayeredMemory(
                assistantId = "a", id = null, category = "PARAMS", title = "baud",
                content = "baud=115200", confidenceFlags = listOf("✅实测已验证"),
                tags = listOf("params"), product = "P1",
            )
        }
        assertTrue(created.id > 0)
        assertEquals("active", created.status)
        assertEquals(java.time.LocalDate.now().toString(), created.updated)
        assertEquals("PARAMS", created.category)
        assertEquals(listOf("✅实测已验证"), created.confidenceFlags)
        assertEquals("P1", created.product)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `writeLayeredMemory create rejects missing required fields`() {
        val (repo, _) = repoWith()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repo.writeLayeredMemory(
                    assistantId = "a", id = null, category = "", title = "t",
                    content = "c", confidenceFlags = listOf("✅"), tags = null, product = null,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repo.writeLayeredMemory(
                    assistantId = "a", id = null, category = "PARAMS", title = "t",
                    content = "c", confidenceFlags = emptyList(), tags = null, product = null,
                )
            }
        }
    }

    @Test
    fun `writeLayeredMemory edit merges provided fields and allows clearing`() {
        val (repo, dao) = repoWith(
            MemoryEntity(
                id = 0, assistantId = "a", content = "old content", category = "PARAMS",
                title = "old title", updated = "2020-01-01",
                confidenceFlags = """["✅实测已验证"]""", tags = """["t1","t2"]""", product = "P1",
            )
        )
        val before = dao.rows.single()
        // 只更新 title + tags（清空 tags），其余保持
        val edited = runBlocking {
            repo.writeLayeredMemory(
                assistantId = "a", id = before.id, category = null, title = "new title",
                content = "old content", confidenceFlags = null, tags = emptyList(), product = null,
            )
        }
        assertEquals("new title", edited.title)
        assertEquals("PARAMS", edited.category)
        assertEquals(listOf("✅实测已验证"), edited.confidenceFlags)
        assertTrue(edited.tags.isEmpty())
        assertEquals("P1", edited.product)
        assertEquals(java.time.LocalDate.now().toString(), edited.updated)
    }

    @Test
    fun `writeLayeredMemory edit rejects record of another assistant`() {
        val (repo, _) = repoWith(
            MemoryEntity(id = 0, assistantId = "b", content = "other", title = "t")
        )
        val other = runBlocking { repo.searchMemories("b", "", null, null, "active", 10).single() }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.writeLayeredMemory(
                    assistantId = "a", id = other.id, category = null, title = null,
                    content = "x", confidenceFlags = null, tags = null, product = null,
                )
            }
        }
    }

    @Test
    fun `archiveMemories archives only ids belonging to the assistant`() {
        val (repo, dao) = repoWith(
            MemoryEntity(id = 0, assistantId = "a", content = "mine", title = "a1"),
            MemoryEntity(id = 0, assistantId = "b", content = "theirs", title = "b1"),
        )
        val mine = dao.rows.first { it.assistantId == "a" }.id
        val theirs = dao.rows.first { it.assistantId == "b" }.id

        val archived = runBlocking { repo.archiveMemories("a", listOf(mine, theirs, 999)) }
        assertEquals(listOf(mine), archived)
        assertEquals("archived", dao.rows.first { it.id == mine }.status)
        assertEquals("active", dao.rows.first { it.id == theirs }.status)
    }

    @Test
    fun `getLayeredMemoryContext excludes archived from index`() {
        val (repo, _) = repoWith(
            MemoryEntity(
                id = 0, assistantId = "a", content = "done stuff", category = "PARAMS",
                title = "done", status = "archived", updated = "2025-01-01",
            ),
            MemoryEntity(
                id = 0, assistantId = "a", content = "ongoing", category = "ACTIVE",
                title = "current", updated = "2025-02-01",
            ),
        )
        val ctx = runBlocking { repo.getLayeredMemoryContext("a") }
        assertFalse(ctx.indexText.contains("done"))
        assertTrue(ctx.indexText.contains("current"))
        assertEquals(1, ctx.indexCount)
        // ACTIVE 分类记录进入活跃状态段
        assertEquals(1, ctx.activeCount)
    }

}
