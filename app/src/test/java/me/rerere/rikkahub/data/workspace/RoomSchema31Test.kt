package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the restore gate for Room schema 31.
 *
 * Schema 31 adds `workspace_apt_plans` to schema 30. These tests pin two directions: the schema 30
 * gate must still accept the schema 30 fixture and reject schema 31, and the schema 31 gate must
 * accept a real schema 31 database while still rejecting every extra, missing or altered object.
 */
class RoomSchema31Test {
    @Test
    fun `real Room 31 fixture including workspace apt plans is accepted`() {
        assertTrue(roomSchema31IsExact(roomSchema31ExpectedSnapshotForTest()))
    }

    @Test
    fun `schema 31 fixture is the schema 30 fixture plus the apt plan table and its indices`() {
        val schema30 = roomSchema30ExpectedSnapshotForTest()
        val schema31 = roomSchema31ExpectedSnapshotForTest()

        assertEquals(schema30.tables.size + 1, schema31.tables.size)
        assertEquals(schema30.indices.size + 3, schema31.indices.size)
        assertEquals(schema30.objects.size + 4, schema31.objects.size)
        val addedTables = schema31.tables.map { it.name }.toSet() - schema30.tables.map { it.name }.toSet()
        assertEquals(setOf("workspace_apt_plans"), addedTables)
        val addedIndices = schema31.indices.map { it.name }.toSet() - schema30.indices.map { it.name }.toSet()
        assertEquals(
            setOf(
                "index_workspace_apt_plans_workspace_id_created_at",
                "index_workspace_apt_plans_conversation_id",
                "sqlite_autoindex_workspace_apt_plans_1",
            ),
            addedIndices,
        )
        assertEquals(
            schema30.sqliteMasterSql["message_fts"],
            schema31.sqliteMasterSql["message_fts"],
        )
    }

    @Test
    fun `the two gates are version specific`() {
        // A restored archive must be rejected by the wrong version's gate instead of being
        // accepted because it happens to carry the right object set.
        assertFalse(roomSchema31IsExact(roomSchema30ExpectedSnapshotForTest()))
        assertFalse(roomSchema30IsExact(roomSchema31ExpectedSnapshotForTest()))
        assertTrue(
            describeRoomSchema31Difference(roomSchema30ExpectedSnapshotForTest())
                .contains("missing tables=[workspace_apt_plans]"),
        )
    }

    @Test
    fun `SQLite generated apt plan primary index suffix may differ`() {
        val expected = roomSchema31ExpectedSnapshotForTest()
        val oldName = "sqlite_autoindex_workspace_apt_plans_1"
        val newName = "sqlite_autoindex_workspace_apt_plans_4"
        val renamed = expected.copy(
            indices = expected.indices.map { index ->
                if (index.name == oldName) index.copy(name = newName) else index
            },
            objects = expected.objects.map { objectInfo ->
                if (objectInfo.name == oldName) objectInfo.copy(name = newName) else objectInfo
            },
            sqliteMasterSql = expected.sqliteMasterSql.toMutableMap().apply {
                remove(oldName)
                put(newName, null)
            },
        )
        assertTrue(roomSchema31IsExact(renamed))
    }

