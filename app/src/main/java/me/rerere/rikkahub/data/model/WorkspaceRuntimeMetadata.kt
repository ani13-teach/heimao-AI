package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.workspace.RootfsInstallResult

/**
 * Compact, versioned description of the rootfs currently installed in a workspace.
 *
 * [sourceUrl] is retained for migration/rebuild purposes. Callers showing or logging it must
 * use [redactWorkspaceUrl] rather than exposing query credentials.
 */
@Serializable
data class WorkspaceRuntimeMetadata(
    val metadataVersion: Int = CURRENT_VERSION,
    val sourceUrl: String = "",
    val archiveSha256: String = "",
    val archiveSize: Long = 0L,
    val expectedSha256: String? = null,
    val expectedArchitecture: String? = null,
    val detectedArchitecture: String? = null,
    val supportedAbis: List<String> = emptyList(),
    val distroId: String? = null,
    val distroVersion: String? = null,
    val installedAt: Long? = null,
    val capabilityChecks: Map<String, Boolean> = emptyMap(),
    val toolVersions: Map<String, String> = emptyMap(),
    val toolScanAt: Long? = null,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

fun RootfsInstallResult.toWorkspaceRuntimeMetadata(
    sourceUrl: String,
    installedAt: Long,
): WorkspaceRuntimeMetadata = WorkspaceRuntimeMetadata(
    sourceUrl = sourceUrl,
    archiveSha256 = archiveSha256,
    archiveSize = archiveSize,
    expectedSha256 = expectedSha256,
    expectedArchitecture = expectedArchitecture,
    detectedArchitecture = detectedArchitecture,
    supportedAbis = supportedAbis,
    distroId = distroId,
    distroVersion = distroVersion,
    installedAt = installedAt,
    capabilityChecks = capabilityChecks,
    toolVersions = toolVersions,
    toolScanAt = toolScanAt,
    warnings = warnings,
)

/** Removes query/fragment credentials and user-info before displaying or logging a URL. */
fun redactWorkspaceUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val withoutFragment = trimmed.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    val schemeSeparator = withoutQuery.indexOf("://")
    if (schemeSeparator < 0) return withoutQuery

    val authorityStart = schemeSeparator + 3
    val pathStart = withoutQuery.indexOf('/', authorityStart)
    val authorityEnd = if (pathStart >= 0) pathStart else withoutQuery.length
    val authority = withoutQuery.substring(authorityStart, authorityEnd)
    val safeAuthority = authority.substringAfterLast('@')
    return withoutQuery.substring(0, authorityStart) + safeAuthority +
        withoutQuery.substring(authorityEnd)
}

/** Redacts URLs embedded in exception text before it reaches a log or an error dialog. */
fun redactWorkspaceText(value: String): String = URL_PATTERN.replace(value) { match ->
    redactWorkspaceUrl(match.value)
}

private val URL_PATTERN = Regex("(?i)https?://[^\\s]+")
