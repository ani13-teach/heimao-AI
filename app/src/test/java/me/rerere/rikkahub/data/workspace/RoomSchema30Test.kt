package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchema30Test {
    @Test
    fun `real Room 30 fixture including workspace tables is accepted`() {
        assertTrue(roomSchema30IsExact(roomSchema30ExpectedSnapshotForTest()))
    }

    @Test
    fun `SQLite generated primary index suffix may differ`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        val oldName = "sqlite_autoindex_ConversationEntity_1"
        val newName = "sqlite_autoindex_ConversationEntity_7"
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
        assertTrue(roomSchema30IsExact(renamed))
    }

    @Test
    fun `FTS shadow primary index objects may be present or omitted`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        val withFtsIndexObjects = expected.copy(
            objects = expected.objects + listOf(
                RoomSchemaObjectSnapshot("index", "sqlite_autoindex_message_fts_config_1"),
                RoomSchemaObjectSnapshot("index", "sqlite_autoindex_message_fts_idx_1"),
            ),
            sqliteMasterSql = expected.sqliteMasterSql + mapOf(
                "sqlite_autoindex_message_fts_config_1" to null,
                "sqlite_autoindex_message_fts_idx_1" to null,
            ),
        )
        assertTrue(roomSchema30IsExact(withFtsIndexObjects))
    }

    @Test
    fun `unknown objects and missing critical schema are rejected`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        assertFalse(
            roomSchema30IsExact(
                expected.copy(
                    objects = expected.objects + RoomSchemaObjectSnapshot("table", "unexpected_table"),
                    sqliteMasterSql = expected.sqliteMasterSql + ("unexpected_table" to "CREATE TABLE unexpected_table (value TEXT)"),
                ),
            ),
        )
        assertFalse(
            roomSchema30IsExact(
                expected.copy(
                    tables = expected.tables.map { table ->
                        if (table.name == "workspace_snapshots") {
                            table.copy(columns = table.columns.filterNot { it.name == "manifest_hash" })
                        } else {
                            table
                        }
                    },
                ),
            ),
        )
        assertFalse(
            roomSchema30IsExact(
                expected.copy(
                    objects = expected.objects + RoomSchemaObjectSnapshot("trigger", "unexpected_trigger"),
                    sqliteMasterSql = expected.sqliteMasterSql + ("unexpected_trigger" to "CREATE TRIGGER unexpected_trigger"),
                ),
            ),
        )
    }

    @Test
    fun `diagnostic names the drifting objects`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        val drifting = expected.copy(
            tables = expected.tables.map { table ->
                when (table.name) {
                    "workspace_snapshots" -> table.copy(
                        columns = table.columns.filterNot { it.name == "manifest_hash" },
                    )

                    "ConversationEntity" -> table.copy(
                        columns = table.columns +
                            RoomSchemaColumnSnapshot(
                                name = "legacy_column",
                                type = "TEXT",
                                notNull = false,
                                primaryKeyPosition = 0,
                                defaultValue = null,
                            ),
                    )

                    else -> table
                }
            } + RoomSchemaTableSnapshot("legacy_table", emptyList(), emptyList()),
            indices = expected.indices.drop(1) +
                RoomSchemaIndexSnapshot(
                    table = "favorites",
                    name = "index_favorites_legacy",
                    unique = false,
                    columns = listOf("type"),
                    origin = "c",
                    partial = false,
                ),
            objects = expected.objects + RoomSchemaObjectSnapshot("table", "legacy_table"),
            sqliteMasterSql = expected.sqliteMasterSql +
                ("legacy_table" to "CREATE TABLE legacy_table (value TEXT)"),
        )

        val description = describeRoomSchema30Difference(drifting)
        assertTrue(description, description.contains("unexpected tables=[legacy_table]"))
        assertTrue(description, description.contains("workspace_snapshots missing columns=[manifest_hash]"))
        assertTrue(description, description.contains("ConversationEntity unexpected columns=[legacy_column]"))
        assertTrue(description, description.contains("unexpected indices=[index_favorites_legacy"))
        assertTrue(description, description.contains("missing indices="))
    }

    @Test
    fun `a column a generated migration added with DEFAULT NULL is accepted`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        val fromMigration = expected.copy(
            tables = expected.tables.map { table ->
                if (table.name != "GenMediaEntity") {
                    table
                } else {
                    table.copy(
                        columns = table.columns.map { column ->
                            if (column.name == "source_paths") column.copy(defaultValue = "NULL") else column
                        },
                    )
                }
            },
        )

        assertTrue(roomSchema30IsExact(fromMigration))
        val description = describeRoomSchema30Difference(fromMigration)
        assertFalse(description, description.contains("GenMediaEntity"))
    }

    @Test
    fun `a changed default value is still rejected`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        val changed = expected.copy(
            tables = expected.tables.map { table ->
                if (table.name != "GenMediaEntity") {
                    table
                } else {
                    table.copy(
                        columns = table.columns.map { column ->
                            if (column.name == "type") column.copy(defaultValue = "'video_generation'") else column
                        },
                    )
                }
            },
        )

        assertFalse(roomSchema30IsExact(changed))
        assertTrue(describeRoomSchema30Difference(changed).contains("GenMediaEntity changed columns=[type"))
    }

    @Test
    fun `missing index, altered shadow SQL, and wrong FTS declaration are rejected`() {
        val expected = roomSchema30ExpectedSnapshotForTest()
        assertFalse(roomSchema30IsExact(expected.copy(indices = expected.indices.drop(1))))
        assertFalse(
            roomSchema30IsExact(
                expected.copy(
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("message_fts_data" to "CREATE TABLE message_fts_data (id INTEGER PRIMARY KEY, block TEXT)"),
                ),
            ),
        )
        val ftsSql = requireNotNull(expected.sqliteMasterSql["message_fts"])
        assertFalse(
            roomSchema30IsExact(
                expected.copy(
                    sqliteMasterSql = expected.sqliteMasterSql +
                        ("message_fts" to ftsSql.replace("tokenize='simple'", "tokenize='unicode61'")),
                ),
            ),
        )
    }
}
