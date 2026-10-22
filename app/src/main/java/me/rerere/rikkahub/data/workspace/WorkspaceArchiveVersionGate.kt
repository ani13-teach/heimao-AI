package me.rerere.rikkahub.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

internal fun requireWorkspaceManifestVersion(text: String, json: Json, format: String) {
    val header = json.parseToJsonElement(text).jsonObject
    require("sharedLinuxPayloads" !in header) { "Linux workspace archives are not portable" }
    val workspaces = header["workspaces"]?.jsonArray?.map { it.jsonObject }.orEmpty() +
        listOfNotNull(header["workspace"]?.jsonObject)
    workspaces.forEach { workspace ->
        require("linux" !in workspace && workspace["home"] is kotlinx.serialization.json.JsonObject) {
            "Portable workspace HOME is required; Linux payloads are unsupported"
        }
    }
    require(header["format"]?.jsonPrimitive?.content == format &&
        header["formatVersion"]?.jsonPrimitive?.content == "2") {
        "Legacy workspace archives are not supported; a v2 manifest is required"
    }
}

internal fun requireCurrentWorkspaceArchive(file: File, json: Json, maxManifestBytes: Long, budget: WorkspaceArchiveBudget? = null) {
    require(!WorkspaceArchiveEncryption.inspect(file).isEncrypted) {
        "Encrypted legacy workspace archives are not supported"
    }
    ZipInputStream(file.inputStream().buffered()).use { zip ->
        val first = zip.nextEntry ?: error("Workspace archive is empty")
        require(!first.isDirectory && first.name == "manifest.json") {
            "Legacy workspace archives without a v2 manifest are not supported"
        }
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maxManifestBytes) { "Workspace manifest is too large" }
            budget?.recordZipDecompressedBytes(count.toLong())
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size.toLong() <= maxManifestBytes) { "Workspace manifest is too large" }
        val text = bytes.toString(Charsets.UTF_8)
        requireWorkspaceManifestVersion(text, json, WorkspaceArchiveManifest.FORMAT)
        json.decodeFromString<WorkspaceArchiveManifest>(text).validate()
    }
}
