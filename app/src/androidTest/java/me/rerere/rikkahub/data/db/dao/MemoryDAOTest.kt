package me.rerere.rikkahub.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: MemoryDAO

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.memoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun allMemoriesAreReturnedNewestFirst() = runBlocking {
        dao.insertMemory(MemoryEntity(assistantId = "__global__", content = "oldest global"))
        dao.insertMemory(MemoryEntity(assistantId = "assistant-a", content = "assistant memory"))
        dao.insertMemory(MemoryEntity(assistantId = "__global__", content = "newest global"))

        val expected = listOf("newest global", "assistant memory", "oldest global")

        assertEquals(expected, dao.getAllMemories().map { it.content })
        assertEquals(expected, dao.getAllMemoriesFlow().first().map { it.content })
    }

    @Test
    fun assistantMemoriesAreReturnedNewestFirst() = runBlocking {
        dao.insertMemory(MemoryEntity(assistantId = "assistant-a", content = "oldest"))
        dao.insertMemory(MemoryEntity(assistantId = "assistant-b", content = "other assistant"))
        dao.insertMemory(MemoryEntity(assistantId = "assistant-a", content = "newest"))

        val expected = listOf("newest", "oldest")

        assertEquals(expected, dao.getMemoriesOfAssistant("assistant-a").map { it.content })
        assertEquals(expected, dao.getMemoriesOfAssistantFlow("assistant-a").first().map { it.content })
    }

    @Test
    fun newColumnsHaveSqlDefaults() = runBlocking {
        // 通过原始 SQL 插入，验证数据库层面的列默认值
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO memoryentity (assistant_id, content) VALUES (?, ?)",
            arrayOf("assistant-a", "legacy row")
        )
        val entity = dao.getMemoriesOfAssistant("assistant-a").single()
        assertEquals("legacy row", entity.content)
        assertEquals("GENERAL", entity.category)
        assertEquals("", entity.title)
        assertEquals("1970-01-01", entity.updated)
        assertEquals("[]", entity.confidenceFlags)
        assertEquals("[]", entity.tags)
        assertEquals("", entity.product)
        assertEquals("active", entity.status)
    }

    @Test
    fun searchMemoriesFiltersByKeywordCategoryConfidenceAndStatus() = runBlocking {
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "voltage=5V",
                category = "PARAMS",
                title = "power rail",
                updated = "2025-01-02",
                confidenceFlags = """["✅实测已验证"]""",
                tags = """["power"]""",
            )
        )
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "use J-link to flash",
                category = "METHOD",
                title = "flashing steps",
                updated = "2025-01-03",
                confidenceFlags = """["⚠️单板待确认"]""",
                tags = """["flash","debug"]""",
            )
        )
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "old draft",
                category = "GENERAL",
                title = "stale note",
                updated = "2024-01-01",
                status = "archived",
            )
        )

        // keyword matches title
        assertEquals(listOf(3), dao.searchMemories("assistant-a", "stale", null, null, "archived", 10).map { it.id })
        // keyword matches tags
        assertEquals(listOf(2), dao.searchMemories("assistant-a", "flash", null, null, "active", 10).map { it.id })
        // keyword matches content
        assertEquals(listOf(1), dao.searchMemories("assistant-a", "voltage", null, null, "active", 10).map { it.id })
        // category filter
        assertEquals(listOf(2), dao.searchMemories("assistant-a", "", "METHOD", null, "active", 10).map { it.id })
        // confidence substring pattern
        assertEquals(listOf(2), dao.searchMemories("assistant-a", "", null, "%⚠️%", "active", 10).map { it.id })
        // status filter
        assertEquals(listOf(3), dao.searchMemories("assistant-a", "", null, null, "archived", 10).map { it.id })
        // combined: keyword + category + confidence
        assertEquals(
            listOf(2),
            dao.searchMemories("assistant-a", "flash", "METHOD", "%待确认%", "active", 10).map { it.id }
        )
        // limit
        assertEquals(listOf(2, 1), dao.searchMemories("assistant-a", "", null, null, "active", 2).map { it.id })
        // other assistant not matched
        assertTrue(dao.searchMemories("assistant-b", "", null, null, "active", 10).isEmpty())
    }

    @Test
    fun getActiveMemoriesForPromptReturnsNewestActiveOnly() = runBlocking {
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "old active",
                category = "ACTIVE",
                updated = "2024-01-01",
            )
        )
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "archived",
                category = "ACTIVE",
                updated = "2025-02-01",
                status = "archived",
            )
        )
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "new active",
                category = "ACTIVE",
                updated = "2025-01-02",
            )
        )
        dao.insertMemory(
            MemoryEntity(
                assistantId = "assistant-a",
                content = "middle active",
                category = "ACTIVE",
                updated = "2025-01-01",
            )
        )
        // 非 ACTIVE 分类（即使 status=active）不进活跃状态段
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "general note", updated = "2025-03-01")
        )

        assertEquals(
            listOf("new active", "middle active", "old active"),
            dao.getActiveMemoriesForPrompt("assistant-a", 10).map { it.content }
        )
        assertEquals(
            listOf("new active", "middle active"),
            dao.getActiveMemoriesForPrompt("assistant-a", 2).map { it.content }
        )
    }

    @Test
    fun getMemoriesForLayeredIndexOrdersActiveNewestFirstAndExcludesArchived() = runBlocking {
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "archived newer", updated = "2025-03-01", status = "archived")
        )
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "active older", updated = "2025-01-01")
        )
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "active newer", updated = "2025-02-01")
        )

        // 归档记录不出现在常驻索引中（可用 memory_read status=archived 检索）
        assertEquals(
            listOf("active newer", "active older"),
            dao.getMemoriesForLayeredIndex("assistant-a", 10).map { it.content }
        )
        assertEquals(
            listOf("active newer", "active older"),
            dao.getMemoriesForLayeredIndex("assistant-a", 2).map { it.content }
        )
    }

    @Test
    fun getMemoriesByStatusReturnsOnlyMatchingStatusNewestIdFirst() = runBlocking {
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "active 1", status = "active")
        )
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "archived", status = "archived")
        )
        dao.insertMemory(
            MemoryEntity(assistantId = "assistant-a", content = "active 2", status = "active")
        )

        assertEquals(listOf("active 2", "active 1"), dao.getMemoriesByStatus("assistant-a", "active").map { it.content })
        assertEquals(listOf("archived"), dao.getMemoriesByStatus("assistant-a", "archived").map { it.content })
    }
}
