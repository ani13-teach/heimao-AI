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
        if (actual.columns.normalizedRoomSchema30Columns() != expected.columns.normalizedRoomSchema30Columns()) return false
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

/**
 * Name the concrete objects that make [roomSchema30IsExact] reject [snapshot].
 *
 * A restored or upgraded device can hold objects that Room itself never inspects: an index an
 * older release forgot to drop, a column a stale migration never removed, an FTS table rebuilt by
 * another SQLite build. Rejecting such a database with only "is not exactly Room schema 30" makes
 * the failure undiagnosable on a device, so the gate reports what actually differs instead.
 */
internal fun describeRoomSchema30Difference(snapshot: RoomSchemaSnapshot): String {
    val differences = mutableListOf<String>()
    if (snapshot.userVersion != ROOM_SCHEMA_30_VERSION) {
        differences += "user_version=${snapshot.userVersion} (expected $ROOM_SCHEMA_30_VERSION)"
    }
    val identityHash = snapshot.identityHash.lowercase(Locale.ROOT)
    if (identityHash != ROOM_SCHEMA_30_IDENTITY_HASH) {
        differences += "room identity_hash=$identityHash (expected $ROOM_SCHEMA_30_IDENTITY_HASH)"
    }

    val specification = ROOM_SCHEMA_30_SPECIFICATION
    val expectedTables = specification.tables.associateBy { it.name }
    val actualTables = snapshot.tables.associateBy { it.name }
    if (actualTables.size != snapshot.tables.size) {
        differences += "duplicate table names=${snapshot.tables.map { it.name }}"
    }
    (expectedTables.keys - actualTables.keys).sorted().bounded().let {
        if (it.isNotEmpty()) differences += "missing tables=$it"
    }
    (actualTables.keys - expectedTables.keys).sorted().bounded().let {
        if (it.isNotEmpty()) differences += "unexpected tables=$it"
    }
    expectedTables.forEach { (name, expected) ->
        val actual = actualTables[name] ?: return@forEach
        if (actual.columns.map { it.name }.toSet().size != actual.columns.size) {
            differences += "$name duplicate column names=${actual.columns.map { it.name }}"
        }
        val expectedColumns = expected.columns.associateBy { it.name }
        val actualColumns = actual.columns.associateBy { it.name }
        (expectedColumns.keys - actualColumns.keys).sorted().bounded().let {
            if (it.isNotEmpty()) differences += "$name missing columns=$it"
        }
        (actualColumns.keys - expectedColumns.keys).sorted().bounded().let {
            if (it.isNotEmpty()) differences += "$name unexpected columns=$it"
        }
        expectedColumns.keys.intersect(actualColumns.keys)
            .filter {
                expectedColumns.getValue(it).normalizedRoomSchema30Column() !=
                    actualColumns.getValue(it).normalizedRoomSchema30Column()
            }
            .sorted()
            .map { "$it expected=${expectedColumns.getValue(it)} actual=${actualColumns.getValue(it)}" }
            .bounded()
            .let { if (it.isNotEmpty()) differences += "$name changed columns=$it" }
        val expectedForeignKeys = expected.foreignKeys.sortedWith(ROOM_SCHEMA_30_FOREIGN_KEY_ORDER)
        val actualForeignKeys = actual.foreignKeys.sortedWith(ROOM_SCHEMA_30_FOREIGN_KEY_ORDER)
        if (expectedForeignKeys != actualForeignKeys) {
            differences += "$name foreign keys expected=$expectedForeignKeys actual=$actualForeignKeys"
        }
    }

    val messageFtsSql = snapshot.sqliteMasterSql[ROOM_SCHEMA_30_FTS_TABLE]
    if (!roomSchema30MessageFtsSqlIsExact(messageFtsSql)) {
        differences += "message_fts SQL=${messageFtsSql?.replace(Regex("\\s+"), " ") ?: "missing"}"
    }
    ROOM_SCHEMA_30_FTS_SHADOW_SQL.forEach { (name, expectedSql) ->
        val actualSql = snapshot.sqliteMasterSql[name]
        if (normalizeRoomSchema30Sql(actualSql) != normalizeRoomSchema30Sql(expectedSql)) {
            differences += "$name SQL=${actualSql?.replace(Regex("\\s+"), " ") ?: "missing"}"
        }
    }

    val usedExpectedIndices = BooleanArray(specification.indices.size)
    if (snapshot.indices.map { it.table to it.name }.toSet().size != snapshot.indices.size) {
        differences += "duplicate indices=${snapshot.indices.map { "${it.table}.${it.name}" }}"
    }
    val unexpectedIndices = mutableListOf<String>()
    snapshot.indices.forEach { actual ->
        if (actual.partial) unexpectedIndices += "${actual.name}(partial)"
        val match = specification.indices.indices.firstOrNull { index ->
            !usedExpectedIndices[index] && roomSchema30IndexMatches(actual, specification.indices[index])
        }
        if (match == null) {
            unexpectedIndices += "${actual.name} table=${actual.table} columns=${actual.columns}"
        } else {
            usedExpectedIndices[match] = true
        }
    }
    unexpectedIndices.bounded().let { if (it.isNotEmpty()) differences += "unexpected indices=$it" }
    specification.indices.filterIndexed { index, _ -> !usedExpectedIndices[index] }
        .map { "${it.name} table=${it.table} columns=${it.columns}" }
        .bounded()
        .let { if (it.isNotEmpty()) differences += "missing indices=$it" }

    if (snapshot.objects.map { it.type to it.name }.toSet().size != snapshot.objects.size) {
        differences += "duplicate schema objects=${snapshot.objects.map { "${it.type} ${it.name}" }}"
    }
    val objectNames = snapshot.objects.map { it.name }.toSet()
    (snapshot.sqliteMasterSql.keys - objectNames).sorted().bounded().let {
        if (it.isNotEmpty()) differences += "objects without a sqlite_master row=$it"
    }
    snapshot.objects
        .filter { !it.type.equals("table", ignoreCase = true) && !it.type.equals("index", ignoreCase = true) }
        .map { "${it.type} ${it.name}" }
        .bounded()
        .let { if (it.isNotEmpty()) differences += "schema objects Room never creates=$it" }
    val indexObjects = snapshot.objects.filter { it.type.equals("index", ignoreCase = true) }
    val knownIndices = snapshot.indices.map { it.name }.toSet()
    indexObjects.map { it.name }.filterNot { it in knownIndices }.bounded().let {
        if (it.isNotEmpty()) differences += "index objects without an index_list entry=$it"
    }
    val ftsShadowTables = setOf("message_fts_config", "message_fts_idx")
    snapshot.indices.filterNot { it.table in ftsShadowTables }.map { it.name }.filterNot { it in objectNames }
        .bounded().let {
            if (it.isNotEmpty()) differences += "indices missing from sqlite_master=$it"
        }
    snapshot.objects.filter { it.type.equals("index", ignoreCase = true) }.map { it.name }
        .filter { name ->
            val index = snapshot.indices.singleOrNull { it.name == name }
            index != null && index.table in ftsShadowTables && !name.startsWith("sqlite_autoindex_")
        }
        .bounded()
        .let { if (it.isNotEmpty()) differences += "non-generated FTS shadow indices=$it" }

    if (differences.isEmpty()) return "no single difference identified; the schema object set differs"
    return differences.joinToString("; ")
}