    @Test
    fun `an extra table, index or trigger is rejected`() {
        val expected = roomSchema31ExpectedSnapshotForTest()
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    tables = expected.tables +
                        RoomSchemaTableSnapshot("restore_attack", emptyList(), emptyList()),
                    objects = expected.objects + RoomSchemaObjectSnapshot("table", "restore_attack"),
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("restore_attack" to "CREATE TABLE restore_attack (payload TEXT NOT NULL)"),
                ),
            ),
        )
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    indices = expected.indices + RoomSchemaIndexSnapshot(
                        table = "ConversationEntity",
                        name = "index_ConversationEntity_title_legacy",
                        unique = false,
                        columns = listOf("title"),
                        origin = "c",
                        partial = false,
                    ),
                    objects = expected.objects +
                        RoomSchemaObjectSnapshot("index", "index_ConversationEntity_title_legacy"),
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("index_ConversationEntity_title_legacy" to null),
                ),
            ),
        )
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    objects = expected.objects + RoomSchemaObjectSnapshot("trigger", "restore_attack"),
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("restore_attack" to "CREATE TRIGGER restore_attack AFTER INSERT ON workspaces BEGIN SELECT 1; END"),
                ),
            ),
        )
    }

    @Test
    fun `apt plan columns are compared exactly`() {
        val expected = roomSchema31ExpectedSnapshotForTest()
        fun planTable(mutate: (RoomSchemaTableSnapshot) -> RoomSchemaTableSnapshot) = expected.copy(
            tables = expected.tables.map { table ->
                if (table.name == "workspace_apt_plans") mutate(table) else table
            },
        )

        assertFalse(roomSchema31IsExact(planTable { table -> table.copy(columns = table.columns.filterNot { it.name == "state" }) }))
        assertFalse(
            roomSchema31IsExact(
                planTable { table ->
                    table.copy(columns = table.columns.filterNot { it.name == "state" } +
                        RoomSchemaColumnSnapshot("state", "TEXT", notNull = false, primaryKeyPosition = 0, defaultValue = null))
                },
            ),
        )
        assertFalse(
            roomSchema31IsExact(
                planTable { table ->
                    table.copy(columns = table.columns.map { column ->
                        if (column.name == "expires_at") column.copy(type = "TEXT") else column
                    })
                },
            ),
        )
        assertFalse(
            roomSchema31IsExact(
                planTable { table ->
                    table.copy(columns = table.columns +
                        RoomSchemaColumnSnapshot("legacy_column", "TEXT", notNull = false, primaryKeyPosition = 0, defaultValue = null))
                },
            ),
        )
        assertFalse(
            roomSchema31IsExact(
                planTable { table ->
                    table.copy(columns = table.columns.map { column ->
                        if (column.name == "plan_id") column.copy(primaryKeyPosition = 0) else column
                    })
                },
            ),
        )
    }

    @Test
    fun `apt plan indices are compared exactly`() {
        val expected = roomSchema31ExpectedSnapshotForTest()
        fun indices(mutate: (RoomSchemaIndexSnapshot) -> RoomSchemaIndexSnapshot) = expected.copy(
            indices = expected.indices.map { index ->
                if (index.name == "index_workspace_apt_plans_conversation_id") mutate(index) else index
            },
        )

        assertFalse(roomSchema31IsExact(indices { it.copy(unique = true) }))
        assertFalse(roomSchema31IsExact(indices { it.copy(columns = listOf("state")) }))
        assertFalse(roomSchema31IsExact(indices { it.copy(partial = true) }))
        assertFalse(roomSchema31IsExact(indices { it.copy(name = "index_workspace_apt_plans_state") }))

        val composite = "index_workspace_apt_plans_workspace_id_created_at"
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    indices = expected.indices.map { index ->
                        if (index.name == composite) {
                            index.copy(columns = listOf("created_at", "workspace_id"))
                        } else {
                            index
                        }
                    },
                ),
            ),
        )
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    indices = expected.indices.filterNot { it.name == composite },
                    objects = expected.objects.filterNot { it.name == composite },
                    sqliteMasterSql = expected.sqliteMasterSql - composite,
                ),
            ),
        )
    }

    @Test
    fun `version identity hash FTS declaration and shadow SQL are still enforced`() {
        val expected = roomSchema31ExpectedSnapshotForTest()
        assertFalse(roomSchema31IsExact(expected.copy(userVersion = 30)))
        assertFalse(roomSchema31IsExact(expected.copy(identityHash = "00000000000000000000000000000000")))
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("message_fts_data" to "CREATE TABLE message_fts_data (id INTEGER PRIMARY KEY, block TEXT)"),
                ),
            ),
        )
        val ftsSql = requireNotNull(expected.sqliteMasterSql["message_fts"])
        assertFalse(
            roomSchema31IsExact(
                expected.copy(
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("message_fts" to ftsSql.replace("tokenize='simple'", "tokenize='unicode61'")),
                ),
            ),
        )
    }

    @Test
    fun `diagnostic names the drifting apt plan objects`() {
        val expected = roomSchema31ExpectedSnapshotForTest()
        val composite = "index_workspace_apt_plans_workspace_id_created_at"
        val drifting = expected.copy(
            tables = expected.tables.filterNot { it.name == "workspace_apt_plans" } +
                RoomSchemaTableSnapshot("legacy_table", emptyList(), emptyList()),
            indices = expected.indices.filterNot { it.name == composite },
            objects = expected.objects.filterNot { it.name == "workspace_apt_plans" } +
                RoomSchemaObjectSnapshot("table", "legacy_table"),
            sqliteMasterSql = (expected.sqliteMasterSql - composite) +
                ("legacy_table" to "CREATE TABLE legacy_table (value TEXT)"),
        )

        val description = describeRoomSchema31Difference(drifting)
        assertTrue(description, description.contains("missing tables=[workspace_apt_plans]"))
        assertTrue(description, description.contains("unexpected tables=[legacy_table]"))
        assertTrue(description, description.contains("missing indices=[$composite"))
    }
}
