package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.WorkspaceRuntimeMetadata
import java.util.Locale

/** Versioned portable workspace archive metadata. Keep this format independent of transports. */
@Serializable
data class WorkspaceArchiveManifest(
    val format: String = FORMAT,
    val formatVersion: Int = CURRENT_VERSION,
    val appVersion: String = "",
    val databaseVersion: Int = AppDatabase.CURRENT_VERSION,
    val createdAt: Long = 0L,
    val createdAbi: List<String> = emptyList(),
    val settingsPath: String = SETTINGS_PATH,
    /** Names are retained for transport compatibility; [databasePayloads] supplies coverage. */
    val databaseEntries: List<String> = emptyList(),
    val databasePayloads: List<WorkspaceArchivePayload> = emptyList(),
    val legacyEntries: List<String> = emptyList(),
    val excluded: List<String> = DEFAULT_EXCLUDED,
    /** Full database payloads deliberately omit local snapshot rows because their files are not portable. */
    val snapshotsExcluded: Boolean = false,
    val workspaces: List<WorkspaceArchiveWorkspace> = emptyList(),
    val references: WorkspaceArchiveReferences = WorkspaceArchiveReferences(),
) {
    fun validate() {
        require(format == FORMAT) { "Unsupported workspace archive format: $format" }
        require(formatVersion == CURRENT_VERSION) {
            "Unsupported workspace archive version: $formatVersion"
        }
        require(createdAt > 0L) { "Workspace archive creation time is invalid" }
        require(databaseVersion in setOf(31, 32)) {
            "Unsupported workspace archive database version $databaseVersion"
        }
        require(settingsPath == SETTINGS_PATH) { "Unsupported settings path: $settingsPath" }
        require(DEFAULT_EXCLUDED.all { it in excluded }) {
            "Portable manifest does not declare required exclusions"
        }
        require(workspaces.size <= MAX_WORKSPACES) { "Too many workspaces in archive" }

        val ids = mutableSetOf<String>()
        val roots = mutableSetOf<String>()
        workspaces.forEach { workspace ->
            require(workspace.id.isValidArchiveComponent()) { "Invalid workspace id" }
            require(workspace.root.isValidArchiveComponent()) { "Invalid workspace root" }
            require(ids.add(workspace.id)) { "Duplicate workspace id: ${workspace.id}" }
            require(roots.add(workspace.root)) { "Duplicate workspace root: ${workspace.root}" }
            require(workspace.name.length <= MAX_NAME_LENGTH) { "Workspace name is too long" }
            workspace.files.validate(expectedPath = "workspaces/${workspace.root}/files.tar")
            workspace.linux?.validate(expectedPath = "workspaces/${workspace.root}/linux.tar")
        }

        databaseEntries.forEach { entry ->
            require(entry in DATABASE_ENTRY_NAMES) { "Unsupported database entry: $entry" }
        }
        require(databaseEntries.distinct().size == databaseEntries.size) {
            "Duplicate database entry"
        }
        require(databaseEntries.isEmpty() || "database/rikka_hub.db" in databaseEntries) {
            "Database payload is missing the main database file"
        }
        require(snapshotsExcluded == databaseEntries.isNotEmpty()) {
            "Portable database snapshots must explicitly declare snapshotsExcluded"
        }
        require(databaseEntries.toSet() == databasePayloads.map { it.path }.toSet()) {
            "Database entries are not completely covered by manifest length/SHA descriptors"
        }
        require(databasePayloads.distinctBy { it.path }.size == databasePayloads.size) {
            "Duplicate database payload descriptor"
        }
        databasePayloads.forEach { payload ->
            payload.validate(expectedPath = "database/rikka_hub.db")
        }
        legacyEntries.forEach { entry ->
            require(entry.isSafePortablePath()) { "Unsafe legacy entry: $entry" }
        }
        require(legacyEntries.distinct().size == legacyEntries.size) {
            "Duplicate legacy entry"
        }

        val manifestWorkspaceIds = workspaces.map { it.id }.toSet()
        require(references.assistants.size <= MAX_REFERENCES) {
            "Too many assistant workspace references"
        }
        require(references.conversations.size <= MAX_REFERENCES) {
            "Too many conversation workspace references"
        }
        val assistantIds = mutableSetOf<String>()
        references.assistants.forEach { reference ->
            require(reference.assistantId.isValidArchiveComponent()) {
                "Invalid assistant reference id: ${reference.assistantId}"
            }
            require(assistantIds.add(reference.assistantId)) {
                "Duplicate assistant workspace reference: ${reference.assistantId}"
            }
            reference.workspaceId?.let { workspaceId ->
                require(workspaceId.isValidArchiveComponent() && workspaceId in manifestWorkspaceIds) {
                    "Assistant reference points outside manifest workspaces: $workspaceId"
                }
            }
        }
        val conversationIds = mutableSetOf<String>()
        references.conversations.forEach { reference ->
            require(reference.conversationId.isValidArchiveComponent()) {
                "Invalid conversation reference id: ${reference.conversationId}"
            }
            require(conversationIds.add(reference.conversationId)) {
                "Duplicate conversation workspace reference: ${reference.conversationId}"
            }
            reference.workspaceId?.let { workspaceId ->
                require(workspaceId.isValidArchiveComponent() && workspaceId in manifestWorkspaceIds) {
                    "Conversation reference points outside manifest workspaces: $workspaceId"
                }
            }
        }
    }

    companion object {
        const val FORMAT = "heimao.workspace.archive"
        const val CURRENT_VERSION = 1
        const val SETTINGS_PATH = "settings.json"
        const val MAX_WORKSPACES = 1_000
        const val MAX_REFERENCES = 100_000
        const val MAX_NAME_LENGTH = 512
        /** Portable archives contain only a checkpointed main database snapshot. */
        val DATABASE_ENTRY_NAMES = setOf("database/rikka_hub.db")
        val DEFAULT_EXCLUDED = listOf(
            "workspaces/*/tmp",
            "workspaces/*/linux/tmp",
            "workspaces/*/linux/var/tmp",
            "filesDir/global credentials",
            "settings provider keys, backup passwords and OAuth tokens",
        )
    }
}

