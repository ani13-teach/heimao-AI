package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.net.ConnectivityManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.workspace.WorkspaceBindMount
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Shared app-side paths and mounts used by both the full shell and the terminal UI. */
internal fun Context.workspaceDirectory(root: String): File =
    File(File(applicationContext.filesDir, "workspaces"), root)

internal fun Context.workspaceBindMounts(): List<WorkspaceBindMount> = listOf(
    // FileFolders.SNAPSHOTS is intentionally absent: snapshot archives are app-private and are
    // never exposed to either the user terminal or the AI shell.
    WorkspaceBindMount(
        source = File(filesDir, FileFolders.SKILLS).apply { mkdirs() },
        target = "/skills",
    ),
    WorkspaceBindMount(
        source = File(filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
        target = "/tool_outputs",
    ),
    WorkspaceBindMount(
        source = File(filesDir, FileFolders.UPLOAD).apply { mkdirs() },
        target = "/upload",
    ),
)

internal fun Context.workspaceDnsServers(): List<String> {
    return try {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
        val network = connectivityManager.activeNetwork
            ?: return selectWorkspaceDnsServers(false, null)
        selectWorkspaceDnsServers(activeNetworkPresent = true) {
            connectivityManager.getLinkProperties(network)
                ?.dnsServers
                ?.mapNotNull { it.hostAddress }
        }
    } catch (_: SecurityException) {
        // ACCESS_NETWORK_STATE may be absent in an older/sideloaded manifest, or the
        // platform may reject the query. DNS is an optional patch input, so fall back to
        // RootfsPatcher's safe defaults instead of failing shell startup.
        emptyList()
    }
}

/** Pure selection logic kept separate from Android ConnectivityManager calls for JVM tests. */
internal fun selectWorkspaceDnsServers(
    activeNetworkPresent: Boolean,
    dnsServerAddresses: Iterable<String>?,
): List<String> {
    if (!activeNetworkPresent) return emptyList()
    return (dnsServerAddresses ?: emptyList())
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

/** Also makes the platform query itself JVM-testable without constructing a ConnectivityManager. */
internal fun selectWorkspaceDnsServers(
    activeNetworkPresent: Boolean,
    dnsServerAddressesProvider: () -> Iterable<String>?,
): List<String> {
    if (!activeNetworkPresent) return emptyList()
    return try {
        selectWorkspaceDnsServers(true, dnsServerAddressesProvider())
    } catch (_: SecurityException) {
        emptyList()
    }
}

/** Owner-only (`0700`): the least access that makes a placeholder directory readable again. */
private const val OWNER_DIRECTORY_MODE = 0x1c0

/**
 * PRoot creates the guest side of a bind mount that is missing from the rootfs as an empty
 * placeholder directory with *every* permission bit cleared (`mkdir(path, 0)` in its
 * `build_glue()`), and it removes that placeholder again only when the traced process exits
 * cleanly. A full-rootfs terminal that is killed — app swiped away, force stop, out of memory —
 * leaves the `0000` placeholder behind permanently, and from then on every workspace archive and
 * snapshot fails before it starts writing: `Files.list` cannot read the placeholder, so the
 * rootfs walk dies on the first entry.
 *
 * Give the placeholder owner access back instead of deleting it: the empty directory is the
 * mount point the full-rootfs terminal expects, and PRoot leaves an existing path alone. Only
 * root-level entries with no permission bits at all are touched, so an ordinary rootfs is
 * archived unchanged. Every bind target this app installs is a root-level guest path, and the
 * workspace-only shell modes never pollute the rootfs at all.
 *
 * Both filesystem lookups are injected so the selection rules stay JVM-testable.
 *
 * @return the names of the entries that were repaired.
 */
internal fun repairProotRootfsPlaceholders(
    linuxDir: File,
    permissionBits: (Path) -> Int? = ::readPermissionBits,
    applyMode: (File, Int) -> Unit = WorkspaceArchiveTar::applyMode,
): List<String> {
    val root = linuxDir.toPath()
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        return emptyList()
    }
    val repaired = mutableListOf<String>()
    linuxDir.listFiles().orEmpty().sortedBy { it.name }.forEach { entry ->
        val path = entry.toPath()
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return@forEach
        }
        // A null mode means this platform cannot report Unix permissions; leave the entry alone
        // rather than guessing.
        if (permissionBits(path) != 0) return@forEach
        runCatching { applyMode(entry, OWNER_DIRECTORY_MODE) }.onSuccess { repaired += entry.name }
    }
    return repaired
}

private fun readPermissionBits(path: Path): Int? = runCatching {
    // 0x1ff is rwxrwxrwx; setuid/setgid/sticky are not relevant to readability.
    (Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Number).toInt() and 0x1ff
}.getOrNull()
