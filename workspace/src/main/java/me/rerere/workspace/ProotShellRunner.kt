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
        if (!context.linuxDir.hasUsableRootfs()) {
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
        }
        if (context.accessMode == WorkspaceShellAccessMode.FULL_ROOTFS) {
            patcher.patch(context.linuxDir)
        }
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
                if (context.accessMode == WorkspaceShellAccessMode.WORKSPACE_ONLY) {
                    environment()["PROOT_DONT_POLLUTE_ROOTFS"] = "1"
                }
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
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

        if (workspaceOnly) {
            command += listOf(
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
        } else {
            command += listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "TMPDIR=/tmp",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "/bin/bash",
                "-l",
            )
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

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val AI_TEMP_DIR = ".rikkahub/tmp"

        // Android system partitions provide basic commands without exposing the mutable private Rootfs.
        private val AI_REQUIRED_RUNTIME_MOUNTS by lazy {
            buildList {
                add("/system/bin/sh" to "/bin/sh")
                AI_TOYBOX_COMMANDS.forEach { command ->
                    add("/system/bin/toybox" to "/bin/$command")
                }
                add("/system/bin/toybox" to "/usr/bin/env")
            }
        }
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
            "awk",
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
            "fold",
            "grep",
            "head",
            "hexdump",
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
    }
}
