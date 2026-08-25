package me.rerere.rikkahub.data.workspace

/**
 * SQLite metadata captured from a staged database. The shape is deliberately independent of
 * Android's SQLite classes so the archive gate can be regression-tested on the JVM.
 *
 * The Room-owned portion is generated from
 * app/schemas/me.rerere.rikkahub.data.db.AppDatabase/30.json; the onOpen FTS5 declaration is
 * validated from sqlite_master.sql against DataSourceModule, with its shadow tables derived from
 * the accepted virtual-table definition. Keep both in lockstep whenever the database version or
 * onOpen schema changes.
 */
internal data class RoomSchemaColumnSnapshot(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int,
    val defaultValue: String?,
)

internal data class RoomSchemaForeignKeySnapshot(
    val table: String,
    val onDelete: String,
    val onUpdate: String,
    val columns: List<String>,
    val referencedColumns: List<String>,
    val match: String = "NONE",
)

internal data class RoomSchemaTableSnapshot(
    val name: String,
    val columns: List<RoomSchemaColumnSnapshot>,
    val foreignKeys: List<RoomSchemaForeignKeySnapshot>,
)

internal data class RoomSchemaIndexSnapshot(
    val table: String,
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
    val origin: String,
    val partial: Boolean,
)

internal data class RoomSchemaObjectSnapshot(
    val type: String,
    val name: String,
)

internal data class RoomSchemaSnapshot(
    val userVersion: Int,
    val identityHash: String,
    val tables: List<RoomSchemaTableSnapshot>,
    val indices: List<RoomSchemaIndexSnapshot>,
    val objects: List<RoomSchemaObjectSnapshot>,
    /** SQL definitions read from sqlite_master, keyed by object name. */
    val sqliteMasterSql: Map<String, String?> = emptyMap(),
)

internal data class RoomFts5ColumnDefinition(
    val name: String,
    val unindexed: Boolean,
)

internal data class RoomFts5Definition(
    val tableName: String,
    val columns: List<RoomFts5ColumnDefinition>,
    val tokenizer: String,
)

/**
 * Normalize the CREATE VIRTUAL TABLE statement stored by SQLite and return its FTS5 definition.
 *
 * The archive gate intentionally parses the sqlite_master SQL instead of treating PRAGMA
 * table_info as proof of the module, tokenizer, or options. SQLite normalizes whitespace and
 * preserves the original statement differently across implementations, so comparison is made on
 * a small canonical form after parsing the SQL grammar used by DataSourceModule.
 */
internal fun normalizeRoomSchema29MessageFtsSql(sql: String?): String? {
    val definition = parseRoomSchema29MessageFtsSql(sql) ?: return null
    return buildString {
        append("create virtual table ")
        append(definition.tableName)
        append(" using fts5(")
        definition.columns.forEachIndexed { index, column ->
            if (index > 0) append(',')
            append(column.name)
            if (column.unindexed) append(" unindexed")
        }
        append(",tokenize='")
        append(definition.tokenizer)
        append("')")
    }
}

/** Exact FTS declaration used by DataSourceModule, independent of SQLite's SQL formatting. */
internal fun roomSchema29MessageFtsSqlIsExact(sql: String?): Boolean =
    normalizeRoomSchema29MessageFtsSql(sql) == ROOM_SCHEMA_29_MESSAGE_FTS_SQL

