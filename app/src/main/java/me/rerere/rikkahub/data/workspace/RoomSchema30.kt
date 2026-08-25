package me.rerere.rikkahub.data.workspace

import java.util.Locale

/**
 * Acceptance rules for the database after Room has finished upgrading it to schema 30.
 *
 * Room's exported JSON describes entities and declared indexes, but it is not a dump of the
 * database that SQLite exposes at runtime. In particular, Room writes [room_master_table],
 * SQLite writes [sqlite_sequence] and primary-key auto-indexes, and FTS5 creates the virtual
 * table's shadow tables. The restore gate therefore validates the Room contract and the observed
 * SQLite-owned objects separately instead of comparing one synthetic sqlite_master set byte for
 * byte.
 */
internal fun roomSchema30MessageFtsSqlIsExact(sql: String?): Boolean =
    roomSchema29MessageFtsSqlIsExact(sql)

internal fun roomSchema30IsExact(snapshot: RoomSchemaSnapshot): Boolean {
    if (snapshot.userVersion != ROOM_SCHEMA_30_VERSION) return false
    if (snapshot.identityHash.lowercase(Locale.ROOT) != ROOM_SCHEMA_30_IDENTITY_HASH) return false

    val specification = ROOM_SCHEMA_30_SPECIFICATION
    val expectedTables = specification.tables.associateBy { it.name }
    val actualTables = snapshot.tables.associateBy { it.name }
    if (actualTables.size != snapshot.tables.size || actualTables.keys != expectedTables.keys) return false

    expectedTables.forEach { (name, expected) ->
        val actual = actualTables[name] ?: return false
        if (actual.columns.map { it.name }.toSet().size != actual.columns.size) return false
        if (actual.columns.sortedBy { it.name } != expected.columns.sortedBy { it.name }) return false
        if (
            actual.foreignKeys.sortedWith(ROOM_SCHEMA_30_FOREIGN_KEY_ORDER) !=
            expected.foreignKeys.sortedWith(ROOM_SCHEMA_30_FOREIGN_KEY_ORDER)
        ) {
            return false
        }
    }

    val messageFtsSql = snapshot.sqliteMasterSql[ROOM_SCHEMA_30_FTS_TABLE]
    if (!roomSchema30MessageFtsSqlIsExact(messageFtsSql)) return false
    if (!roomSchema30FtsShadowSqlIsExact(snapshot.sqliteMasterSql)) return false
    if (!roomSchema30IndicesAreExact(snapshot.indices, specification.indices)) return false
    return roomSchema30ObjectsAreSafe(snapshot, expectedTables.keys)
}

/** Fixture used by JVM tests; its shadow SQL mirrors sqlite_master on the Android SQLite build. */
internal fun roomSchema30ExpectedSnapshotForTest(): RoomSchemaSnapshot {
    val specification = roomSchema29ExpectedSnapshotForTest()
    return specification.copy(
        sqliteMasterSql = specification.sqliteMasterSql + ROOM_SCHEMA_30_FTS_SHADOW_SQL,
    )
}

private const val ROOM_SCHEMA_30_VERSION = 30
private const val ROOM_SCHEMA_30_IDENTITY_HASH = "3979b81ecdae08bd3d51457e69ba2b2b"
private const val ROOM_SCHEMA_30_FTS_TABLE = "message_fts"

private val ROOM_SCHEMA_30_SPECIFICATION: RoomSchemaSnapshot by lazy {
    roomSchema29ExpectedSnapshotForTest()
}

private val ROOM_SCHEMA_30_FOREIGN_KEY_ORDER = compareBy<RoomSchemaForeignKeySnapshot>(
    { it.table },
    { it.columns.joinToString("\u0000") },
    { it.referencedColumns.joinToString("\u0000") },
)

private val ROOM_SCHEMA_30_FTS_SHADOW_SQL = mapOf(
    "message_fts_config" to "CREATE TABLE 'message_fts_config'(k PRIMARY KEY, v) WITHOUT ROWID",
    "message_fts_content" to "CREATE TABLE 'message_fts_content'(id INTEGER PRIMARY KEY, c0, c1, c2, c3, c4, c5)",
    "message_fts_data" to "CREATE TABLE 'message_fts_data'(id INTEGER PRIMARY KEY, block BLOB)",
    "message_fts_docsize" to "CREATE TABLE 'message_fts_docsize'(id INTEGER PRIMARY KEY, sz BLOB)",
    "message_fts_idx" to "CREATE TABLE 'message_fts_idx'(segid, term, pgno, PRIMARY KEY(segid, term)) WITHOUT ROWID",
)

