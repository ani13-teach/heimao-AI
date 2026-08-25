package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/** The files required for a usable FULL_ROOTFS or WORKSPACE_PACKAGE shell. */
private val FULL_ROOTFS_REQUIRED_PATHS = listOf(
    "bin/sh",
    "bin/bash",
    "usr/bin/env",
)

/**
 * Returns the required rootfs paths which are absent or not regular files.
 * apt/python3 are deliberately not included: they are runtime capabilities which
 * WORKSPACE_PACKAGE may install later in an otherwise complete Ubuntu Base rootfs.
 */
fun File.missingFullRootfsPaths(): List<String> {
    if (!isDirectory || Files.isSymbolicLink(toPath())) return FULL_ROOTFS_REQUIRED_PATHS.toList()
    val root = canonicalFile
    return FULL_ROOTFS_REQUIRED_PATHS.filterNot { path ->
        hasUsableRootfsFile(root, path)
    }
}

fun File.hasUsableFullRootfs(): Boolean = missingFullRootfsPaths().isEmpty()

/** The old workspace-only runner still needs its Android-backed /bin/sh entry point. */
fun File.hasUsableShellRootfs(): Boolean =
    !Files.isSymbolicLink(toPath()) && isDirectory && hasUsableRootfsFile(canonicalFile, "bin/sh")

private fun hasUsableRootfsFile(root: File, relativePath: String): Boolean {
    val target = File(root, relativePath)
    if (!target.isFile) return false
    val canonicalTarget = target.canonicalFile
    return canonicalTarget.path == root.path ||
        canonicalTarget.path.startsWith(root.path + File.separator)
}

/** Public archive-validation helpers; inspection itself remains read-only. */
fun detectRootfsArchitecture(rootfs: File): String? =
    RootfsRuntimeInspector.inspect(rootfs).detectedArchitecture

fun supportedRootfsAbis(): List<String> = RootfsRuntimeInspector.defaultSupportedAbis()

fun isRootfsArchitectureCompatible(
    expectedArchitecture: String?,
    detectedArchitecture: String?,
    supportedAbis: List<String>,
): Boolean = runCatching {
    RootfsRuntimeInspector.assertArchitectureCompatible(
        expectedArchitecture = expectedArchitecture,
        detectedArchitecture = detectedArchitecture,
        supportedAbis = supportedAbis,
    )
}.isSuccess

fun fullRootfsIncompleteMessage(missing: List<String>): String =
    "FULL_ROOTFS rootfs is incomplete; missing ${missing.joinToString()}. " +
        "Install a complete Linux rootfs. apt/python3 are runtime capabilities, " +
        "not installation completeness requirements; WORKSPACE_PACKAGE can install them later."
