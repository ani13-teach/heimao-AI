package me.rerere.rikkahub.data.workspace

/**
 * Acceptance rules for the database after Room has finished upgrading it to schema 31.
 *
 * Schema 31 only adds the persisted workspace apt plans (`workspace_apt_plans`) with the two
 * declared indexes and the primary-key auto-index SQLite creates for its TEXT key. Everything else
 * is inherited from schema 30 without relaxation: the FTS5 declaration, the shadow-table SQL, the
 * object set, and the strict table/column/index/foreign-key comparison all stay as they were.
 *
 * The expected object set is derived from the Room 30 fixture plus the schema 31 addition, and the
 * addition was verified against app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json.
 */
internal val ROOM_SCHEMA_31_EXPECTATION: RoomSchemaExpectation by lazy {
    RoomSchemaExpectation(
        version = ROOM_SCHEMA_31_VERSION,
        identityHash = ROOM_SCHEMA_31_IDENTITY_HASH,
        specification = ROOM_SCHEMA_31_SPECIFICATION,
    )
}

internal fun roomSchema31IsExact(snapshot: RoomSchemaSnapshot): Boolean =
    roomSchemaIsExact(snapshot, ROOM_SCHEMA_31_EXPECTATION)

/** Name the concrete objects that make [roomSchema31IsExact] reject [snapshot]. */
internal fun describeRoomSchema31Difference(snapshot: RoomSchemaSnapshot): String =
    describeRoomSchemaDifference(snapshot, ROOM_SCHEMA_31_EXPECTATION)

/** The FTS declaration is untouched by schema 31; the accepted SQL is the schema 29 one. */
internal fun roomSchema31MessageFtsSqlIsExact(sql: String?): Boolean =
    roomSchema29MessageFtsSqlIsExact(sql)

/** JVM-test fixture derived from the same schema 31 specification used by the restore gate. */
internal fun roomSchema31ExpectedSnapshotForTest(): RoomSchemaSnapshot = ROOM_SCHEMA_31_SPECIFICATION

internal const val ROOM_SCHEMA_31_VERSION = 31
internal const val ROOM_SCHEMA_31_IDENTITY_HASH = "0ab31169cf3757936d83ce0d8f79728c"

private const val ROOM_SCHEMA_31_PLAN_TABLE = "workspace_apt_plans"

private val ROOM_SCHEMA_31_PLAN_INDICES: List<RoomSchemaIndexSnapshot> = listOf(
    roomSchema31Index(
        table = ROOM_SCHEMA_31_PLAN_TABLE,
        name = "index_workspace_apt_plans_workspace_id_created_at",
        unique = false,
        columns = listOf("workspace_id", "created_at"),
        origin = "c",
    ),
    roomSchema31Index(
        table = ROOM_SCHEMA_31_PLAN_TABLE,
        name = "index_workspace_apt_plans_conversation_id",
        unique = false,
        columns = listOf("conversation_id"),
        origin = "c",
    ),
    // SQLite creates this unique index for the non-autoincrement TEXT primary key.
    roomSchema31Index(
        table = ROOM_SCHEMA_31_PLAN_TABLE,
        name = "sqlite_autoindex_workspace_apt_plans_1",
        unique = true,
        columns = listOf("plan_id"),
        origin = "pk",
    ),
)

private val ROOM_SCHEMA_31_PLAN_TABLE_SNAPSHOT: RoomSchemaTableSnapshot = RoomSchemaTableSnapshot(
    name = ROOM_SCHEMA_31_PLAN_TABLE,
    columns = listOf(
        roomSchema31Column("plan_id", "TEXT", notNull = true, primaryKeyPosition = 1),
        roomSchema31Column("workspace_id", "TEXT", notNull = true),
        roomSchema31Column("assistant_id", "TEXT", notNull = true),
        roomSchema31Column("conversation_id", "TEXT", notNull = true),
        roomSchema31Column("action", "TEXT", notNull = true),
        roomSchema31Column("packages_json", "TEXT", notNull = true),
        roomSchema31Column("base_fingerprint", "TEXT", notNull = true),
        roomSchema31Column("plan_fingerprint", "TEXT", notNull = true),
        roomSchema31Column("summary_json", "TEXT", notNull = true),
        roomSchema31Column("created_at", "INTEGER", notNull = true),
        roomSchema31Column("expires_at", "INTEGER", notNull = true),
        roomSchema31Column("confirmed_tool_call_id", "TEXT", notNull = false),
        roomSchema31Column("state", "TEXT", notNull = true),
        roomSchema31Column("operation_id", "TEXT", notNull = false),
    ),
    foreignKeys = emptyList(),
)

private val ROOM_SCHEMA_31_SPECIFICATION: RoomSchemaSnapshot by lazy {
    val schema30 = roomSchema30ExpectedSnapshotForTest()
    schema30.copy(
        userVersion = ROOM_SCHEMA_31_VERSION,
        identityHash = ROOM_SCHEMA_31_IDENTITY_HASH,
        tables = schema30.tables + ROOM_SCHEMA_31_PLAN_TABLE_SNAPSHOT,
        indices = schema30.indices + ROOM_SCHEMA_31_PLAN_INDICES,
        objects = schema30.objects +
            RoomSchemaObjectSnapshot("table", ROOM_SCHEMA_31_PLAN_TABLE) +
            ROOM_SCHEMA_31_PLAN_INDICES.map { RoomSchemaObjectSnapshot("index", it.name) },
    )
}

private fun roomSchema31Column(
    name: String,
    type: String,
    notNull: Boolean,
    primaryKeyPosition: Int = 0,
) = RoomSchemaColumnSnapshot(name, type, notNull, primaryKeyPosition, defaultValue = null)

private fun roomSchema31Index(
    table: String,
    name: String,
    unique: Boolean,
    columns: List<String>,
    origin: String,
) = RoomSchemaIndexSnapshot(table, name, unique, columns, origin, partial = false)