/** Exact current Room 30 acceptance gate: no old-version migration and no extension objects are allowed. */
internal fun roomSchema29IsExact(snapshot: RoomSchemaSnapshot): Boolean {
    if (snapshot.userVersion != ROOM_SCHEMA_29_VERSION) return false
    if (snapshot.identityHash.lowercase() != ROOM_SCHEMA_29_IDENTITY_HASH) return false

    val messageFtsSql = snapshot.sqliteMasterSql["message_fts"]
    if (!roomSchema29MessageFtsSqlIsExact(messageFtsSql)) return false
    val ftsDefinition = parseRoomSchema29MessageFtsSql(messageFtsSql)
        ?: return false
    val expectedTables = roomSchema29ExpectedTables(ftsDefinition)
    val expectedIndices = roomSchema29ExpectedIndices(ftsDefinition)
    val expectedObjects = roomSchema29ExpectedObjects(expectedTables, expectedIndices)

    if (snapshot.tables.size != expectedTables.size) return false
    val actualTables = snapshot.tables.associateBy { it.name }
    if (actualTables.keys != expectedTables.keys) return false
    expectedTables.forEach { (name, expected) ->
        val actual = actualTables[name] ?: return false
        // SQLite ALTER TABLE migrations append columns; Room's generated JSON retains entity
        // declaration order. Column order is not part of the SQLite schema contract, so compare
        // the complete column descriptors by name while still rejecting every extra/missing or
        // changed type/default/nullability descriptor.
        if (actual.columns.sortedBy { it.name } != expected.columns.sortedBy { it.name }) return false
        if (actual.foreignKeys.sortedWith(FOREIGN_KEY_ORDER) != expected.foreignKeys.sortedWith(FOREIGN_KEY_ORDER)) {
            return false
        }
    }

    if (snapshot.indices.size != expectedIndices.size) return false
    val expectedIndexSet = expectedIndices
        .map { it.table to it }
        .toSet()
    val actualIndexSet = snapshot.indices
        .map { it.table to it }
        .toSet()
    if (actualIndexSet != expectedIndexSet) return false

    return snapshot.objects.size == expectedObjects.size &&
        snapshot.objects.toSet() == expectedObjects
}

/** JVM-test fixture derived from the same Room 30 specification used by the restore gate. */
internal fun roomSchema29ExpectedSnapshotForTest(): RoomSchemaSnapshot {
    val ftsDefinition = parseRoomSchema29MessageFtsSql(ROOM_SCHEMA_29_MESSAGE_FTS_SQL)
        ?: error("Invalid Room 29 FTS fixture")
    val tables = roomSchema29ExpectedTables(ftsDefinition)
    val indices = roomSchema29ExpectedIndices(ftsDefinition)
    return RoomSchemaSnapshot(
        userVersion = ROOM_SCHEMA_29_VERSION,
        identityHash = ROOM_SCHEMA_29_IDENTITY_HASH,
        tables = tables.values.toList(),
        indices = indices,
        objects = roomSchema29ExpectedObjects(tables, indices).toList(),
        sqliteMasterSql = mapOf("message_fts" to ROOM_SCHEMA_29_MESSAGE_FTS_SQL),
    )
}

private const val ROOM_SCHEMA_29_VERSION = 30
private const val ROOM_SCHEMA_29_IDENTITY_HASH = "3979b81ecdae08bd3d51457e69ba2b2b"

private val FOREIGN_KEY_ORDER = compareBy<RoomSchemaForeignKeySnapshot>(
    { it.table },
    { it.columns.joinToString("\u0000") },
    { it.referencedColumns.joinToString("\u0000") },
)

private fun column(
    name: String,
    type: String,
    notNull: Boolean,
    primaryKeyPosition: Int = 0,
    defaultValue: String? = null,
) = RoomSchemaColumnSnapshot(name, type, notNull, primaryKeyPosition, defaultValue)

private fun table(
    name: String,
    columns: List<RoomSchemaColumnSnapshot>,
    foreignKeys: List<RoomSchemaForeignKeySnapshot> = emptyList(),
) = RoomSchemaTableSnapshot(name, columns, foreignKeys)

private fun index(
    table: String,
    name: String,
    unique: Boolean,
    columns: List<String>,
    origin: String,
) = RoomSchemaIndexSnapshot(table, name, unique, columns, origin, partial = false)

