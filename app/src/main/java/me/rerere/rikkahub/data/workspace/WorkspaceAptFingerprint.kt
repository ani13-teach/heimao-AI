package me.rerere.rikkahub.data.workspace

import java.io.File
import java.security.MessageDigest

/** Fingerprint the installed database and apt inputs without recording their contents or credentials. */
internal fun sharedAptFingerprint(rootfs: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) = digest.update((value + "\n").toByteArray(Charsets.UTF_8))
    fun content(file: File) {
        add(rootfs.toPath().relativize(file.toPath()).toString().replace('\\', '/'))
        if (!file.isFile) {
            add("missing")
            return
        }
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
    }
    content(File(rootfs, "var/lib/dpkg/status"))
    content(File(rootfs, "etc/apt/sources.list"))
    listOf("etc/apt/sources.list.d", "etc/apt/apt.conf.d").forEach { dir ->
        File(rootfs, dir).listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach(::content)
    }
    File(rootfs, "var/lib/apt/lists").listFiles()?.filter { it.isFile }?.sortedBy { it.name }
        ?.forEach { add("${it.name}:${it.length()}:${it.lastModified()}") }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
