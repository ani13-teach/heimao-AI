package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** Result returned only after a rootfs has passed validation and has been exchanged. */
data class RootfsInstallResult(
    val archiveSha256: String,
    val archiveSize: Long,
    val expectedSha256: String?,
    val expectedArchitecture: String?,
    val detectedArchitecture: String?,
    val supportedAbis: List<String>,
    val distroId: String?,
    val distroVersion: String?,
    val capabilityChecks: Map<String, Boolean>,
    val toolVersions: Map<String, String>,
    val toolScanAt: Long,
    val warnings: List<String>,
)

internal data class RootfsRuntimeInspection(
    val detectedArchitecture: String?,
    val distroId: String?,
    val distroVersion: String?,
    val capabilityChecks: Map<String, Boolean>,
    val toolVersions: Map<String, String>,
    val toolScanAt: Long,
    val warnings: List<String>,
)

/**
 * Bounded, read-only inspection of well-known rootfs files.
 *
 * In particular this never sources a shell profile and never executes a workspace-provided
 * script. Tool versions are read from package metadata when available; missing metadata is a
 * warning rather than an installation attempt.
 */
internal object RootfsRuntimeInspector {
    private const val MAX_METADATA_BYTES = 128 * 1024
    private const val MAX_PACKAGE_STATUS_BYTES = 2 * 1024 * 1024
    private const val ELF_HEADER_BYTES = 64
    private const val MAX_VALUE_LENGTH = 256

    private val toolSpecs = listOf(
        ToolSpec("bash", listOf("bash"), "bin/bash"),
        ToolSpec("python3", listOf("python3"), "usr/bin/python3"),
        ToolSpec("pip", listOf("python3-pip", "pip"), "usr/bin/pip3"),
        ToolSpec("node", listOf("nodejs", "node"), "usr/bin/node"),
        ToolSpec("npm", listOf("npm"), "usr/bin/npm"),
        ToolSpec("go", listOf("golang-go", "golang"), "usr/bin/go"),
        ToolSpec("rustc", listOf("rustc"), "usr/bin/rustc"),
        ToolSpec("cargo", listOf("cargo"), "usr/bin/cargo"),
    )

    fun inspect(
        rootfs: File,
        scanAt: Long = System.currentTimeMillis(),
    ): RootfsRuntimeInspection {
        val warnings = linkedSetOf<String>()
        val osRelease = runCatching { readOsRelease(rootfs) }
            .onFailure { warnings += "Failed to read distro metadata" }
            .getOrNull()
        val distroId = osRelease?.get("ID")?.takeIf { it.isNotBlank() }
        val distroVersion = osRelease?.let { values ->
            values["VERSION_ID"]?.takeIf { it.isNotBlank() }
                ?: values["VERSION"]?.takeIf { it.isNotBlank() }
        }
        if (distroId == null) warnings += "Distro ID was not detected"
        if (distroVersion == null) warnings += "Distro version was not detected"

        val detectedArchitecture = runCatching { detectArchitecture(rootfs) }
            .onFailure { warnings += "Failed to inspect rootfs architecture" }
            .getOrNull()
        if (detectedArchitecture == null) {
            warnings += "Rootfs architecture was not detected"
        }

        val capabilityChecks = linkedMapOf(
            "shell" to hasRegularFile(rootfs, "bin/sh"),
            "bash" to hasRegularFile(rootfs, "bin/bash"),
            "env" to hasRegularFile(rootfs, "usr/bin/env"),
            "tmp" to hasDirectory(rootfs, "tmp"),
            "varTmp" to hasDirectory(rootfs, "var/tmp"),
            "osRelease" to (osRelease != null),
            "architecture" to (detectedArchitecture != null),
            "fullRootfs" to runCatching { rootfs.hasUsableFullRootfs() }
                .onFailure { warnings += "Failed to check full rootfs capability" }
                .getOrDefault(false),
        )
        capabilityChecks.forEach { (name, available) ->
            if (!available) warnings += "Capability check failed: $name"
        }

        val toolVersions = runCatching { scanToolVersions(rootfs, warnings) }
            .onFailure { warnings += "Tool version scan failed" }
            .getOrDefault(emptyMap())

        return RootfsRuntimeInspection(
            detectedArchitecture = detectedArchitecture,
            distroId = distroId,
            distroVersion = distroVersion,
            capabilityChecks = capabilityChecks,
            toolVersions = toolVersions,
            toolScanAt = scanAt,
            warnings = warnings.toList(),
        )
    }