/** Keep a diagnostic message readable when a database holds many drifting objects. */
private fun List<*>.bounded(limit: Int = 6): List<String> =
    if (size <= limit) {
        map { it.toString() }
    } else {
        take(limit).map { it.toString() } + "(+${size - limit} more)"
    }

/** Fixture used by JVM tests; its shadow SQL mirrors sqlite_master on the Android SQLite build. */
internal fun roomSchema30ExpectedSnapshotForTest(): RoomSchemaSnapshot {
    val specification = roomSchema29ExpectedSnapshotForTest()
    return specification.copy(
        sqliteMasterSql = specification.sqliteMasterSql + ROOM_SCHEMA_30_FTS_SHADOW_SQL,
    )
}

internal const val ROOM_SCHEMA_30_VERSION = 30
internal const val ROOM_SCHEMA_30_IDENTITY_HASH = "3979b81ecdae08bd3d51457e69ba2b2b"
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

/**
 * Compare a column the way Room does at runtime.
 *
 * Room's exported schema records no default for a nullable column, but SQLite keeps the wording of
 * whichever clause created it: Room's own generated migration adds such a column with
 * `ALTER TABLE ... ADD COLUMN source_paths TEXT DEFAULT NULL`, so a database that lived through
 * that upgrade keeps the word NULL while a freshly created one reports no default. The two behave
 * identically and Room's runtime validation accepts both, so the gate must not reject one of them.
 */
private fun RoomSchemaColumnSnapshot.normalizedRoomSchema30Column(): RoomSchemaColumnSnapshot =
    copy(defaultValue = defaultValue?.takeIf { !it.equals("NULL", ignoreCase = true) })

private fun List<RoomSchemaColumnSnapshot>.normalizedRoomSchema30Columns(): List<RoomSchemaColumnSnapshot> =
    map { it.normalizedRoomSchema30Column() }.sortedBy { it.name }

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