private fun parseRoomSchema29MessageFtsSql(sql: String?): RoomFts5Definition? {
    if (sql == null) return null
    val cursor = SqlCursor(sql)
    if (!cursor.consumeKeyword("CREATE") ||
        !cursor.consumeKeyword("VIRTUAL") ||
        !cursor.consumeKeyword("TABLE")
    ) {
        return null
    }
    if (cursor.consumeKeyword("IF")) {
        if (!cursor.consumeKeyword("NOT") || !cursor.consumeKeyword("EXISTS")) return null
    }
    val tableName = cursor.readIdentifier() ?: return null
    if (tableName != "message_fts" || !cursor.consumeKeyword("USING")) return null
    val module = cursor.readIdentifier() ?: return null
    if (!module.equals("fts5", ignoreCase = true)) return null
    cursor.skipWhitespace()
    if (!cursor.consume('(')) return null
    val arguments = cursor.readParenthesizedContents() ?: return null
    cursor.skipWhitespace()
    if (cursor.consume(';')) cursor.skipWhitespace()
    if (!cursor.isAtEnd()) return null

    val parts = splitTopLevelSqlList(arguments)
    if (parts.size != ROOM_SCHEMA_29_FTS_ARGUMENT_COUNT) return null
    val columns = parts.take(ROOM_SCHEMA_29_FTS_COLUMN_COUNT).map { part ->
        parseFtsColumn(part) ?: return null
    }
    if (columns != ROOM_SCHEMA_29_FTS_COLUMNS) return null
    val tokenizer = parseTokenizerArgument(parts.last()) ?: return null
    return RoomFts5Definition(tableName, columns, tokenizer)
}

private fun parseFtsColumn(sql: String): RoomFts5ColumnDefinition? {
    val cursor = SqlCursor(sql)
    val name = cursor.readIdentifier() ?: return null
    val unindexed = cursor.consumeKeyword("UNINDEXED")
    cursor.skipWhitespace()
    return if (cursor.isAtEnd()) RoomFts5ColumnDefinition(name, unindexed) else null
}

private fun parseTokenizerArgument(sql: String): String? {
    val cursor = SqlCursor(sql)
    val key = cursor.readIdentifier() ?: return null
    if (!key.equals("tokenize", ignoreCase = true)) return null
    cursor.skipWhitespace()
    if (!cursor.consume('=')) return null
    val value = cursor.readSingleQuotedString() ?: return null
    return value.takeIf { it == "simple" }
}

private fun splitTopLevelSqlList(sql: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var index = 0
    var parentheses = 0
    var quote: Char? = null
    while (index < sql.length) {
        val character = sql[index]
        if (quote != null) {
            if (character == quote) {
                if (index + 1 < sql.length && sql[index + 1] == quote) {
                    index += 2
                    continue
                }
                quote = null
            }
            index++
            continue
        }
        when (character) {
            '\'', '"', '`' -> quote = character
            '[' -> quote = ']'
            '(' -> parentheses++
            ')' -> {
                if (parentheses == 0) return emptyList()
                parentheses--
            }
            ',' -> if (parentheses == 0) {
                val part = sql.substring(start, index).trim()
                if (part.isEmpty()) return emptyList()
                parts += part
                start = index + 1
            }
        }
        index++
    }
    if (quote != null || parentheses != 0) return emptyList()
    val last = sql.substring(start).trim()
    if (last.isEmpty()) return emptyList()
    parts += last
    return parts
}

private class SqlCursor(private val sql: String) {
    private var index = 0

    fun consumeKeyword(keyword: String): Boolean {
        val saved = index
        skipWhitespace()
        val end = index + keyword.length
        if (end > sql.length || !sql.regionMatches(index, keyword, 0, keyword.length, ignoreCase = true)) {
            index = saved
            return false
        }
        if (end < sql.length && isIdentifierCharacter(sql[end])) {
            index = saved
            return false
        }
        index = end
        return true
    }

    fun consume(expected: Char): Boolean {
        skipWhitespace()
        if (index >= sql.length || sql[index] != expected) return false
        index++
        return true
    }

