package me.rerere.workspace

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Rootfs path operations which never follow a symlink in an existing path component.
 *
 * Archive extraction and post-install patching both write below the same rootfs. Keeping
 * this boundary in one place prevents a crafted archive entry (or a damaged rootfs) from
 * turning mkdir/write/chmod into an operation on a path outside the rootfs.
 */
internal object RootfsPathSafety {
    fun ensureRootDirectory(root: File): File {
        val rootPath = root.toPath()
        require(!Files.isSymbolicLink(rootPath)) {
            "Rootfs directory must not be a symbolic link: ${root.path}"
        }
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            require(root.mkdirs() || Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                "Unable to create rootfs directory: ${root.path}"
            }
        }
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            "Rootfs path is not a directory: ${root.path}"
        }
        return root.canonicalFile
    }

    /**
     * Resolves a relative rootfs path after checking every existing component with
     * NOFOLLOW_LINKS. The final symlink is allowed only for callers which remove or replace
     * the link itself (currently resolv.conf); it is never dereferenced by this helper.
     */
    fun resolve(
        root: File,
        relativePath: String,
        allowFinalSymlink: Boolean = false,
    ): File {
        val rootDir = ensureRootDirectory(root)
        val normalized = normalizeRelativePath(relativePath)
        var current = rootDir
        val components = normalized.split('/')
        components.forEachIndexed { index, component ->
            val next = File(current, component)
            val isFinal = index == components.lastIndex
            if (Files.isSymbolicLink(next.toPath()) && !(allowFinalSymlink && isFinal)) {
                throw unsafeSymlink(next)
            }
            current = next
        }

        if (!Files.isSymbolicLink(current.toPath())) {
            val canonical = current.canonicalFile
            require(isInside(rootDir, canonical)) {
                "Rootfs path escapes target directory: $relativePath"
            }
            return canonical
        }

        // The caller requested a no-follow final-link operation. Returning the lexical path
        // lets Files.delete/remove it without touching its target.
        return current
    }

    fun ensureDirectory(root: File, relativePath: String): File {
        val rootDir = ensureRootDirectory(root)
        val normalized = relativePath
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?: return rootDir
        val components = normalizeRelativePath(normalized).split('/')
        var current = rootDir
        components.forEach { component ->
            val next = File(current, component)
            if (Files.isSymbolicLink(next.toPath())) {
                throw unsafeSymlink(next)
            }
            if (!Files.exists(next.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next.toPath())
                } catch (_: FileAlreadyExistsException) {
                    // Re-check below. A concurrent creator may have made a directory, but
                    // a symlink or regular file must still be rejected.
                }
            }
            require(!Files.isSymbolicLink(next.toPath())) {
                "Unsafe symlink in rootfs path: ${next.path}"
            }
            require(Files.isDirectory(next.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Rootfs path is not a directory: ${next.path}"
            }
            current = next
        }
        return current
    }

    fun ensureParentDirectory(root: File, relativePath: String) {
        val normalized = normalizeRelativePath(relativePath)
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        ensureDirectory(root, parent)
    }

    fun normalizeRelativePath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("./")
        require(normalized.isNotBlank()) { "Rootfs entry path is blank" }
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) {
            "Rootfs entry escapes target directory: $path"
        }
        return normalized
    }

    private fun unsafeSymlink(path: File): IllegalArgumentException =
        IllegalArgumentException("Unsafe symbolic link in rootfs path: ${path.path}")

    private fun isInside(root: File, target: File): Boolean =
        target.path == root.path || target.path.startsWith(root.path + File.separator)
}

internal fun File.resolveRootfsPathNoFollow(
    relativePath: String,
    allowFinalSymlink: Boolean = false,
): File = RootfsPathSafety.resolve(this, relativePath, allowFinalSymlink)

internal fun File.ensureRootfsDirectory(relativePath: String = ""): File =
    RootfsPathSafety.ensureDirectory(this, relativePath)

internal fun File.ensureRootfsParentDirectory(relativePath: String) =
    RootfsPathSafety.ensureParentDirectory(this, relativePath)
