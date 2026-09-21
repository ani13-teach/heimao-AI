package me.rerere.workspace

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.util.Locale

/** How a workspace's Linux rootfs is provided. */
enum class WorkspaceEnvironment(val marker: String) {
    /** A rootfs that lives inside the workspace (`<workspace>/linux`). */
    PRIVATE("private"),

    /** The single shared rootfs under `baseDir.parentFile/shared-linux/linux`. */
    SHARED("shared"),
    ;

    companion object {
        fun fromMarker(value: String): WorkspaceEnvironment? =
            WorkspaceEnvironment.entries.firstOrNull { it.marker == value }
    }
}

/**
 * Owns the on-disk layout that decides whether a workspace uses its own rootfs or the shared one.
 *
 * Layout for `baseDir = filesDir/workspaces`:
 * ```
 * <baseDir>/<root>/linux            private rootfs (kept even after switching to shared)
 * <baseDir>/<root>/home             workspace-local HOME for any non-legacy environment
 * <baseDir>/<root>/home-staging     temporary copy target while switching to shared
 * <baseDir>/<root>/guest-tmp        workspace-local guest /tmp
 * <baseDir>/<root>/guest-var-tmp    workspace-local guest /var/tmp
 * <baseDir>/<root>/environment      environment marker (written atomically)
 * baseDir.parentFile/shared-linux/linux     the shared rootfs
 * baseDir.parentFile/shared-linux/recovery  shared install/upgrade transaction area
 * ```
 *
 * The absent marker is the legacy private layout: HOME stays inside `linux/root`. Once a marker
 * exists (either `shared` or `private`) the HOME is the workspace-local `home` directory, so
 * switching back to private keeps everything the user configured while shared. The marker never
 * uses a symbolic link: a link (or any unrecognized content) is rejected instead of followed.
 */
class WorkspaceEnvironmentStore(private val baseDir: File) {

    /** The shared rootfs. A fixed path, never derived from a workspace root. */
    fun sharedLinuxDir(): File = File(sharedLinuxRootDir(), SHARED_LINUX_NAME)

    /** Scratch area for the app-owned shared install/upgrade transaction. */
    fun sharedRecoveryDir(): File = File(sharedLinuxRootDir(), SHARED_RECOVERY_NAME)

    /** The workspace's own rootfs, regardless of the currently selected environment. */
    fun privateLinuxDir(root: String): File = File(workspaceDir(root), PRIVATE_LINUX_NAME)

    /** True when [root] uses the shared rootfs. Only an explicit `shared` marker counts. */
    fun isShared(root: String): Boolean = readMarkerOrNull(root) == WorkspaceEnvironment.SHARED

    /**
     * True when the guest HOME is the workspace-local `home` directory. Holds for an explicit
     * `shared` marker and for an explicit `private` marker; the legacy no-marker layout keeps the
     * rootfs's own `/root`.
     */
    fun hasExternalHome(root: String): Boolean = readMarkerOrNull(root) != null

    /** The declared environment; a missing marker reports [WorkspaceEnvironment.PRIVATE]. */
    fun environmentOf(root: String): WorkspaceEnvironment =
        readMarkerOrNull(root) ?: WorkspaceEnvironment.PRIVATE

    /**
     * Guest HOME. Any non-legacy environment gets a workspace-local directory so the user's files
     * survive a switch between shared and private; the legacy layout keeps the rootfs `/root`.
     */
    fun homeDir(root: String): File = if (hasExternalHome(root)) {
        File(workspaceDir(root), HOME_NAME)
    } else {
        File(privateLinuxDir(root), ROOTFS_HOME_NAME)
    }

    /** Workspace-local guest `/tmp`; only bound while HOME is external. */
    fun guestTempDir(root: String): File = File(workspaceDir(root), GUEST_TMP_NAME)

    /** Workspace-local guest `/var/tmp`; only bound while HOME is external. */
    fun guestVarTempDir(root: String): File = File(workspaceDir(root), GUEST_VAR_TMP_NAME)

