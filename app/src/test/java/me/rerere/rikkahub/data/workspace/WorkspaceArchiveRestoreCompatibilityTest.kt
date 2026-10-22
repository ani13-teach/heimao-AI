package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.WorkspaceRuntimeMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceArchiveRestoreCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val linuxBytes = "synthetic-linux".toByteArray()
    private val linux = WorkspaceArchivePayload("environments/shared-linux.tar", linuxBytes.size.toLong(), ArchiveIncremental.digest(linuxBytes))

    private fun payload(value: WorkspaceArchivePayload) = json.encodeToJsonElement(WorkspaceArchivePayload.serializer(), value)
    private fun legacy(label: String = "base"): JsonObject {
        val workspaces = listOf("one", "two").map { id ->
            val workspace = WorkspaceArchiveWorkspace(id, "工作区 $id", id, createdAt = 10, updatedAt = 20, lastAccessAt = 30,
                runtimeMetadata = WorkspaceRuntimeMetadata(sourceUrl = "old-source", detectedArchitecture = "old-abi"),
                files = linux.copy(path = "workspaces/$id/files.tar"), home = linux.copy(path = "workspaces/$id/home.tar"))
            JsonObject(json.encodeToJsonElement(WorkspaceArchiveWorkspace.serializer(), workspace).jsonObject + ("linux" to payload(linux)))
        }
        val portable = WorkspaceArchiveManifest(createdAt = 1, appVersion = label, workspaces = emptyList())
        return JsonObject(json.encodeToJsonElement(WorkspaceArchiveManifest.serializer(), portable).jsonObject + mapOf(
            "workspaces" to JsonArray(workspaces), "sharedLinuxPayloads" to JsonArray(listOf(payload(linux))),
            "excluded" to JsonArray(listOf(JsonPrimitive("workspaces/*/tmp"))),
        ))
    }
    private fun alterWorkspace(manifest: JsonObject, block: (JsonObject) -> JsonObject): JsonObject =
        JsonObject(manifest + ("workspaces" to JsonArray(manifest.getValue("workspaces").jsonArray.mapIndexed { index, element ->
            if (index == 0) block(element.jsonObject) else element
        })))

    @Test fun `legacy full manifests project HOME and identity while clearing Linux environment`() {
        val result = decodeWorkspaceArchiveForRestore(legacy().toString(), json)
        assertTrue(result.isLegacyLinux)
        assertEquals(mapOf(linux.path to linux), result.ignoredLinuxPayloads)
        assertEquals(listOf("one", "two"), result.portable.workspaces.map { it.id })
        result.portable.workspaces.forEach { workspace ->
            assertEquals(WorkspaceRuntimeMetadata(), workspace.runtimeMetadata)
            assertEquals("READY", workspace.shellStatus)
            assertEquals(10L, workspace.createdAt)
            assertEquals(20L, workspace.updatedAt)
            assertEquals(30L, workspace.lastAccessAt)
            assertEquals("workspaces/${workspace.root}/home.tar", workspace.home.path)
        }
        assertTrue(result.portable.excluded.containsAll(WorkspaceArchiveManifest.DEFAULT_EXCLUDED))
        result.portable.validate()
        assertTrue(runCatching { requireWorkspaceManifestVersion(legacy().toString(), json, WorkspaceArchiveManifest.FORMAT) }.isFailure)
    }

    @Test fun `legacy descriptors can be inline without a shared descriptor table`() {
        val original = legacy()
        val inline = JsonObject(original - "sharedLinuxPayloads")
        assertEquals(mapOf(linux.path to linux), decodeWorkspaceArchiveForRestore(inline.toString(), json).ignoredLinuxPayloads)
        val independent = JsonObject(inline + ("workspaces" to JsonArray(inline.getValue("workspaces").jsonArray.map { element ->
            val workspace = element.jsonObject
            JsonObject(workspace + ("linux" to payload(linux.copy(path = "workspaces/${workspace.getValue("root").jsonPrimitive.content}/linux.tar"))))
        })))
        assertEquals(setOf("workspaces/one/linux.tar", "workspaces/two/linux.tar"),
            decodeWorkspaceArchiveForRestore(independent.toString(), json).ignoredLinuxPayloads.keys)
    }

    @Test fun `portable manifests remain unchanged`() {
        val portable = WorkspaceArchiveManifest(createdAt = 1)
        val result = decodeWorkspaceArchiveForRestore(json.encodeToString(portable), json)
        assertEquals(portable, result.portable)
        assertFalse(result.isLegacyLinux)
    }

    @Test fun `null missing HOME null Linux and fake Linux headers reject`() {
        val original = legacy()
        val invalid = listOf(
            alterWorkspace(original) { JsonObject(it - "home") },
            alterWorkspace(original) { JsonObject(it + ("home" to JsonNull)) },
            alterWorkspace(original) { JsonObject(it + ("linux" to JsonNull)) },
            alterWorkspace(original) { JsonObject(it - "linux") },
            JsonObject(original + ("sharedLinuxPayloads" to JsonNull)),
            JsonObject(json.encodeToJsonElement(WorkspaceArchiveManifest.serializer(), WorkspaceArchiveManifest(createdAt = 1)).jsonObject +
                ("sharedLinuxPayloads" to JsonArray(emptyList()))),
            JsonObject(original + ("formatVersion" to JsonPrimitive(1))),
        )
        invalid.forEach { manifest -> assertTrue(runCatching { decodeWorkspaceArchiveForRestore(manifest.toString(), json) }.isFailure) }
    }

    @Test fun `duplicate workspace escaping paths conflicting and unused Linux descriptors reject`() {
        val original = legacy()
        val invalid = listOf(
            JsonObject(original + ("workspaces" to JsonArray(List(2) { original.getValue("workspaces").jsonArray.first() }))),
            alterWorkspace(original) { JsonObject(it + ("linux" to payload(linux.copy(path = "environments/../outside")))) },
            alterWorkspace(original) { JsonObject(it + ("linux" to payload(linux.copy(length = linux.length + 1)))) },
            alterWorkspace(original) { JsonObject(it + ("linux" to payload(linux.copy(sha256 = "0".repeat(64))))) },
            alterWorkspace(original) { JsonObject(it + ("linux" to payload(linux.copy(length = -1)))) },
            JsonObject(original + ("sharedLinuxPayloads" to JsonArray(listOf(payload(linux), payload(linux))))),
            JsonObject(original + ("sharedLinuxPayloads" to JsonArray(emptyList()))),
        )
        invalid.forEach { manifest -> assertTrue(runCatching { decodeWorkspaceArchiveForRestore(manifest.toString(), json) }.isFailure) }
    }

    private fun zip(entries: Map<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
    }
    private fun entries(label: String) = linkedMapOf("manifest.json" to legacy(label).toString().toByteArray(), linux.path to linuxBytes)
    private fun delta(base: Map<String, ByteArray>, current: Map<String, ByteArray>, runtime: String = ""): File {
        val recipe = IncrementalArchiveManifest(version = 1, runtime = runtime,
            baseManifestSha256 = ArchiveIncremental.digest(base.getValue("manifest.json")),
            entries = current.map { (path, bytes) -> ArchiveIncremental.describe(path, bytes).copy(fromBase = path != "manifest.json") })
        return zip(linkedMapOf(ArchiveIncremental.INDEX_PATH to json.encodeToString(recipe).toByteArray(),
            "manifest.json" to current.getValue("manifest.json")))
    }
    private fun readDelta(delta: File, base: File): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ArchiveIncremental.open(delta, base, allowLegacyRestore = true).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    @Test fun `legacy v1 incremental restores with raw base digest and missing runtime`() {
        val base = entries("base")
        val current = entries("current")
        // Old writers omitted the runtime field, unlike current encodeDefaults writers.
        val incremental = delta(base, current)
        val noRuntimeRecipe = json.encodeToString(IncrementalArchiveManifest(version = 1,
            baseManifestSha256 = ArchiveIncremental.digest(base.getValue("manifest.json")),
            entries = current.map { (path, bytes) -> ArchiveIncremental.describe(path, bytes).copy(fromBase = path != "manifest.json") }))
            .replace("\"runtime\":\"\",", "")
        val missingRuntime = zip(linkedMapOf(ArchiveIncremental.INDEX_PATH to noRuntimeRecipe.toByteArray(), "manifest.json" to current.getValue("manifest.json")))
        for (file in listOf(incremental, missingRuntime)) {
            val restored = readDelta(file, zip(base))
            current.forEach { (path, bytes) -> assertArrayEquals(bytes, restored.getValue(path)) }
        }
        requireRestorableWorkspaceArchive(zip(base), json, 2L * 1024 * 1024)
        assertTrue(runCatching { requireCurrentWorkspaceArchive(zip(base), json, 2L * 1024 * 1024) }.isFailure)
        assertTrue(runCatching { ArchiveIncremental.open(incremental, zip(base)) }.isFailure)
    }

    @Test fun `legacy incremental rejects wrong base payload tampering and portable pairing`() {
        val base = entries("base")
        val current = entries("current")
        val incremental = delta(base, current)
        assertTrue(runCatching { readDelta(incremental, zip(entries("wrong"))) }.isFailure)
        assertTrue(runCatching { readDelta(incremental, zip(base + (linux.path to "tampered-linux!".toByteArray()))) }.isFailure)
        assertTrue(runCatching { readDelta(delta(base, current, "portable-v1"), zip(base)) }.isFailure)
        val portable = linkedMapOf("manifest.json" to json.encodeToString(WorkspaceArchiveManifest(createdAt = 1)).toByteArray())
        assertTrue(runCatching { readDelta(delta(portable, portable), zip(portable)) }.isFailure)
        assertTrue(runCatching { readDelta(delta(base, portable), zip(base)) }.isFailure)
        assertTrue(runCatching { ArchiveIncremental.plan(zip(base), current.map { ArchiveIncremental.describe(it.key, it.value) }) }.isFailure)
        val recipe = IncrementalArchiveManifest(version = 1, baseManifestSha256 = "0".repeat(64), entries = listOf(ArchiveIncremental.describe("manifest.json", byteArrayOf())))
        assertTrue(runCatching { ArchiveIncremental.encode(recipe) }.isFailure)
        val currentPlan = ArchiveIncremental.plan(zip(portable), portable.map { ArchiveIncremental.describe(it.key, it.value) })
        assertEquals(2, currentPlan.version)
        assertEquals("portable-v1", currentPlan.runtime)
        val encoded = json.parseToJsonElement(ArchiveIncremental.encode(currentPlan).toString(Charsets.UTF_8)).jsonObject
        assertEquals(2, encoded.getValue("version").jsonPrimitive.int)
        assertEquals("portable-v1", encoded.getValue("runtime").jsonPrimitive.content)
    }
}