private fun roomSchema30FtsShadowSqlIsExact(sqlByName: Map<String, String?>): Boolean =
    ROOM_SCHEMA_30_FTS_SHADOW_SQL.all { (name, expectedSql) ->
        normalizeRoomSchema30Sql(sqlByName[name]) == normalizeRoomSchema30Sql(expectedSql)
    }

private fun normalizeRoomSchema30Sql(sql: String?): String? =
    sql
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[\\s`\"'\\[\\]]"), "")
        ?.removeSuffix(";")

private fun roomSchema30IndicesAreExact(
    actual: List<RoomSchemaIndexSnapshot>,
    expected: List<RoomSchemaIndexSnapshot>,
): Boolean {
    if (actual.any { it.partial }) return false
    if (actual.map { it.table to it.name }.toSet().size != actual.size) return false

    val usedExpected = BooleanArray(expected.size)
    actual.forEach { actualIndex ->
        val match = expected.indices.firstOrNull { expectedIndex ->
            !usedExpected[expectedIndex] &&
                roomSchema30IndexMatches(actualIndex, expected[expectedIndex])
        } ?: return false
        usedExpected[match] = true
    }
    return usedExpected.all { it }
}

private fun roomSchema30IndexMatches(
    actual: RoomSchemaIndexSnapshot,
    expected: RoomSchemaIndexSnapshot,
): Boolean {
    if (actual.table != expected.table ||
        actual.unique != expected.unique ||
        actual.columns != expected.columns ||
        actual.partial != expected.partial
    ) {
        return false
    }
    // Room's declared index names are part of the exported schema. SQLite primary-key names are
    // generated, however, and FTS5's WITHOUT ROWID shadow indexes are SQLite-owned too.
    val nameMatches = if (expected.name.startsWith("sqlite_autoindex_")) {
        actual.name.matches(
            Regex("sqlite_autoindex_${Regex.escape(expected.table)}_[0-9]+"),
        )
    } else {
        actual.name == expected.name
    }
    if (!nameMatches) return false
    return actual.origin.isEmpty() || actual.origin == expected.origin
}

private fun roomSchema30ObjectsAreSafe(
    snapshot: RoomSchemaSnapshot,
    expectedTableNames: Set<String>,
): Boolean {
    if (snapshot.objects.map { it.type to it.name }.toSet().size != snapshot.objects.size) return false
    if ((snapshot.sqliteMasterSql.keys - snapshot.objects.map { it.name }.toSet()).isNotEmpty()) return false

    val tableObjects = snapshot.objects.filter { it.type.equals("table", ignoreCase = true) }
    if (tableObjects.map { it.name }.toSet() != expectedTableNames) return false

    // Any trigger/view, or a table with an unrecognised name, is outside Room's schema contract.
    if (snapshot.objects.any { !it.type.equals("table", ignoreCase = true) && !it.type.equals("index", ignoreCase = true) }) {
        return false
    }

    val indicesByName = snapshot.indices.groupBy { it.name }
    val indexObjects = snapshot.objects.filter { it.type.equals("index", ignoreCase = true) }
    if (indexObjects.any { it.name !in indicesByName }) return false

    val ftsShadowIndexTables = setOf("message_fts_config", "message_fts_idx")
    // Ordinary Room indexes and rowid primary-key auto-indexes are persisted in sqlite_master.
    // FTS5's WITHOUT ROWID primary-key indexes are reported by PRAGMA index_list/index_info but
    // are not necessarily sqlite_master objects, so those two are optional there.
    snapshot.indices
        .filterNot { it.table in ftsShadowIndexTables }
        .forEach { index ->
            val objectInfo = snapshot.objects.singleOrNull { it.name == index.name }
                ?: return false
            if (!objectInfo.type.equals("index", ignoreCase = true)) return false
        }

    indexObjects.forEach { objectInfo ->
        val index = indicesByName[objectInfo.name]?.singleOrNull() ?: return false
        if (index.table in ftsShadowIndexTables && !objectInfo.name.startsWith("sqlite_autoindex_")) {
            return false
        }
    }
    return true
}
