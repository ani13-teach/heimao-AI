package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    private val rootfsPatchOptionsProvider: () -> RootfsPatchOptions = { RootfsPatchOptions() },
) {
    private val fileSystem = WorkspaceFileSystem(config)

    /**
     * Per-root Linux environment selection (private rootfs vs the shared rootfs). Callers may use
     * this to inspect or switch environments; the manager itself only reads it.
     */
    val environments = WorkspaceEnvironmentStore(baseDir)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = ensureWorkspaceFiles(root)
        if (environments.hasExternalHome(root)) {
            // HOME and the guest temp areas are workspace-local for both the shared and the
            // explicit-private environment; only the legacy no-marker layout keeps them in rootfs.
            environments.homeDir(root).mkdirs()
            environments.guestTempDir(root).mkdirs()
            environments.guestVarTempDir(root).mkdirs()
        }
        if (!environments.isShared(root)) {
            privateLinuxDir(root).mkdirs()
        }
        return dir
    }

    /**
     * Prepare only the host-side files/temp areas. The internal files-only channel may use this while a
     * rootfs is being atomically exchanged; they must not recreate a temporarily moved linux
     * directory and interfere with recovery.
     */
    fun ensureWorkspaceFiles(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    /** The rootfs actually used by [root]: the shared one when selected, otherwise its own. */
    fun linuxDir(root: String): File =
        if (environments.isShared(root)) environments.sharedLinuxDir()
        else environments.privateLinuxDir(root)

    /** The workspace's own rootfs, independent of the current environment selection. */
    fun privateLinuxDir(root: String): File = environments.privateLinuxDir(root)

    /**
     * Guest HOME for [root]. When the shared rootfs is selected this is a workspace-local
     * directory, so sharing one rootfs does not merge every workspace's `/root`.
     */
    fun homeDir(root: String): File = environments.homeDir(root)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = linuxDir(root).hasUsableFullRootfs()

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // A workspace-local HOME is one physical tree for every workspace, so its per-user
        // directories must resolve to the workspace-local copy instead of the rootfs ones.
        if (environments.hasExternalHome(root)) {
            externalHomeMapping(root, trimmed)?.let { return it }
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(root: String, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry =
        fileSystem.move(filesDir(root), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        accessMode: WorkspaceShellAccessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        return shellRunner.execute(
            createShellContext(root, command, cwd, timeoutMillis, stdin, accessMode)
        )
    }

    /**
     * Builds the exact context [executeCommand] would pass to the shell runner, so the interactive
     * terminal can reuse it (same mounts, HOME, temp areas and patch decision) without executing a
     * command. [command] may be blank for the terminal, which builds its own command line.
     */
    fun createShellContext(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        accessMode: WorkspaceShellAccessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
    ): WorkspaceShellContext {
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        val fullLinux = accessMode == WorkspaceShellAccessMode.FULL_ROOTFS ||
            accessMode == WorkspaceShellAccessMode.WORKSPACE_PACKAGE
        // Shared execution does not automatically patch the common rootfs. An external HOME is
        // workspace-local. Switching back to private must keep that HOME while the private rootfs becomes patchable again, so
        // the two decisions are deliberately separate.
        val externalHome = fullLinux && environments.hasExternalHome(root)
        return WorkspaceShellContext(
            root = root,
            command = command,
            cwd = cwd,
            filesDir = filesDir(root),
            linuxDir = linuxDir(root),
            tempDir = tempDir(root),
            workingDir = workingDir,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            accessMode = accessMode,
            bindMounts = if (accessMode == WorkspaceShellAccessMode.FULL_ROOTFS) {
                bindMounts
            } else {
                emptyList()
            },
            rootfsPatchOptions = if (fullLinux) {
                rootfsPatchOptionsProvider()
            } else {
                RootfsPatchOptions()
            },
            homeDir = if (externalHome) environments.homeDir(root) else null,
            guestTempDir = if (externalHome) environments.guestTempDir(root) else null,
            guestVarTempDir = if (externalHome) environments.guestVarTempDir(root) else null,
            patchRootfs = !environments.isShared(root),
        )
    }

    private fun externalHomeMapping(root: String, path: String): RootfsLocation? {
        fun under(prefix: String) = path == prefix || path.startsWith("$prefix/")
        fun relative(prefix: String) = path.removePrefix(prefix).trimStart('/')
        return when {
            under(ROOTFS_HOME_DIR) -> RootfsLocation(environments.homeDir(root), relative(ROOTFS_HOME_DIR))
            under(ROOTFS_TMP_DIR) -> RootfsLocation(environments.guestTempDir(root), relative(ROOTFS_TMP_DIR))
            under(ROOTFS_VAR_TMP_DIR) ->
                RootfsLocation(environments.guestVarTempDir(root), relative(ROOTFS_VAR_TMP_DIR))
            else -> null
        }
    }

    private fun requireValidRoot(root: String) {
        require(root != "." && root != ".." && root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files are always workspace-local, shared or not.
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            environments.guestTempDir(root).let { if (it.exists()) it.deleteRecursively() }
            environments.guestVarTempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Only a private rootfs owns its /tmp and /var/tmp. A shared rootfs (or a marker we
            // cannot read) may be in use by other workspaces, so its temp areas are never deleted.
            val environment = runCatching { environments.environmentOf(root) }.getOrNull()
            if (environment != WorkspaceEnvironment.PRIVATE) continue
            File(privateLinuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(privateLinuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        const val MAX_COMMAND_TIMEOUT_MS = 600_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 共享 Rootfs 下由宿主映射的每工作区目录 */
        private const val ROOTFS_HOME_DIR = "/root"
        private const val ROOTFS_TMP_DIR = "/tmp"
        private const val ROOTFS_VAR_TMP_DIR = "/var/tmp"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
