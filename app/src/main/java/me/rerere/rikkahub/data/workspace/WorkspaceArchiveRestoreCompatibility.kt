package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.WorkspaceRuntimeMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** A restore-only projection; ignored payloads must still pass the archive's byte/digest checks. */
internal data class WorkspaceArchiveRestoreManifest(
    val portable: WorkspaceArchiveManifest,
    val ignoredLinuxPayloads: Map<String, WorkspaceArchivePayload> = emptyMap(),
) {
    val isLegacyLinux: Boolean get() = ignoredLinuxPayloads.isNotEmpty()
}

internal fun decodeWorkspaceArchiveForRestore(text: String, json: Json): WorkspaceArchiveRestoreManifest {
    val header = json.parseToJsonElement(text).jsonObject
    val workspaces = header["workspaces"] as? JsonArray
    val hasLinux = "sharedLinuxPayloads" in header || workspaces.orEmpty().any { "linux" in it.jsonObject }
    if (!hasLinux) {
        requireWorkspaceManifestVersion(text, json, WorkspaceArchiveManifest.FORMAT)
        return WorkspaceArchiveRestoreManifest(json.decodeFromString<WorkspaceArchiveManifest>(text).also { it.validate() })
    }
    require(header["format"]?.jsonPrimitive?.content == WorkspaceArchiveManifest.FORMAT &&
        header["formatVersion"]?.jsonPrimitive?.content == "2") { "Only v2 Linux archives can be restored" }
    require(workspaces != null && workspaces.isNotEmpty() && workspaces.size <= WorkspaceArchiveManifest.MAX_WORKSPACES) {
        "Legacy Linux archive workspaces are missing or invalid"
    }
    val ignored = linkedMapOf<String, WorkspaceArchivePayload>()
    val projected = workspaces.map { element ->
        val workspace = element.jsonObject
        require(workspace["linux"] is JsonObject && workspace["home"] is JsonObject) {
            "Legacy Linux archive requires complete Linux and HOME descriptors"
        }
        val root = workspace["root"]?.jsonPrimitive?.content ?: error("Workspace root is missing")
        require(root.isValidArchiveComponent()) { "Invalid legacy workspace root" }
        val linux = json.decodeFromJsonElement(WorkspaceArchivePayload.serializer(), workspace.getValue("linux"))
        require(linux.path == "environments/shared-linux.tar" || linux.path == "workspaces/$root/linux.tar") {
            "Unexpected legacy Linux payload path"
        }
        linux.validate(linux.path)
        require(linux.length in 1..32L * 1024 * 1024 * 1024) { "Legacy Linux payload length is invalid" }
        val normalized = linux.copy(sha256 = linux.sha256.normalizedSha256())
        val previous = ignored.putIfAbsent(linux.path, normalized)
        require(previous == null || (linux.path == "environments/shared-linux.tar" && previous == normalized)) {
            "Conflicting or duplicate legacy Linux descriptors"
        }
        JsonObject((workspace - "linux") + mapOf(
            "shellStatus" to JsonPrimitive("READY"),
            "runtimeMetadata" to json.encodeToJsonElement(WorkspaceRuntimeMetadata.serializer(), WorkspaceRuntimeMetadata()),
        ))
    }
    if ("sharedLinuxPayloads" in header) {
        val shared = header["sharedLinuxPayloads"] as? JsonArray ?: error("Invalid shared Linux descriptors")
        val paths = hashSetOf<String>()
        shared.forEach { element ->
            require(element is JsonObject) { "Invalid shared Linux descriptor" }
            val payload = json.decodeFromJsonElement(WorkspaceArchivePayload.serializer(), element)
            payload.validate("environments/shared-linux.tar")
            require(paths.add(payload.path) && ignored[payload.path] == payload.copy(sha256 = payload.sha256.normalizedSha256())) {
                "Shared Linux descriptor is duplicate, unused or inconsistent"
            }
        }
        require(paths == ignored.keys.filter { it.startsWith("environments/") }.toSet()) {
            "Shared Linux descriptors are incomplete"
        }
    }
    val excluded = header["excluded"] as? JsonArray ?: error("Legacy archive exclusions are missing")
    val portableHeader = JsonObject((header - "sharedLinuxPayloads" - "createdAbi") + mapOf(
        "workspaces" to JsonArray(projected),
        "excluded" to JsonArray((excluded.map { it.jsonPrimitive.content } + WorkspaceArchiveManifest.DEFAULT_EXCLUDED)
            .distinct().map { JsonPrimitive(it) }),
    ))
    val portable = json.decodeFromJsonElement(WorkspaceArchiveManifest.serializer(), portableHeader).also { it.validate() }
    return WorkspaceArchiveRestoreManifest(portable, ignored)
}

internal fun requireRestorableWorkspaceArchive(file: File, json: Json, maxManifestBytes: Long) {
    require(!WorkspaceArchiveEncryption.inspect(file).isEncrypted) { "Encrypted legacy workspace archives are not supported" }
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        val first = zip.nextEntry ?: error("Workspace archive is empty")
        require(!first.isDirectory && first.name == "manifest.json") { "Workspace manifest must be the first ZIP entry" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maxManifestBytes) { "Workspace manifest is too large" }
            output.write(buffer, 0, count)
        }
        decodeWorkspaceArchiveForRestore(output.toString(Charsets.UTF_8.name()), json)
    }
}
