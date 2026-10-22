package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Test

class RoomSchema32Test {
    @Test fun acceptsOnlyExactCurrentSchema() {
        val current = roomSchema32ExpectedSnapshotForTest()
        assertTrue(roomSchema32IsExact(current))
        assertFalse(roomSchema32IsExact(roomSchema31ExpectedSnapshotForTest()))
        assertTrue(roomSchema31IsExact(roomSchema31ExpectedSnapshotForTest()))
        assertFalse(roomSchema31IsExact(current))
        assertFalse(roomSchema32IsExact(current.copy(tables = current.tables.filterNot { it.name == "context_block" })))
        assertFalse(roomSchema32IsExact(current.copy(tables = current.tables.map {
            if (it.name == "context_block") it.copy(foreignKeys = emptyList()) else it
        })))
        assertEquals(listOf("ConversationEntity"), current.tables.single { it.name == "context_block" }.foreignKeys.map { it.table })
    }

    @Test fun manifestAccepts31And32AndRejectsOtherVersions() {
        listOf(31, 32).forEach { WorkspaceArchiveManifest(createdAt = 1, databaseVersion = it).validate() }
        listOf(30, 33).forEach { version ->
            assertTrue(runCatching { WorkspaceArchiveManifest(createdAt = 1, databaseVersion = version).validate() }.isFailure)
        }
    }
}