    fun readIdentifier(): String? {
        skipWhitespace()
        if (index >= sql.length) return null
        val first = sql[index]
        if (first == '"' || first == '`' || first == '[') {
            val closing = if (first == '[') ']' else first
            index++
            val value = StringBuilder()
            while (index < sql.length) {
                val character = sql[index++]
                if (character == closing) {
                    if (index < sql.length && sql[index] == closing) {
                        value.append(closing)
                        index++
                    } else {
                        return value.toString()
                    }
                } else {
                    value.append(character)
                }
            }
            return null
        }
        if (!isIdentifierStart(first)) return null
        val start = index++
        while (index < sql.length && isIdentifierCharacter(sql[index])) index++
        return sql.substring(start, index)
    }

    fun readSingleQuotedString(): String? {
        skipWhitespace()
        if (index >= sql.length || sql[index] != '\'') return null
        index++
        val value = StringBuilder()
        while (index < sql.length) {
            val character = sql[index++]
            if (character == '\'') {
                if (index < sql.length && sql[index] == '\'') {
                    value.append('\'')
                    index++
                } else {
                    skipWhitespace()
                    return value.toString().takeIf { isAtEnd() }
                }
            } else {
                value.append(character)
            }
        }
        return null
    }

    fun readParenthesizedContents(): String? {
        val start = index
        var depth = 1
        var quote: Char? = null
        while (index < sql.length) {
            val character = sql[index]
            if (quote != null) {
                if (character == quote) {
                    if (index + 1 < sql.length && sql[index + 1] == quote) {
                        index += 2
                        continue
                    }
                    quote = null
                }
                index++
                continue
            }
            when (character) {
                '\'', '"', '`' -> quote = character
                '[' -> quote = ']'
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        val contents = sql.substring(start, index)
                        index++
                        return contents
                    }
                }
            }
            index++
        }
        return null
    }

    fun skipWhitespace() {
        while (index < sql.length && sql[index].isWhitespace()) index++
    }

    fun isAtEnd(): Boolean = index == sql.length
}

private fun isIdentifierStart(character: Char): Boolean =
    character == '_' || character.isLetter()

private fun isIdentifierCharacter(character: Char): Boolean =
    isIdentifierStart(character) || character.isDigit() || character == '$'

private const val ROOM_SCHEMA_29_FTS_COLUMN_COUNT = 6
private const val ROOM_SCHEMA_29_FTS_ARGUMENT_COUNT = ROOM_SCHEMA_29_FTS_COLUMN_COUNT + 1
private val ROOM_SCHEMA_29_FTS_COLUMNS = listOf(
    RoomFts5ColumnDefinition("text", unindexed = false),
    RoomFts5ColumnDefinition("node_id", unindexed = true),
    RoomFts5ColumnDefinition("message_id", unindexed = true),
    RoomFts5ColumnDefinition("conversation_id", unindexed = true),
    RoomFts5ColumnDefinition("title", unindexed = true),
    RoomFts5ColumnDefinition("update_at", unindexed = true),
)
private const val ROOM_SCHEMA_29_MESSAGE_FTS_SQL =
    "create virtual table message_fts using fts5(text,node_id unindexed,message_id unindexed,conversation_id unindexed,title unindexed,update_at unindexed,tokenize='simple')"

