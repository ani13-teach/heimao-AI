package me.rerere.rikkahub.data.workspace

/**
 * Render a rejected database's structure as plain text.
 *
 * A shipped release build is not debuggable, so a deviating device database cannot be inspected
 * over adb: the file lives in the app's private storage and `run-as` is refused. This report is
 * therefore written next to the app's external files, where the developer can read it without
 * root. It contains DDL only — table, column, index and sqlite_master definitions — never row
 * data, so it is safe to keep outside the app's private directory.
 *
 * [expectation] names the contract the database was compared against; the restore gate passes its
 * own version so a rejected database reports the version it was expected to match. It defaults to
 * the schema 30 contract to keep the report callable without a gate in hand.
 */
internal fun roomSchemaDriftReport(
    snapshot: RoomSchemaSnapshot,
    difference: String,
    appVersion: String,
    appVersionCode: String,
    maxChars: Int = 256 * 1024,
    expectation: RoomSchemaExpectation = ROOM_SCHEMA_30_EXPECTATION,
): String {
    val report = buildString {
        appendLine("app=$appVersion ($appVersionCode)")
        appendLine("user_version=${snapshot.userVersion} expected=${expectation.version}")
        appendLine("identity_hash=${snapshot.identityHash} expected=${expectation.identityHash}")
        appendLine("difference=$difference")
        appendLine()
        appendLine("== tables (PRAGMA table_info) ==")
        snapshot.tables.sortedBy { it.name }.forEach { table ->
            appendLine("table ${table.name}")
            table.columns.forEach { column ->
                appendLine(
                    "  ${column.name}|${column.type}|notNull=${column.notNull}|" +
                        "pk=${column.primaryKeyPosition}|default=${column.defaultValue}",
                )
            }
            table.foreignKeys.forEach { key ->
                appendLine(
                    "  fk -> ${key.table}(${key.referencedColumns}) on ${key.columns} " +
                        "update=${key.onUpdate} delete=${key.onDelete} match=${key.match}",
                )
            }
        }
        appendLine()
        appendLine("== indices (PRAGMA index_list/index_info) ==")
        snapshot.indices.sortedWith(compareBy({ it.table }, { it.name })).forEach { index ->
            appendLine(
                "${index.table} ${index.name} unique=${index.unique} partial=${index.partial} " +
                    "origin=${index.origin} columns=${index.columns}",
            )
        }
        appendLine()
        appendLine("== sqlite_master ==")
        snapshot.sqliteMasterSql.toSortedMap().forEach { (name, sql) ->
            appendLine("[$name] ${sql ?: "NULL"}")
        }
    }
    return if (report.length <= maxChars) report else report.take(maxChars) + "\n<truncated>\n"
}