    /**
     * Switch [root] to the shared rootfs.
     *
     * The previous private rootfs is deliberately kept as a fallback, and its `/root` is copied
     * into the workspace-local HOME so existing files follow the switch. The copy goes through a
     * temporary `home-staging` directory that is atomically renamed into place, so a failed copy
     * leaves no half-written HOME and does not claim the shared environment. Idempotent.
     */
    fun selectShared(root: String) {
        val dir = requireWorkspaceDirectory(root)
        if (isShared(root)) return
        val home = File(dir, HOME_NAME)
        if (!home.exists()) {
            val previousHome = File(privateLinuxDir(root), ROOTFS_HOME_NAME)
            if (previousHome.exists()) {
                migrateHome(previousHome, home, File(dir, HOME_STAGING_NAME))
            } else {
                Files.createDirectories(home.toPath())
            }
        } else if (!home.isDirectory) {
            error("Workspace home path is not a directory: ${home.path}")
        }
        check(home.isDirectory) { "Workspace home was not prepared: ${home.path}" }
        writeMarker(root, WorkspaceEnvironment.SHARED)
    }

    /**
     * Mark a brand-new [root] as shared before it ever had a private rootfs. Nothing is migrated:
     * there is no previous HOME yet. Idempotent.
     */
    fun initializeShared(root: String) {
        val dir = requireWorkspaceDirectory(root)
        if (isShared(root)) return
        val home = File(dir, HOME_NAME)
        if (!home.exists()) Files.createDirectories(home.toPath())
        check(home.isDirectory) { "Workspace home was not prepared: ${home.path}" }
        writeMarker(root, WorkspaceEnvironment.SHARED)
    }

    /**
     * Initializes a private workspace whose HOME must remain outside an unavailable rootfs.
     *
     * Portable restore uses this when it can preserve `/root` from an archive but cannot activate
     * the archived Linux tree on this device. A later private-rootfs installation keeps binding
     * this directory instead of hiding the restored HOME inside a replacement rootfs.
     */
    fun initializePrivateWithExternalHome(root: String) {
        val dir = requireWorkspaceDirectory(root)
        val home = File(dir, HOME_NAME)
        if (!home.exists()) Files.createDirectories(home.toPath())
        check(home.isDirectory && !Files.isSymbolicLink(home.toPath())) {
            "Workspace home was not prepared: ${home.path}"
        }
        writeMarker(root, WorkspaceEnvironment.PRIVATE)
    }

    /**
     * Switch [root] back to its private rootfs.
     *
     * The workspace-local HOME is intentionally preserved (the marker stays external) so the user
     * keeps their login state and files; the private rootfs must still be usable, otherwise the
     * fallback would silently point at a broken environment.
     */
    fun selectPrivate(root: String) {
        requireWorkspaceDirectory(root)
        if (!isShared(root)) return
        val privateLinux = privateLinuxDir(root)
        check(privateLinux.hasUsableFullRootfs()) {
            "Cannot switch workspace back to its private rootfs: " +
                "${privateLinux.path} is missing or incomplete"
        }
        writeMarker(root, WorkspaceEnvironment.PRIVATE)
    }

    private fun readMarkerOrNull(root: String): WorkspaceEnvironment? {
        val dir = workspaceDir(root)
        val dirPath = dir.toPath()
        if (!Files.exists(dirPath, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(dirPath)) {
            "Workspace directory must not be a symbolic link: ${dir.path}"
        }
        val marker = File(dir, MARKER_NAME)
        val path = marker.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(path)) {
            "Workspace environment marker must not be a symbolic link: ${marker.path}"
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace environment marker is not a regular file: ${marker.path}"
        }
        val value = marker.readText(StandardCharsets.UTF_8).trim().lowercase(Locale.US)
        return WorkspaceEnvironment.fromMarker(value)
            ?: error("Unrecognized workspace environment marker in ${marker.path}: '$value'")
    }

    private fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    private fun sharedLinuxRootDir(): File =
        baseDir.parentFile?.let { File(it, SHARED_LINUX_ROOT_NAME) }
            ?: File(baseDir, SHARED_LINUX_ROOT_NAME)

