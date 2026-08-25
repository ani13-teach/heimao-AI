package me.rerere.rikkahub.data.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Shared lexical and no-follow checks for ZIP/TAR entries. */
object WorkspaceArchivePathSafety {
    fun normalize(path: String): String {
        require(path.isNotBlank()) { "Archive path is blank" }
        require(!path.contains('\u0000')) { "Archive path contains NUL" }
        val value = path.replace('\\', '/')
        require(!value.startsWith('/')) { "Absolute archive path is not allowed: $path" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(value)) {
            "Drive-qualified archive path is not allowed: $path"
        }
        val parts = value.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe archive path: $path"
        }
        return parts.joinToString("/")
    }

    fun resolve(root: File, path: String): File {
        val normalized = normalize(path)
        val rootPath = ensureDirectory(root).toPath()
        val target = rootPath.resolve(normalized).normalize()
        require(target.startsWith(rootPath)) { "Archive path escapes staging root: $path" }
        ensureNoSymlinkAncestors(rootPath, target, includeTarget = false)
        return target.toFile()
    }

    fun ensureDirectory(root: File): File {
        val path = root.toPath()
        require(!Files.isSymbolicLink(path)) { "Staging root must not be a symbolic link" }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(root.mkdirs() || Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                "Unable to create staging root: ${root.path}"
            }
        }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Staging root is not a directory: ${root.path}"
        }
        return root
    }

    fun ensureNoSymlinkAncestors(
        root: File,
        target: File,
        includeTarget: Boolean = false,
    ) = ensureNoSymlinkAncestors(root.toPath(), target.toPath(), includeTarget)

    private fun ensureNoSymlinkAncestors(
        root: java.nio.file.Path,
        target: java.nio.file.Path,
        includeTarget: Boolean,
    ) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        require(normalizedTarget.startsWith(normalizedRoot)) {
            "Archive path escapes staging root: $target"
        }
        var current = normalizedRoot
        val relative = normalizedRoot.relativize(normalizedTarget)
        val components = relative.nameCount - if (includeTarget) 0 else 1
        for (index in 0 until components.coerceAtLeast(0)) {
            current = current.resolve(relative.getName(index))
            require(!Files.isSymbolicLink(current)) {
                "Symbolic-link ancestor escapes staging root: $current"
            }
        }
        if (includeTarget && Files.isSymbolicLink(normalizedTarget)) {
            throw IllegalArgumentException("Archive target must not already be a symbolic link")
        }
    }

    /** Resolves a TAR link target inside [root] without creating a host-absolute link. */
    fun resolveLinkTarget(root: File, entryPath: String, linkName: String): java.nio.file.Path {
        require(linkName.isNotBlank()) { "Archive link target is blank" }
        require(!linkName.contains('\u0000')) { "Archive link target contains NUL" }
        val rootPath = ensureDirectory(root).toPath().toAbsolutePath().normalize()
        val normalizedLink = linkName.replace('\\', '/')
        require(!Regex("^[A-Za-z]:").containsMatchIn(normalizedLink)) {
            "Drive-qualified archive link target is not allowed"
        }
        val entry = rootPath.resolve(normalize(entryPath)).normalize()
        val parent = entry.parent ?: rootPath
        val candidate = if (normalizedLink.startsWith('/')) {
            rootPath.resolve(normalizedLink.trimStart('/')).normalize()
        } else {
            parent.resolve(normalizedLink).normalize()
        }
        require(candidate.startsWith(rootPath)) {
            "Archive link escapes staging root: $entryPath -> $linkName"
        }
        ensureNoSymlinkAncestors(rootPath, candidate, includeTarget = false)
        return candidate
    }
}