private val ROOM_SCHEMA_29_TABLES: Map<String, RoomSchemaTableSnapshot> = linkedMapOf(
    "ConversationEntity" to table(
        "ConversationEntity",
        listOf(
            column("id", "TEXT", true, primaryKeyPosition = 1),
            column("assistant_id", "TEXT", true, defaultValue = "'0950e2dc-9bd5-4801-afa3-aa887aa36b4e'"),
            column("title", "TEXT", true),
            column("nodes", "TEXT", true),
            column("create_at", "INTEGER", true),
            column("update_at", "INTEGER", true),
            column("suggestions", "TEXT", true, defaultValue = "'[]'"),
            column("is_pinned", "INTEGER", true, defaultValue = "0"),
            column("custom_system_prompt", "TEXT", true, defaultValue = "''"),
            column("mode_injection_ids", "TEXT", true, defaultValue = "'[]'"),
            column("workspace_id", "TEXT", false),
            column("workspace_cwd", "TEXT", true, defaultValue = "''"),
            column("folder_id", "TEXT", true, defaultValue = "''"),
            column("conversation_number", "INTEGER", true, defaultValue = "0"),
            column("summary_source_assistant_id", "TEXT", true, defaultValue = "''"),
            column("summary_source_conversation_ids", "TEXT", true, defaultValue = "'[]'"),
            column("summary_context", "TEXT", true, defaultValue = "''"),
        ),
    ),
    "conversation_number_sequence" to table(
        "conversation_number_sequence",
        listOf(
            column("id", "INTEGER", true, primaryKeyPosition = 1),
            column("next_number", "INTEGER", true),
        ),
    ),
    "MemoryEntity" to table(
        "MemoryEntity",
        listOf(
            column("id", "INTEGER", true, primaryKeyPosition = 1),
            column("assistant_id", "TEXT", true),
            column("content", "TEXT", true),
            column("category", "TEXT", true, defaultValue = "'GENERAL'"),
            column("title", "TEXT", true, defaultValue = "''"),
            column("updated", "TEXT", true, defaultValue = "'1970-01-01'"),
            column("confidence_flags", "TEXT", true, defaultValue = "'[]'"),
            column("tags", "TEXT", true, defaultValue = "'[]'"),
            column("product", "TEXT", true, defaultValue = "''"),
            column("status", "TEXT", true, defaultValue = "'active'"),
        ),
    ),
    "GenMediaEntity" to table(
        "GenMediaEntity",
        listOf(
            column("id", "INTEGER", true, primaryKeyPosition = 1),
            column("path", "TEXT", true),
            column("model_id", "TEXT", true),
            column("prompt", "TEXT", true),
            column("create_at", "INTEGER", true),
            column("type", "TEXT", true, defaultValue = "'image_generation'"),
            column("source_paths", "TEXT", false),
        ),
    ),
    "message_node" to table(
        "message_node",
        listOf(
            column("id", "TEXT", true, primaryKeyPosition = 1),
            column("conversation_id", "TEXT", true),
            column("node_index", "INTEGER", true),
            column("messages", "TEXT", true),
            column("select_index", "INTEGER", true),
        ),
        foreignKeys = listOf(
            RoomSchemaForeignKeySnapshot(
                table = "ConversationEntity",
                onDelete = "CASCADE",
                onUpdate = "NO ACTION",
                columns = listOf("conversation_id"),
                referencedColumns = listOf("id"),
                match = "NONE",
            )
        ),
    ),
    "managed_files" to table(
        "managed_files",
        listOf(
            column("id", "INTEGER", true, primaryKeyPosition = 1),
            column("folder", "TEXT", true),
            column("relative_path", "TEXT", true),
            column("display_name", "TEXT", true),
            column("mime_type", "TEXT", true),
            column("size_bytes", "INTEGER", true),
            column("created_at", "INTEGER", true),
            column("updated_at", "INTEGER", true),
        ),
    ),
    "favorites" to table(
        "favorites",
        listOf(
            column("id", "TEXT", true, primaryKeyPosition = 1),
            column("type", "TEXT", true),
            column("ref_key", "TEXT", true),
            column("ref_json", "TEXT", true),
            column("snapshot_json", "TEXT", true),
            column("meta_json", "TEXT", false),
            column("created_at", "INTEGER", true),
            column("updated_at", "INTEGER", true),
        ),
    ),
    "workspaces" to table(
        "workspaces",
        listOf(
            column("id", "TEXT", true, primaryKeyPosition = 1),
            column("name", "TEXT", true),
            column("root", "TEXT", true),
            column("shell_status", "TEXT", true),
            column("created_at", "INTEGER", true),
            column("updated_at", "INTEGER", true),
            column("last_access_at", "INTEGER", false),
            column("tool_approvals", "TEXT", true, defaultValue = "'{}'"),
            column("runtime_metadata", "TEXT", true, defaultValue = "'{}'"),
        ),
    ),
    "workspace_audit_events" to table(
        "workspace_audit_events",
        listOf(
            column("id", "INTEGER", true, primaryKeyPosition = 1),
            column("workspace_id", "TEXT", true),
            column("assistant_id", "TEXT", false),
            column("conversation_id", "TEXT", false),
            column("actor", "TEXT", true),
            column("operation", "TEXT", true),
            column("target", "TEXT", true),
            column("cwd", "TEXT", true),
            column("started_at", "INTEGER", true),
            column("finished_at", "INTEGER", true),
            column("result", "TEXT", true),
            column("exit_code", "INTEGER", false),
            column("timed_out", "INTEGER", true),
            column("bytes", "INTEGER", false),
            column("details", "TEXT", true),
        ),
    ),
    "workspace_snapshots" to table(
        "workspace_snapshots",
        listOf(
            column("id", "TEXT", true, primaryKeyPosition = 1),
            column("workspace_id", "TEXT", true),
            column("path", "TEXT", true),
            column("manifest_hash", "TEXT", true),
            column("size", "INTEGER", true),
            column("reason", "TEXT", true),
            column("status", "TEXT", true),
            column("created_at", "INTEGER", true),
        ),
    ),
    "conversation_folder" to table(
        "conversation_folder",
        listOf(
            column("id", "TEXT", true, primaryKeyPosition = 1),
            column("assistant_id", "TEXT", true),
            column("name", "TEXT", true),
            column("sort_index", "INTEGER", true, defaultValue = "0"),
            column("create_at", "INTEGER", true),
        ),
    ),
    "room_master_table" to table(
        "room_master_table",
        listOf(
            column("id", "INTEGER", false, primaryKeyPosition = 1),
            column("identity_hash", "TEXT", false),
        ),
    ),
    // SQLite creates this internal table for the AUTOINCREMENT entities in Room schema 29.
    "sqlite_sequence" to table(
        "sqlite_sequence",
        listOf(
            column("name", "", false),
            column("seq", "", false),
        ),
    ),
)

