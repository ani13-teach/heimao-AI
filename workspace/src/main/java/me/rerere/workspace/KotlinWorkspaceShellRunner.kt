package me.rerere.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

fun interface WorkspacePythonExecutor {
    fun execute(context: WorkspaceShellContext, args: List<String>, stdin: ByteArray?, timeoutMillis: Long): WorkspaceCommandResult
}

/** Small, bounded shell grammar. Never invokes a host shell or a package manager. */
class KotlinWorkspaceShellRunner(private val pythonExecutor: WorkspacePythonExecutor) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult = Session(context).run()

    private data class Token(val value: String, val operator: Boolean = false)
    private data class Command(val words: List<String>, val redirects: List<Pair<String, String>>)
    private data class Pipeline(val commands: List<Command>, val condition: String)
    private class Limit : RuntimeException()
    private class Deadline : RuntimeException()

    private inner class Session(private val context: WorkspaceShellContext) {
        private val deadline = System.nanoTime() + context.timeoutMillis.coerceIn(1, WorkspaceManager.MAX_COMMAND_TIMEOUT_MS) * 1_000_000
        private var cwd = "/workspace" + context.cwd.trim('/').let { if (it.isEmpty()) "" else "/$it" }
        private var truncated = false
        private val roots = listOf(
            "/workspace" to context.filesDir,
            "/root" to (context.homeDir ?: context.filesDir),
            "/home" to (context.homeDir ?: context.filesDir),
            "/tmp" to (context.guestTempDir ?: context.tempDir),
        )
        private fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Workspace command interrupted")
            if (System.nanoTime() >= deadline) throw Deadline()
        }
        private fun remaining() = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        private fun bounded(text: String): String {
            if (text.length > MAX_OUTPUT_CHARS) truncated = true
            return text.take(MAX_OUTPUT_CHARS)
        }
        private fun virtual(path: String): String {
            require(!path.contains('\\') && !path.contains('\u0000')) { "Invalid path" }
            val raw = if (path.startsWith('/')) path else "$cwd/$path"
            val parts = mutableListOf<String>()
            raw.split('/').forEach {
                when (it) {
                    "", "." -> Unit
                    ".." -> { require(parts.isNotEmpty()) { "Path escapes workspace" }; parts.removeAt(parts.lastIndex) }
                    else -> parts += it
                }
            }
            return "/" + parts.joinToString("/")
        }
        private fun file(path: String): File {
            check()
            val name = virtual(path)
            val (prefix, root) = roots.firstOrNull { name == it.first || name.startsWith(it.first + "/") }
                ?: error("Path is outside the portable workspace: $path")
            return RootfsPathSafety.resolve(root, name.removePrefix(prefix).trimStart('/').ifEmpty { "." })
        }
        private fun mutableFile(path: String): File {
            val f = file(path)
            require(roots.none { f.canonicalFile == it.second.canonicalFile }) { "Cannot modify a runtime root" }
            return f
        }
        private fun read(file: File): ByteArray {
            require(file.isFile) { "Not a file: ${file.name}" }
            return file.inputStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw Limit()
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        private fun write(path: String, bytes: ByteArray, append: Boolean = false) {
            require(bytes.size <= MAX_BYTES) { "Input limit exceeded" }
            val f = mutableFile(path)
            require(f.parentFile?.isDirectory == true) { "Parent directory does not exist" }
            f.outputStreamWithAppend(append).use { it.write(bytes) }
        }
        private fun File.outputStreamWithAppend(append: Boolean) = java.io.FileOutputStream(this, append)

        fun run(): WorkspaceCommandResult {
            var code = 0
            var timedOut = false
            var pending = false
            val out = StringBuilder()
            val err = StringBuilder()
            fun collect(builder: StringBuilder, text: String) {
                val n = minOf(text.length, MAX_OUTPUT_CHARS - builder.length)
                builder.append(text, 0, n)
                if (n < text.length) truncated = true
            }
            try {
                require(context.command.length <= MAX_OUTPUT_CHARS) { "Command limit exceeded" }
                require((context.stdin?.size ?: 0) <= MAX_BYTES) { "Input limit exceeded" }
                val plan = parse(tokenize(context.command))
                for (pipeline in plan) {
                    check()
                    if (pipeline.condition == "&&" && code != 0) continue
                    var input = context.stdin
                    for (command in pipeline.commands) {
                        check()
                        val outputs = command.redirects.filter { it.first != "<" }
                        val result = try {
                            command.redirects.forEach { (op, target) ->
                                if (op == "<") input = read(file(target)) else write(target, byteArrayOf(), op == ">>")
                            }
                            execute(command.words, input)
                        } catch (e: IllegalArgumentException) {
                            WorkspaceCommandResult(1, "", (e.message ?: "Invalid argument") + "\n")
                        } catch (e: java.io.IOException) {
                            WorkspaceCommandResult(1, "", (e.message ?: "File operation failed") + "\n")
                        } catch (e: IllegalStateException) {
                            WorkspaceCommandResult(1, "", (e.message ?: "Command failed") + "\n")
                        }
                        code = result.exitCode
                        truncated = truncated || result.truncated
                        timedOut = timedOut || result.timedOut
                        pending = pending || result.terminationPending
                        if (!timedOut && !pending) check()
                        collect(err, result.stderr)
                        var text = bounded(result.stdout)
                        outputs.lastOrNull()?.let { (op, target) ->
                            if (result.stdout.isNotEmpty()) write(target, text.toByteArray(), op == ">>")
                        }
                        if (outputs.isNotEmpty()) text = ""
                        input = text.toByteArray()
                        if (timedOut || pending) break
                    }
                    collect(out, input?.toString(Charsets.UTF_8).orEmpty())
                    if (timedOut || pending) break
                }
            } catch (_: Deadline) {
                timedOut = true; code = -1
            } catch (_: Limit) {
                truncated = true; code = 1; collect(err, "Workspace data limit exceeded\n")
            } catch (e: IllegalArgumentException) {
                code = 2; collect(err, (e.message ?: "Invalid shell syntax") + "\n")
            } catch (e: IllegalStateException) {
                code = 1; collect(err, (e.message ?: "Command failed") + "\n")
            } catch (e: java.io.IOException) {
                code = 1; collect(err, (e.message ?: "File operation failed") + "\n")
            }
            return WorkspaceCommandResult(code, out.toString(), err.toString(), timedOut, truncated, pending)
        }

        private fun execute(words: List<String>, input: ByteArray?): WorkspaceCommandResult {
            check()
            val args = words.drop(1).map { expand(it) }
            val name = expand(words.first())
            if (name == "python" || name == "python3") {
                return pythonExecutor.execute(context.copy(cwd = cwd, workingDir = file(cwd), stdin = input), args, input, remaining())
            }
            val flags = args.takeWhile { it.startsWith('-') && it != "-" }
            val operands = args.drop(flags.size)
            fun data(paths: List<String> = operands): String {
                if (paths.isEmpty()) return input?.toString(Charsets.UTF_8).orEmpty()
                val out = StringBuilder()
                paths.forEach { path ->
                    check()
                    val s = if (path == "-") input?.toString(Charsets.UTF_8).orEmpty() else read(file(path)).toString(Charsets.UTF_8)
                    if (out.length + s.length > MAX_BYTES) throw Limit()
                    out.append(s)
                }
                return out.toString()
            }
            fun lines(text: String): List<String> = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
            fun joined(lines: List<String>): String {
                val builder = StringBuilder()
                for (line in lines) {
                    check()
                    if (builder.length + line.length + 1 > MAX_OUTPUT_CHARS) {
                        truncated = true
                        builder.append((line + "\n").take(MAX_OUTPUT_CHARS - builder.length))
                        break
                    }
                    builder.append(line).append('\n')
                }
                return builder.toString()
            }
            var code = 0
            val output = when (name) {
                "true" -> ""
                "false" -> { code = 1; "" }
                "pwd" -> "$cwd\n"
                "cd" -> { require(args.size <= 1); val dest = args.firstOrNull() ?: "/root"; require(file(dest).isDirectory) { "Not a directory" }; cwd = virtual(dest); "" }
                "echo" -> args.drop(if (args.firstOrNull() == "-n") 1 else 0).joinToString(" ") + if (args.firstOrNull() == "-n") "" else "\n"
                "printf" -> format(args)
                "cat" -> data()
                "ls" -> {
                    val entries = mutableListOf<String>()
                    (operands.ifEmpty { listOf(".") }).forEach { path ->
                        val f = file(path)
                        require(f.exists()) { "Path does not exist: $path" }
                        if (f.isDirectory) Files.newDirectoryStream(f.toPath()).use { stream ->
                            stream.forEach { p -> check(); if (flags.any { 'a' in it } || !p.fileName.toString().startsWith('.')) entries += p.fileName.toString(); if (entries.size > MAX_ENTRIES) throw Limit() }
                        } else entries += f.name
                    }
                    joined(entries.sorted())
                }
                "mkdir" -> { operands.forEach { path -> val f = mutableFile(path); if ("-p" in flags) Files.createDirectories(f.toPath()) else Files.createDirectory(f.toPath()) }; "" }
                "touch" -> { operands.forEach { path -> val f = mutableFile(path); if (!f.exists()) Files.createFile(f.toPath()); check(f.setLastModified(System.currentTimeMillis())) }; "" }
                "rm" -> { operands.forEach { path -> val f = mutableFile(path); if (!Files.exists(f.toPath(), NOFOLLOW_LINKS)) require(flags.any { 'f' in it }) { "Path does not exist" } else remove(f, flags.any { 'r' in it || 'R' in it }) }; "" }
                "cp", "mv" -> {
                    require(operands.size >= 2) { "Source and destination required" }
                    val target = operands.last()
                    operands.dropLast(1).forEach { source ->
                        val src = mutableFile(source)
                        val destination = if (file(target).isDirectory) "$target/${src.name}" else target
                        val dest = mutableFile(destination)
                        require(!dest.toPath().startsWith(src.toPath())) { "Destination is inside source" }
                        if (name == "mv") Files.move(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        else copy(src, dest, flags.any { 'r' in it || 'R' in it })
                    }; ""
                }
                "head", "tail" -> {
                    val count = if (args.firstOrNull() == "-n") args.getOrNull(1)?.toIntOrNull() ?: error("Invalid count") else 10
                    require(count in 0..MAX_ENTRIES)
                    val paths = if (args.firstOrNull() == "-n") args.drop(2) else operands
                    val all = lines(data(paths)); joined(if (name == "head") all.take(count) else all.takeLast(count))
                }
                "wc" -> {
                    val text = data(); val values = mutableListOf<String>()
                    if (flags.isEmpty() || flags.any { 'l' in it }) values += text.count { it == '\n' }.toString()
                    if (flags.isEmpty() || flags.any { 'w' in it }) values += text.split(Regex("\\s+")).count { it.isNotEmpty() }.toString()
                    if (flags.isEmpty() || flags.any { 'c' in it }) values += text.toByteArray().size.toString()
                    values.joinToString(" ") + "\n"
                }
                "grep" -> {
                    require(operands.isNotEmpty()) { "Pattern required" }
                    val pattern = operands.first()
                    require(pattern.length <= 256) { "Pattern limit exceeded" }
                    require(flags.any { 'F' in it } || pattern.none { it in "()*+?{}|\\" }) { "Complex regex is unsupported; use grep -F for literal text" }
                    val regex = if (flags.any { 'F' in it }) Regex(Regex.escape(pattern), if (flags.any { 'i' in it }) setOf(RegexOption.IGNORE_CASE) else emptySet()) else Regex(pattern, if (flags.any { 'i' in it }) setOf(RegexOption.IGNORE_CASE) else emptySet())
                    val matching = lines(data(operands.drop(1))).mapIndexedNotNull { i, line -> check(); if (regex.containsMatchIn(line) != flags.any { 'v' in it }) (if (flags.any { 'n' in it }) "${i + 1}:" else "") + line else null }
                    if (matching.isEmpty()) code = 1
                    if (flags.any { 'c' in it }) "${matching.size}\n" else joined(matching)
                }
                "sort" -> { val sorted = lines(data()).sorted(); joined(if (flags.any { 'r' in it }) sorted.reversed() else sorted) }
                "uniq" -> { val result = mutableListOf<String>(); var last: String? = null; var count = 0
                    fun flush() { last?.let { result += if (flags.any { 'c' in it }) "$count $it" else it } }
                    lines(data()).forEach { check(); if (it == last) count++ else { flush(); last = it; count = 1 } }; flush(); joined(result) }
                "cut" -> {
                    val d = args.indexOf("-d").let { if (it >= 0) args.getOrNull(it + 1) ?: error("Delimiter required") else "\t" }
                    val index = args.indexOf("-f"); require(index >= 0) { "cut requires -f" }
                    val fields = args.getOrNull(index + 1)?.split(',')?.map { it.toInt() - 1 } ?: error("Fields required")
                    require(fields.all { it >= 0 }); val used = mutableSetOf(index, index + 1); args.indexOf("-d").takeIf { it >= 0 }?.let { used += it; used += it + 1 }
                    joined(lines(data(args.filterIndexed { i, _ -> i !in used })).map { line -> val cols = line.split(d); fields.mapNotNull { cols.getOrNull(it) }.joinToString(d) })
                }
                "tee" -> { val text = input?.toString(Charsets.UTF_8).orEmpty(); operands.forEach { write(it, text.toByteArray(), "-a" in flags) }; text }
                "find" -> {
                    val start = args.firstOrNull()?.takeUnless { it.startsWith('-') } ?: "."
                    val nameIndex = args.indexOf("-name"); val glob = if (nameIndex >= 0) args.getOrNull(nameIndex + 1) ?: error("Name required") else null
                    val typeIndex = args.indexOf("-type"); val type = if (typeIndex >= 0) args.getOrNull(typeIndex + 1) else null
                    val matcher = glob?.let { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$it") }
                    val result = mutableListOf<String>()
                    fun visit(path: String) {
                        check(); val f = file(path)
                        if ((matcher == null || matcher.matches(f.toPath().fileName)) && (type == null || (type == "d" && f.isDirectory) || (type == "f" && f.isFile))) result += path
                        if (result.size > MAX_ENTRIES) throw Limit()
                        if (f.isDirectory) Files.newDirectoryStream(f.toPath()).use { children -> children.forEach { child -> if (!Files.isSymbolicLink(child)) visit(path.trimEnd('/') + "/" + child.fileName) } }
                    }
                    visit(start); joined(result)
                }
                else -> return WorkspaceCommandResult(127, "", "$name: command not found\n")
            }
            return WorkspaceCommandResult(code, bounded(output), "", truncated = truncated)
        }

        private fun copy(source: File, dest: File, recursive: Boolean) {
            check(); require(!Files.isSymbolicLink(source.toPath()) && !Files.isSymbolicLink(dest.toPath())) { "Symbolic links are not supported by cp" }
            if (source.isDirectory) {
                require(recursive) { "Directory requires -r" }; Files.createDirectories(dest.toPath())
                Files.newDirectoryStream(source.toPath()).use { children -> children.forEach { copy(it.toFile(), File(dest, it.fileName.toString()), true) } }
            } else { val bytes = read(source); dest.outputStream().use { it.write(bytes) } }
        }
        private fun remove(f: File, recursive: Boolean) {
            check()
            if (Files.isDirectory(f.toPath(), NOFOLLOW_LINKS)) {
                require(recursive) { "Directory requires -r" }
                Files.newDirectoryStream(f.toPath()).use { children -> children.forEach { remove(it.toFile(), true) } }
            }
            Files.delete(f.toPath())
        }
        private fun format(args: List<String>): String {
            require(args.isNotEmpty()) { "printf requires a format" }
            val fmt = args.first().replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r").replace("\\\\", "\\")
            require(!Regex("%[^%sd]").containsMatchIn(fmt)) { "Unsupported printf format" }
            var i = 1
            return Regex("%%|%s|%d").replace(fmt) { match ->
                when (match.value) { "%%" -> "%"; "%d" -> (args.getOrNull(i++)?.toLongOrNull() ?: 0).toString(); else -> args.getOrNull(i++).orEmpty() }
            }
        }
        // Variables are expanded while tokenizing, except within single quotes.
        private fun expand(word: String) = word
        private fun tokenize(source: String): List<Token> {
            val tokens = mutableListOf<Token>(); val word = StringBuilder(); var active = false; var quote: Char? = null; var i = 0
            fun flush() { if (active) { tokens += Token(word.toString()); word.clear(); active = false } }
            while (i < source.length) {
                check(); val c = source[i++]
                if (quote == '\'') { if (c == '\'') quote = null else word.append(c); continue }
                if (c == '\\') { require(i < source.length) { "Incomplete escape" }; active = true; word.append(source[i++]); continue }
                require(c != '`' && !(c == '$' && source.getOrNull(i) == '(')) { "Command substitution is unsupported" }
                if (c == '"') { quote = if (quote == '"') null else '"'; active = true; continue }
                if (quote == null && c == '\'') { quote = c; active = true; continue }
                if (c == '$') {
                    val start = i; while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    val variable = source.substring(start, i)
                    word.append(when (variable) { "HOME" -> "/root"; "TMPDIR", "TMP" -> "/tmp"; "PWD" -> cwd; else -> error("Unsupported variable: $variable") }); active = true; continue
                }
                if (quote != null) { word.append(c); active = true; continue }
                if (c.isWhitespace()) { flush(); if (c == '\n' && tokens.lastOrNull()?.operator == false) tokens += Token(";", true); continue }
                if (c in "|><&;") {
                    flush(); var op = c.toString()
                    if ((c == '>' || c == '&') && source.getOrNull(i) == c) { op += c; i++ }
                    require(op != "&") { "Background execution is unsupported" }
                    tokens += Token(op, true); continue
                }
                require(c !in "(){}") { "Unsupported shell syntax: $c" }
                word.append(c); active = true
            }
            require(quote == null) { "Unclosed quote" }; flush(); return tokens
        }
        private fun parse(tokens: List<Token>): List<Pipeline> {
            val plans = mutableListOf<Pipeline>(); val commands = mutableListOf<Command>(); val words = mutableListOf<String>(); val redirects = mutableListOf<Pair<String, String>>()
            var condition = ";"; var i = 0
            fun command() { require(words.isNotEmpty()) { "Command required" }; commands += Command(words.toList(), redirects.toList()); words.clear(); redirects.clear() }
            fun pipeline() { command(); plans += Pipeline(commands.toList(), condition); commands.clear() }
            while (i < tokens.size) {
                val token = tokens[i++]
                if (!token.operator) { words += token.value; continue }
                when (token.value) {
                    ">", ">>", "<" -> { val path = tokens.getOrNull(i++); require(path != null && !path.operator) { "Redirection requires a path" }; redirects += token.value to path.value }
                    "|" -> command()
                    "&&", ";" -> { pipeline(); condition = token.value }
                    else -> error("Unsupported shell operator")
                }
            }
            if (words.isNotEmpty()) pipeline() else require(commands.isEmpty() && condition != "&&") { "Incomplete command" }
            require(plans.isNotEmpty()) { "Command required" }; return plans
        }
    }
    private companion object { const val MAX_BYTES = 512 * 1024; const val MAX_ENTRIES = 10000 }
}