    fun architectureFromSourceUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val candidates = listOf(
            "arm64-v8a" to "arm64",
            "aarch64" to "arm64",
            "arm64" to "arm64",
            "x86_64" to "x86_64",
            "amd64" to "x86_64",
            "armeabi-v7a" to "arm",
            "armv7" to "arm",
            "armhf" to "arm",
            "i386" to "x86",
            "i686" to "x86",
            "x86" to "x86",
            "riscv64" to "riscv64",
        )
        return candidates.firstOrNull { (marker, _) ->
            Regex("(?:^|[^a-z0-9])${Regex.escape(marker)}(?:[^a-z0-9]|$)").containsMatchIn(path)
        }?.second
    }

    fun defaultSupportedAbis(): List<String> {
        val androidAbis = runCatching {
            val field = Class.forName("android.os.Build").getField("SUPPORTED_ABIS")
            (field.get(null) as? Array<*>)
                ?.filterIsInstance<String>()
                ?.filter { it.isNotBlank() }
        }.getOrNull().orEmpty()
        if (androidAbis.isNotEmpty()) return androidAbis

        return when (System.getProperty("os.arch").orEmpty().lowercase(Locale.US)) {
            "aarch64", "arm64", "arm64-v8a" -> listOf("arm64-v8a")
            "arm", "armv7", "armv7l", "armeabi-v7a" -> listOf("armeabi-v7a")
            "amd64", "x86_64" -> listOf("x86_64")
            "x86", "i386", "i686" -> listOf("x86")
            "riscv64" -> listOf("riscv64")
            else -> emptyList()
        }
    }

    fun assertArchitectureCompatible(
        expectedArchitecture: String?,
        detectedArchitecture: String?,
        supportedAbis: List<String>,
    ) {
        val expected = canonicalArchitecture(expectedArchitecture)
        val detected = canonicalArchitecture(detectedArchitecture)
        val supported = supportedAbis.mapNotNull(::canonicalArchitecture).toSet()
        require(expected == null || detected == null || expected == detected) {
            "Rootfs architecture mismatch: expected $expected but detected $detected"
        }
        if (supported.isEmpty()) return
        listOf("expected" to expected, "detected" to detected).forEach { (kind, architecture) ->
            require(architecture == null || architecture in supported) {
                "Rootfs $kind architecture $architecture is incompatible with supported ABI(s): " +
                    supportedAbis.joinToString()
            }
        }
    }

    fun canonicalArchitecture(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', '-')
            ?.replace('.', '-')
            ?.trim('-')
            ?: return null
        return when {
            normalized in setOf("aarch64", "arm64", "arm64-v8a") -> "arm64"
            normalized in setOf("arm", "armhf", "armv7", "armv7l", "armv7-a", "armeabi-v7a") -> "arm"
            normalized in setOf("x86-64", "amd64", "x8664") -> "x86_64"
            normalized in setOf("x86", "i386", "i486", "i586", "i686", "386") -> "x86"
            normalized in setOf("riscv64", "riscv-64") -> "riscv64"
            else -> null
        }
    }

    private fun detectArchitecture(rootfs: File): String? {
        val executableCandidates = listOf("bin/sh", "bin/bash", "usr/bin/env")
        executableCandidates.forEach { path ->
            val file = safeFile(rootfs, path) ?: return@forEach
            val architecture = readElfArchitecture(file)
            if (architecture != null) return architecture
        }

        val dpkgArch = safeFile(rootfs, "var/lib/dpkg/arch")
            ?.let { readLimitedText(it, MAX_METADATA_BYTES)?.text }
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotBlank() }
        return canonicalArchitecture(dpkgArch)
    }

    private fun readElfArchitecture(file: File): String? {
        val header = readLimitedBytes(file, ELF_HEADER_BYTES) ?: return null
        if (header.size < 20 ||
            header[0] != 0x7f.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) return null

        val littleEndian = header.getOrNull(5)?.toInt() == 1
        val machine = if (littleEndian) {
            (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        } else {
            ((header[18].toInt() and 0xff) shl 8) or (header[19].toInt() and 0xff)
        }
        return when (machine) {
            3 -> "x86"
            40 -> "arm"
            62 -> "x86_64"
            183 -> "arm64"
            243 -> "riscv64"
            else -> null
        }
    }

    private fun readOsRelease(rootfs: File): Map<String, String>? {
        val file = safeFile(rootfs, "etc/os-release")
            ?: safeFile(rootfs, "usr/lib/os-release")
            ?: return null
        val content = readLimitedText(file, MAX_METADATA_BYTES) ?: return null
        return parseKeyValueFile(content.text).takeIf { it.isNotEmpty() }
    }

    private fun scanToolVersions(
        rootfs: File,
        warnings: MutableSet<String>,
    ): Map<String, String> {
        val statusFile = safeFile(rootfs, "var/lib/dpkg/status")
        if (statusFile == null) {
            warnings += "Tool version metadata is unavailable"
            return emptyMap()
        }
        val content = readLimitedText(statusFile, MAX_PACKAGE_STATUS_BYTES)
        if (content == null) {
            warnings += "Tool version metadata could not be read"
            return emptyMap()
        }
        if (content.truncated) warnings += "Tool version metadata was truncated"
        val packageVersions = parsePackageVersions(content.text)
        return buildMap {
            toolSpecs.forEach { spec ->
                val version = spec.packageNames.firstNotNullOfOrNull { packageVersions[it] }
                if (version != null && hasRegularFile(rootfs, spec.executable)) {
                    put(spec.name, version)
                } else if (hasRegularFile(rootfs, spec.executable)) {
                    warnings += "Version unavailable for ${spec.name}"
                }
            }
            if (isEmpty()) warnings += "No primary tool versions detected"
        }
    }

    private fun parsePackageVersions(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.split(Regex("\\n\\s*\\n")).forEach { stanza ->
            val values = parseKeyValueFile(stanza)
            val packageName = values["Package"] ?: return@forEach
            val version = values["Version"]?.takeIf { it.isNotBlank() } ?: return@forEach
            result.putIfAbsent(packageName, version)
        }
        return result
    }

    private fun parseKeyValueFile(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val equals = line.indexOf('=')
            val colon = line.indexOf(':')
            val separator = when {
                equals > 0 -> equals
                colon > 0 -> colon
                else -> -1
            }
            if (separator > 0) {
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key.isNotBlank()) values[key] = unquote(value).take(MAX_VALUE_LENGTH)
            }
        }
        return values
    }

    private fun unquote(value: String): String = value
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .trim()

    private fun hasRegularFile(rootfs: File, relativePath: String): Boolean =
        safeFile(rootfs, relativePath) != null

    private fun hasDirectory(rootfs: File, relativePath: String): Boolean {
        val file = safePath(rootfs, relativePath) ?: return false
        return Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun safeFile(rootfs: File, relativePath: String): File? =
        safePath(rootfs, relativePath)?.takeIf { Files.isRegularFile(it.toPath()) }

    private fun safePath(rootfs: File, relativePath: String): File? {
        val root = runCatching { rootfs.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(rootfs, relativePath).canonicalFile }.getOrNull() ?: return null
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
        return file
    }

    private fun readLimitedText(file: File, limit: Int): LimitedContent? =
        readLimitedBytesWithStatus(file, limit)?.let { content ->
            LimitedContent(String(content.bytes, StandardCharsets.UTF_8), content.truncated)
        }

    private fun readLimitedBytes(file: File, limit: Int): ByteArray? =
        readLimitedBytesWithStatus(file, limit)?.bytes

    private fun readLimitedBytesWithStatus(file: File, limit: Int): LimitedBytes? {
        if (limit <= 0) return LimitedBytes(ByteArray(0), truncated = false)
        return runCatching {
            val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            var remaining = limit
            file.inputStream().use { input ->
                val buffer = ByteArray(minOf(16 * 1024, limit))
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                val truncated = remaining == 0 && input.read() >= 0
                LimitedBytes(output.toByteArray(), truncated)
            }
        }.getOrNull()
    }

    private data class LimitedBytes(val bytes: ByteArray, val truncated: Boolean)

    private data class LimitedContent(val text: String, val truncated: Boolean)

    private data class ToolSpec(
        val name: String,
        val packageNames: List<String>,
        val executable: String,
    )
}
