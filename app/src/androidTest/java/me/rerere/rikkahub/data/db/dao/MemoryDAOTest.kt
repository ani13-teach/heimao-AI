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
}
