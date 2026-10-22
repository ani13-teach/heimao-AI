package me.rerere.rikkahub.data.workspace

internal const val ROOM_SCHEMA_32_VERSION = 32
internal const val ROOM_SCHEMA_32_IDENTITY_HASH = "5e2893e1ed6c76ac70045892bf33a38f"

internal val ROOM_SCHEMA_32_EXPECTATION: RoomSchemaExpectation by lazy {
    RoomSchemaExpectation(ROOM_SCHEMA_32_VERSION, ROOM_SCHEMA_32_IDENTITY_HASH, roomSchema32ExpectedSnapshotForTest())
}

internal fun roomSchema32IsExact(snapshot: RoomSchemaSnapshot): Boolean =
    roomSchemaIsExact(snapshot, ROOM_SCHEMA_32_EXPECTATION)

internal fun describeRoomSchema32Difference(snapshot: RoomSchemaSnapshot): String =
    describeRoomSchemaDifference(snapshot, ROOM_SCHEMA_32_EXPECTATION)

internal fun roomSchema32ExpectedSnapshotForTest(): RoomSchemaSnapshot {
    val previous = roomSchema31ExpectedSnapshotForTest()
    val indices = listOf(
        RoomSchemaIndexSnapshot("context_block", "index_context_block_conversation_id", false, listOf("conversation_id"), "c", false),
        RoomSchemaIndexSnapshot("context_block", "sqlite_autoindex_context_block_1", true, listOf("id"), "pk", false),
    )
    val columns = listOf("id", "conversation_id", "assistant_id", "sources", "source_hash", "prefix_hash", "summary", "created_at", "format_version").map { name ->
        RoomSchemaColumnSnapshot(name, if (name in listOf("created_at", "format_version")) "INTEGER" else "TEXT", true, if (name == "id") 1 else 0, null)
    }
    return previous.copy(
        userVersion = ROOM_SCHEMA_32_VERSION,
        identityHash = ROOM_SCHEMA_32_IDENTITY_HASH,
        tables = previous.tables + RoomSchemaTableSnapshot("context_block", columns, listOf(
            RoomSchemaForeignKeySnapshot("ConversationEntity", "CASCADE", "NO ACTION", listOf("conversation_id"), listOf("id")),
        )),
        indices = previous.indices + indices,
        objects = previous.objects + RoomSchemaObjectSnapshot("table", "context_block") + indices.map { RoomSchemaObjectSnapshot("index", it.name) },
    )
}