@Serializable
data class WorkspaceArchiveWorkspace(
    val id: String,
    val name: String,
    val root: String,
    val shellStatus: String = "DISABLED",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastAccessAt: Long? = null,
    val runtimeMetadata: WorkspaceRuntimeMetadata = WorkspaceRuntimeMetadata(),
    val files: WorkspaceArchivePayload,
    val linux: WorkspaceArchivePayload? = null,
    /** Legacy workspace metadata retained for exact single-workspace restoration. */
    val toolApprovals: String = "{}",
)

/** A local checkpoint containing exactly one workspace and no Room database or Settings data. */
@Serializable
data class WorkspaceSnapshotManifest(
    val format: String = FORMAT,
    val formatVersion: Int = CURRENT_VERSION,
    val createdAt: Long = 0L,
    val createdAbi: List<String> = emptyList(),
    val workspace: WorkspaceArchiveWorkspace,
) {
    fun validate() {
        require(format == FORMAT) { "Unsupported workspace snapshot format: $format" }
        require(formatVersion == CURRENT_VERSION) {
            "Unsupported workspace snapshot version: $formatVersion"
        }
        require(createdAt > 0L) { "Workspace snapshot creation time is invalid" }
        require(workspace.id.isValidArchiveComponent()) { "Invalid snapshot workspace id" }
        require(workspace.root.isValidArchiveComponent()) { "Invalid snapshot workspace root" }
        require(workspace.files.path == "workspaces/${workspace.root}/files.tar") {
            "Snapshot files payload path does not match workspace root"
        }
        workspace.files.validate(expectedPath = workspace.files.path)
        val linux = workspace.linux ?: error("Workspace snapshot Linux payload is missing")
        require(linux.path == "workspaces/${workspace.root}/linux.tar") {
            "Snapshot Linux payload path does not match workspace root"
        }
        linux.validate(expectedPath = linux.path)
        require(workspace.name.length <= WorkspaceArchiveManifest.MAX_NAME_LENGTH) {
            "Snapshot workspace name is too long"
        }
        require(toolApprovalsIsSafe(workspace.toolApprovals)) {
            "Snapshot workspace tool approvals are invalid"
        }
    }

    companion object {
        const val FORMAT = "heimao.workspace.snapshot"
        const val CURRENT_VERSION = 1
        const val MANIFEST_PATH = "manifest.json"
    }
}

private fun toolApprovalsIsSafe(value: String): Boolean =
    value.length <= 64 * 1024 && value.trimStart().startsWith("{") && value.trimEnd().endsWith("}")

@Serializable
data class WorkspaceArchivePayload(
    val path: String,
    val length: Long,
    val sha256: String,
) {
    fun validate(expectedPath: String) {
        require(path == expectedPath) { "Unexpected workspace payload path: $path" }
        require(length >= 0L) { "Negative workspace payload length" }
        require(sha256.matches(SHA256_REGEX)) { "Invalid workspace payload SHA-256" }
    }
}

@Serializable
data class WorkspaceArchiveReferences(
    /** Authoritative workspace ownership for archives created by current versions. */
    val assistants: List<WorkspaceAssistantReference> = emptyList(),
    /**
     * Conversation IDs are retained for structural compatibility. A non-null workspace ID is
     * accepted only as legacy restore input and is reconciled after a full database replacement.
     */
    val conversations: List<WorkspaceConversationReference> = emptyList(),
)

@Serializable
data class WorkspaceAssistantReference(
    val assistantId: String,
    val workspaceId: String? = null,
)

@Serializable
data class WorkspaceConversationReference(
    val conversationId: String,
    val workspaceId: String? = null,
)

private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
private val ARCHIVE_COMPONENT_REGEX = Regex("[A-Za-z0-9._-]{1,255}")

internal fun String.isValidArchiveComponent(): Boolean = ARCHIVE_COMPONENT_REGEX.matches(this)

internal fun String.isSafePortablePath(): Boolean = runCatching {
    WorkspaceArchivePathSafety.normalize(this)
    true
}.getOrDefault(false)

internal fun String.normalizedSha256(): String = lowercase(Locale.US)