    private fun requireWorkspaceDirectory(root: String): File {
        val dir = workspaceDir(root)
        val path = dir.toPath()
        require(!Files.isSymbolicLink(path)) {
            "Workspace directory must not be a symbolic link: ${dir.path}"
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(dir.mkdirs() || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "Unable to create workspace directory: ${dir.path}"
            }
        }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace path is not a directory: ${dir.path}"
        }
        return dir
    }

    private fun writeMarker(root: String, environment: WorkspaceEnvironment) {
        val dir = requireWorkspaceDirectory(root)
        val marker = File(dir, MARKER_NAME)
        val temp = File(dir, TEMP_MARKER_NAME)
        val markerPath = marker.toPath()
        val tempPath = temp.toPath()
        require(!Files.isSymbolicLink(markerPath)) {
            "Workspace environment marker must not be a symbolic link: ${marker.path}"
        }
        require(!Files.isSymbolicLink(tempPath)) {
            "Workspace environment marker temp must not be a symbolic link: ${temp.path}"
        }
        Files.deleteIfExists(tempPath)
        // fsync before the rename so a crash cannot leave a marker with unflushed content.
        FileOutputStream(temp).use { output ->
            output.write(environment.marker.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                tempPath,
                markerPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Only the "filesystem cannot do an atomic rename" case falls back. Any other IO error
            // is surfaced instead of overwriting the marker with a possibly partial write.
            Files.move(tempPath, markerPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Copies [previousHome] into [home] through [staging]. A failure removes its own staging
     * directory and rethrows, leaving the old marker and the previous HOME untouched so the switch
     * can simply be retried.
     */
    private fun migrateHome(previousHome: File, home: File, staging: File) {
        deleteTreeNoFollow(staging)
        try {
            copyTreePreservingLinks(previousHome, staging)
            try {
                Files.move(
                    staging.toPath(),
                    home.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), home.toPath())
            }
        } catch (error: Throwable) {
            runCatching { deleteTreeNoFollow(staging) }
            throw error
        }
    }

    /**
     * Recursive copy that keeps symbolic links instead of dereferencing them.
     *
     * A link is recreated as a link; if the platform refuses to create it the switch is aborted
     * rather than silently copying the link target. Directories are walked without following links,
     * so a link can never make the copy loop or escape the source tree.
     */
    private fun copyTreePreservingLinks(source: File, target: File) {
        val sourcePath = source.toPath()
        require(Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            "Home source is missing: ${source.path}"
        }
        if (Files.isSymbolicLink(sourcePath)) {
            createSymbolicLink(sourcePath, target)
            return
        }
        if (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            target.parentFile?.mkdirs()
            Files.copy(
                sourcePath,
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            return
        }
        Files.walkFileTree(
            sourcePath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val destination = target.toPath().resolve(sourcePath.relativize(dir))
                    Files.createDirectories(destination)
                    copyAttributes(dir, destination)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val destination = target.toPath().resolve(sourcePath.relativize(file))
                    if (attrs.isSymbolicLink) {
                        createSymbolicLink(file, destination.toFile())
                    } else {
                        destination.parent?.let { Files.createDirectories(it) }
                        Files.copy(
                            file,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    throw exc
            },
        )
    }

    private fun createSymbolicLink(source: Path, target: File) {
        target.parentFile?.mkdirs()
        Files.deleteIfExists(target.toPath())
        Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(source))
    }

    private fun copyAttributes(source: Path, target: Path) {
        runCatching {
            Files.setLastModifiedTime(
                target,
                Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS),
            )
        }
        runCatching {
            Files.setPosixFilePermissions(
                target,
                Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS),
            )
        }
    }

    /** Deletes a tree without ever following a symbolic link into its target. */
    private fun deleteTreeNoFollow(target: File) {
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { children ->
                children.forEach { child -> deleteTreeNoFollow(child.toFile()) }
            }
        }
        Files.deleteIfExists(path)
    }

    private fun requireValidRoot(root: String) {
        require(root != "." && root != ".." && root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private companion object {
        private const val MARKER_NAME = "environment"
        private const val TEMP_MARKER_NAME = "environment.tmp"
        private const val PRIVATE_LINUX_NAME = "linux"
        private const val ROOTFS_HOME_NAME = "root"
        private const val HOME_NAME = "home"
        private const val HOME_STAGING_NAME = "home-staging"
        private const val GUEST_TMP_NAME = "guest-tmp"
        private const val GUEST_VAR_TMP_NAME = "guest-var-tmp"
        private const val SHARED_LINUX_ROOT_NAME = "shared-linux"
        private const val SHARED_LINUX_NAME = "linux"
        private const val SHARED_RECOVERY_NAME = "recovery"

        // Same rule WorkspaceManager uses, so a store call can never escape baseDir.
        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}
