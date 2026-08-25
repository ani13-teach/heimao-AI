package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchema29Test {
    @Test
    fun `exact Room 29 snapshot is accepted`() {
        assertTrue(roomSchema29IsExact(roomSchema29ExpectedSnapshotForTest()))
    }

    @Test
    fun `Room 29 FTS5 shadow layout is derived from the accepted declaration`() {
        val expected = roomSchema29ExpectedSnapshotForTest()
        assertTrue(roomSchema29MessageFtsSqlIsExact(expected.sqliteMasterSql["message_fts"]))
        assertTrue(expected.tables.map { it.name }.containsAll(
            listOf(
                "message_fts",
                "message_fts_config",
                "message_fts_content",
                "message_fts_data",
                "message_fts_docsize",
                "message_fts_idx",
            )
        ))
        assertTrue(expected.indices.contains(
            RoomSchemaIndexSnapshot(
                table = "message_fts_config",
                name = "sqlite_autoindex_message_fts_config_1",
                unique = true,
                columns = listOf("k"),
                origin = "pk",
                partial = false,
            )
        ))
        assertTrue(expected.indices.contains(
            RoomSchemaIndexSnapshot(
                table = "message_fts_idx",
                name = "sqlite_autoindex_message_fts_idx_1",
                unique = true,
                columns = listOf("segid", "term"),
                origin = "pk",
                partial = false,
            )
        ))
        assertFalse(expected.objects.any { it.name == "sqlite_autoindex_message_fts_config_1" })
        assertFalse(expected.objects.any { it.name == "sqlite_autoindex_message_fts_idx_1" })
        assertTrue(expected.objects.any { it.name == "sqlite_autoindex_ConversationEntity_1" })
        assertTrue(roomSchema29IsExact(expected))
    }

    @Test
    fun `schema gate accepts SQLite migration column append order`() {
        val expected = roomSchema29ExpectedSnapshotForTest()
        val reordered = expected.copy(
            tables = expected.tables.map { table ->
                if (table.name == "ConversationEntity") {
                    table.copy(
                        columns = table.columns
                            .filterNot { it.name == "workspace_id" } +
                            table.columns.filter { it.name == "workspace_id" },
                    )
                } else {
                    table
                }
            },
        )
        assertTrue(roomSchema29IsExact(reordered))
    }

    @Test
    fun `schema gate rejects missing index, foreign key, and extra object`() {
        val expected = roomSchema29ExpectedSnapshotForTest()
        assertFalse(roomSchema29IsExact(expected.copy(indices = expected.indices.drop(1))))
        assertFalse(
            roomSchema29IsExact(
                expected.copy(
                    tables = expected.tables.map { table ->
                        if (table.name == "message_node") table.copy(foreignKeys = emptyList()) else table
                    }
                )
            )
        )
        listOf("table", "index", "view", "trigger").forEach { type ->
            assertFalse(
                roomSchema29IsExact(
                    expected.copy(
                        objects = expected.objects + RoomSchemaObjectSnapshot(type, "unexpected_$type"),
                    )
                )
            )
        }
    }

    @Test
    fun `schema gate rejects old version extra column and invalid FTS SQL`() {
        val expected = roomSchema29ExpectedSnapshotForTest()
        assertFalse(roomSchema29IsExact(expected.copy(userVersion = 28)))
        val messageFtsSql = requireNotNull(expected.sqliteMasterSql["message_fts"])
        listOf(
            messageFtsSql.replace("tokenize='simple'", "tokenize='unicode61'"),
            messageFtsSql.replace("text,", "body,"),
            messageFtsSql.replace("tokenize='simple'", "tokenize='simple',prefix='2'"),
        ).forEach { invalidSql ->
            assertFalse(
                roomSchema29IsExact(expected.copy(sqliteMasterSql = mapOf("message_fts" to invalidSql)))
            )
        }
        assertFalse(
            roomSchema29IsExact(
                expected.copy(
                    tables = expected.tables.map { table ->
                        if (table.name == "workspaces") {
                            table.copy(
                                columns = table.columns +
                                    RoomSchemaColumnSnapshot("unexpected", "TEXT", false, 0, null),
                            )
                        } else {
                            table
                        }
                    }
                )
            )
        )
    }
}