private val ROOM_SCHEMA_29_BASE_INDICES: List<RoomSchemaIndexSnapshot> = listOf(
    index("ConversationEntity", "index_ConversationEntity_conversation_number", true, listOf("conversation_number"), "c"),
    index("ConversationEntity", "sqlite_autoindex_ConversationEntity_1", true, listOf("id"), "pk"),
    index("message_node", "index_message_node_conversation_id", false, listOf("conversation_id"), "c"),
    index("message_node", "sqlite_autoindex_message_node_1", true, listOf("id"), "pk"),
    index("managed_files", "index_managed_files_relative_path", true, listOf("relative_path"), "c"),
    index("managed_files", "index_managed_files_folder", false, listOf("folder"), "c"),
    index("favorites", "index_favorites_ref_key", true, listOf("ref_key"), "c"),
    index("favorites", "index_favorites_type", false, listOf("type"), "c"),
    index("favorites", "index_favorites_created_at", false, listOf("created_at"), "c"),
    index("favorites", "sqlite_autoindex_favorites_1", true, listOf("id"), "pk"),
    index("workspaces", "index_workspaces_root", true, listOf("root"), "c"),
    index("workspaces", "index_workspaces_updated_at", false, listOf("updated_at"), "c"),
    index("workspaces", "sqlite_autoindex_workspaces_1", true, listOf("id"), "pk"),
    index("workspace_audit_events", "index_workspace_audit_events_workspace_id_started_at", false, listOf("workspace_id", "started_at"), "c"),
    index("workspace_audit_events", "index_workspace_audit_events_conversation_id", false, listOf("conversation_id"), "c"),
    index("workspace_snapshots", "index_workspace_snapshots_workspace_id_created_at", false, listOf("workspace_id", "created_at"), "c"),
    index("workspace_snapshots", "index_workspace_snapshots_path", true, listOf("path"), "c"),
    index("workspace_snapshots", "sqlite_autoindex_workspace_snapshots_1", true, listOf("id"), "pk"),
    index("conversation_folder", "index_conversation_folder_assistant_id", false, listOf("assistant_id"), "c"),
    index("conversation_folder", "sqlite_autoindex_conversation_folder_1", true, listOf("id"), "pk"),
)

