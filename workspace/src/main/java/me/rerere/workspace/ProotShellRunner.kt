package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (context.accessMode == WorkspaceShellAccessMode.WORKSPACE_ONLY) {
            unsupportedWorkspaceOnlyCommand(context.command)?.let { command ->
                return WorkspaceCommandResult(
                    exitCode = 127,
                    stdout = "",
                    stderr = "Command '$command' is not available in sandbox mode. Switch to package mode for a full Linux Rootfs.\n",
                )
            }
        }
        val usesFullRootfs = context.accessMode == WorkspaceShellAccessMode.FULL_ROOTFS ||
            context.accessMode == WorkspaceShellAccessMode.WORKSPACE_PACKAGE
        if (usesFullRootfs) {
            val missing = context.linuxDir.missingFullRootfsPaths()
            if (missing.isNotEmpty()) {
                return WorkspaceCommandResult(
                    exitCode = 127,
                    stdout = "",
                    stderr = fullRootfsIncompleteMessage(missing),
                )
            }
        } else if (!context.linuxDir.hasUsableShellRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        if (context.accessMode == WorkspaceShellAccessMode.WORKSPACE_ONLY) {
            File(context.filesDir, AI_TEMP_DIR).mkdirs()
            ensureCommandDispatchers(context.tempDir)
        }
        if (usesFullRootfs) {
            ensureGuestDirectories(context)
            // A shared rootfs belongs to every workspace, so an ordinary execution must never
            // patch it; only a private rootfs is patched here.
            if (context.patchRootfs) {
                patcher.patch(context.linuxDir, context.rootfsPatchOptions)
            }
        }
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
                if (context.accessMode == WorkspaceShellAccessMode.WORKSPACE_ONLY ||
                    context.accessMode == WorkspaceShellAccessMode.WORKSPACE_PACKAGE ||
                    // A shared rootfs belongs to every workspace, so an ordinary execution must
                    // not let PRoot write its link2symlink placeholders into the tree either.
                    !context.patchRootfs
                ) {
                    environment()["PROOT_DONT_POLLUTE_ROOTFS"] = "1"
                } else {
                    // Do not let an inherited setting change the manual FULL_ROOTFS terminal.
                    environment().remove("PROOT_DONT_POLLUTE_ROOTFS")
                }
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /**
     * Builds the same FULL_ROOTFS prefix used by [buildCommand] for the interactive terminal.
     * The terminal supplies its own PTY, so this intentionally stops at an interactive bash
     * instead of adding the command-mode `-c` tail.
     */
    fun buildFullRootfsInteractiveCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        require(context.accessMode == WorkspaceShellAccessMode.FULL_ROOTFS) {
            "Interactive terminal requires FULL_ROOTFS"
        }
        return buildRootfsCommandPrefix(context, proot) + fullRootfsShellCommand()
    }

    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val workspaceOnly = context.accessMode == WorkspaceShellAccessMode.WORKSPACE_ONLY
        val packageMode = context.accessMode == WorkspaceShellAccessMode.WORKSPACE_PACKAGE
        val command = buildRootfsCommandPrefix(context, proot)
        when {
            workspaceOnly -> command += listOf(
                "/usr/bin/env",
                "-i",
                "HOME=$WORKSPACE_DIR",
                "TMPDIR=$WORKSPACE_DIR/$AI_TEMP_DIR",
                "PATH=/usr/bin:/bin",
                "ENV=/dev/null",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "/bin/sh",
            )
            packageMode -> command += packageRootfsShellCommand()
            else -> command += fullRootfsShellCommand()
        }
        command += listOf(
            "-c",
            // Pass the command as a positional argument so shell text is evaluated exactly once.
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    private fun buildRootfsCommandPrefix(
        context: WorkspaceShellContext,
        proot: File,
    ): MutableList<String> {
        val workspaceOnly = context.accessMode == WorkspaceShellAccessMode.WORKSPACE_ONLY
        val command = mutableListOf(proot.absolutePath)
        if (!workspaceOnly) {
            command += "--root-id"
        }
        command += listOf(
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            if (workspaceOnly) context.filesDir.absolutePath else context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        if (workspaceOnly) {
            AI_REQUIRED_RUNTIME_MOUNTS.forEach { (source, target) ->
                command += "-b"
                command += "$source:$target!"
            }
            AI_TOYBOX_COMMANDS.forEach { commandName ->
                command += "-b"
                command += "/system/bin/toybox:$AI_APPLET_DIR/$commandName!"
                command += "-b"
                command += "${commandDispatcher(context.tempDir, commandName).absolutePath}:/bin/$commandName!"
            }
            command += "-b"
            command += "${commandDispatcher(context.tempDir, "env").absolutePath}:/usr/bin/env!"
            if (File(AI_AWK_BINARY).isFile) {
                command += "-b"
                command += "$AI_AWK_BINARY:$AI_AWK_GUEST_BINARY!"
                command += "-b"
                command += "${commandDispatcher(context.tempDir, "awk").absolutePath}:/bin/awk!"
            }
            AI_OPTIONAL_RUNTIME_MOUNTS.forEach { (sourcePath, target) ->
                val source = File(sourcePath)
                if (source.exists()) {
                    command += "-b"
                    command += "${source.absolutePath}:$target!"
                }
            }
            val guestTemp = File(context.filesDir, AI_TEMP_DIR)
            command += "-b"
            command += "${guestTemp.absolutePath}:/tmp!"
            AI_SAFE_DEVICE_PATHS.forEach { path ->
                val source = File(path)
                if (source.exists()) {
                    command += "-b"
                    command += "${source.absolutePath}:$path!"
                }
            }
        } else if (context.accessMode == WorkspaceShellAccessMode.WORKSPACE_PACKAGE) {
            // Package mode deliberately ignores configured app mounts and kernel filesystems.
            // Use /dev/null as a file mask: unlike an app-owned empty directory, its source
            // cannot be populated by guest root or expose directory contents. PRoot's `!`
            // suffix prevents guest symlink dereference at each masked target.
            PACKAGE_MASK_TARGETS.forEach { target ->
                command += "-b"
                command += "/dev/null:$target!"
            }
            AI_SAFE_DEVICE_PATHS.forEach { path ->
                val source = File(path)
                if (source.exists()) {
                    command += "-b"
                    command += "${source.absolutePath}:$path!"
                }
            }
        } else {
            context.bindMounts.forEach { mount ->
                if (mount.source.exists()) {
                    command += "-b"
                    command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
                }
            }

            WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
                if (File(path).exists()) {
                    command += "-b"
                    command += path
                }
            }
        }

        // Both full Linux modes (FULL_ROOTFS and WORKSPACE_PACKAGE) bind the workspace-local HOME
        // and temp areas on top of the rootfs, so a shared rootfs keeps per-workspace state.
        if (!workspaceOnly) {
            context.homeDir?.let {
                command += "-b"
                command += "${it.absolutePath}:$GUEST_HOME_DIR"
            }
            context.guestTempDir?.let {
                command += "-b"
                command += "${it.absolutePath}:$GUEST_TMP_DIR"
            }
            context.guestVarTempDir?.let {
                command += "-b"
                command += "${it.absolutePath}:$GUEST_VAR_TMP_DIR"
            }
        }
        return command
    }

    private fun ensureGuestDirectories(context: WorkspaceShellContext) {
        context.homeDir?.mkdirs()
        context.guestTempDir?.mkdirs()
        context.guestVarTempDir?.mkdirs()
    }

    private fun packageRootfsShellCommand(): List<String> = listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=/bin/bash",
        "DEBIAN_FRONTEND=noninteractive",
        "/bin/bash",
        "-l",
    )

    private fun fullRootfsShellCommand(): List<String> = listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=/bin/bash",
        "/bin/bash",
        "-l",
    )

    private fun ensureCommandDispatchers(tempDir: File) {
        AI_TOYBOX_COMMANDS.forEach { commandName ->
            ensureCommandDispatcher(
                file = commandDispatcher(tempDir, commandName),
                command = "$AI_APPLET_DIR/$commandName",
            )
        }
        if (File(AI_AWK_BINARY).isFile) {
            ensureCommandDispatcher(
                file = commandDispatcher(tempDir, "awk"),
                command = AI_AWK_GUEST_BINARY,
            )
        }
    }

    private fun ensureCommandDispatcher(file: File, command: String) {
        val content = "#!/bin/sh\nexec $command \"\$@\"\n"
        synchronized(DISPATCHER_LOCK) {
            file.parentFile?.mkdirs()
            if (!file.isFile || file.readText() != content) {
                file.setWritable(true, true)
                file.writeText(content)
            }
            check(file.setReadable(true, true)) { "Unable to make dispatcher readable" }
            check(file.setExecutable(true, true)) { "Unable to make dispatcher executable" }
            file.setWritable(false, false)
        }
    }

    private fun commandDispatcher(tempDir: File, commandName: String): File =
        File(tempDir, "$AI_RUNTIME_DIR/$commandName.sh")

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun unsupportedWorkspaceOnlyCommand(command: String): String? {
        val match = Regex("(?:^|[\\s;&|()])((?:python|python3|pip|pip3|bash))(?:\\s|$)")
            .find(command)
        return match?.groupValues?.getOrNull(1)
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val AI_TEMP_DIR = ".rikkahub/tmp"
        private const val AI_RUNTIME_DIR = "workspace-only-runtime"
        private val PACKAGE_MASK_TARGETS = listOf(
            "/skills",
            "/upload",
            "/tool_outputs",
        )
        private const val AI_AWK_BINARY = "/system/bin/awk"
        private const val AI_APPLET_DIR = "/bin/.rikkahub-applets"
        private const val AI_AWK_GUEST_BINARY = "$AI_APPLET_DIR/awk"
        private val DISPATCHER_LOCK = Any()

        // Bind only the shell directly. Each allowed toybox applet gets a distinct hidden
        // absolute path, so Android's linker works without exposing /proc or a multicall entry.
        private val AI_REQUIRED_RUNTIME_MOUNTS = listOf(
            "/system/bin/sh" to "/bin/sh",
        )
        private val AI_OPTIONAL_RUNTIME_MOUNTS = listOf(
            "/system/bin/linker" to "/system/bin/linker",
            "/system/bin/linker64" to "/system/bin/linker64",
            "/system/bin/mksh" to "/system/bin/mksh",
            "/system/etc" to "/system/etc",
            "/system/lib" to "/system/lib",
            "/system/lib64" to "/system/lib64",
            "/apex/com.android.runtime/lib" to "/apex/com.android.runtime/lib",
            "/apex/com.android.runtime/lib64" to "/apex/com.android.runtime/lib64",
            "/linkerconfig" to "/linkerconfig",
        )
        private val AI_TOYBOX_COMMANDS = listOf(
            "base64",
            "basename",
            "cat",
            "chmod",
            "cksum",
            "cmp",
            "comm",
            "cp",
            "cut",
            "date",
            "dd",
            "df",
            "diff",
            "dirname",
            "du",
            "echo",
            "env",
            "expand",
            "expr",
            "false",
            "find",
            "fmt",
            "grep",
            "head",
            "id",
            "ln",
            "ls",
            "md5sum",
            "mkdir",
            "mktemp",
            "mv",
            "nl",
            "od",
            "paste",
            "printf",
            "pwd",
            "readlink",
            "realpath",
            "rev",
            "rm",
            "rmdir",
            "sed",
            "seq",
            "sha1sum",
            "sha256sum",
            "sha512sum",
            "sleep",
            "sort",
            "split",
            "stat",
            "strings",
            "tac",
            "tail",
            "tar",
            "tee",
            "test",
            "timeout",
            "touch",
            "tr",
            "true",
            "truncate",
            "uniq",
            "unlink",
            "wc",
            "which",
            "xargs",
            "xxd",
            "yes",
        )
        private val AI_SAFE_DEVICE_PATHS = listOf(
            "/dev/null",
            "/dev/random",
            "/dev/urandom",
            "/dev/zero",
        )
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        private const val GUEST_HOME_DIR = "/root"
        private const val GUEST_TMP_DIR = "/tmp"
        private const val GUEST_VAR_TMP_DIR = "/var/tmp"
    }
}
