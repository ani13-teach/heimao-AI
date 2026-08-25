package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.net.ConnectivityManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

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