private fun roomSchema29ExpectedTables(
    fts: RoomFts5Definition,
): Map<String, RoomSchemaTableSnapshot> = linkedMapOf<String, RoomSchemaTableSnapshot>().apply {
    putAll(ROOM_SCHEMA_29_TABLES)
    put(
        fts.tableName,
        table(
            fts.tableName,
            fts.columns.map { column(it.name, "", false) },
        ),
    )
    put(
        "${fts.tableName}_config",
        table(
            "${fts.tableName}_config",
            listOf(
                column("k", "", true, primaryKeyPosition = 1),
                column("v", "", false),
            ),
        ),
    )
    put(
        "${fts.tableName}_content",
        table(
            "${fts.tableName}_content",
            buildList {
                add(column("id", "INTEGER", false, primaryKeyPosition = 1))
                fts.columns.indices.forEach { add(column("c$it", "", false)) }
            },
        ),
    )
    put(
        "${fts.tableName}_data",
        table(
            "${fts.tableName}_data",
            listOf(
                column("id", "INTEGER", false, primaryKeyPosition = 1),
                column("block", "BLOB", false),
            ),
        ),
    )
    put(
        "${fts.tableName}_docsize",
        table(
            "${fts.tableName}_docsize",
            listOf(
                column("id", "INTEGER", false, primaryKeyPosition = 1),
                column("sz", "BLOB", false),
            ),
        ),
    )
    put(
        "${fts.tableName}_idx",
        table(
            "${fts.tableName}_idx",
            listOf(
                column("segid", "", true, primaryKeyPosition = 1),
                column("term", "", true, primaryKeyPosition = 2),
                column("pgno", "", false),
            ),
        ),
    )
}

private fun roomSchema29ExpectedIndices(
    fts: RoomFts5Definition,
): List<RoomSchemaIndexSnapshot> = ROOM_SCHEMA_29_BASE_INDICES + listOf(
    // FTS5 shadow-table primary keys reported by PRAGMA index_list/index_info.
    index(
        "${fts.tableName}_config",
        "sqlite_autoindex_${fts.tableName}_config_1",
        true,
        listOf("k"),
        "pk",
    ),
    index(
        "${fts.tableName}_idx",
        "sqlite_autoindex_${fts.tableName}_idx_1",
        true,
        listOf("segid", "term"),
        "pk",
    ),
)

private fun roomSchema29ExpectedObjects(
    tables: Map<String, RoomSchemaTableSnapshot>,
    indices: List<RoomSchemaIndexSnapshot>,
): Set<RoomSchemaObjectSnapshot> = buildSet {
    tables.keys.forEach { add(RoomSchemaObjectSnapshot("table", it)) }
    // SQLite persists the ordinary rowid-table primary-key autoindices in sqlite_master. The
    // WITHOUT ROWID FTS5 shadow-table autoindices are exposed by PRAGMA only, so exclude only
    // those two from the sqlite_master object set; every index is still checked above.
    indices
        .filterNot { it.table == "message_fts_config" || it.table == "message_fts_idx" }
        .forEach { add(RoomSchemaObjectSnapshot("index", it.name)) }
}
