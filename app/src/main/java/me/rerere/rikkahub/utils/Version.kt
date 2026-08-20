package me.rerere.rikkahub.utils

/**
 * Minimal SemVer implementation used by version comparison tests.
 *
 * Supports `major.minor.patch` cores of different lengths (missing parts
 * count as 0), optional `-prerelease` identifiers and `+build` metadata.
 * Precedence follows the SemVer spec: numeric identifiers compare
 * numerically, numeric < alphanumeric, a shorter identifier list is lower
 * when it is a prefix, and build metadata is ignored.
 */
data class Version(val value: String) : Comparable<Version> {

    private val core: List<Int>
    private val prerelease: List<String>
    private val hasPrerelease: Boolean

    init {
        val withoutBuild = value.substringBefore('+')
        val parts = withoutBuild.split('-', limit = 2)
        core = parts[0].split('.').map { it.toIntOrNull() ?: 0 }
        val pre = parts.getOrNull(1)
        hasPrerelease = pre != null
        prerelease = pre?.split('.')?.filter { it.isNotEmpty() } ?: emptyList()
    }

    override fun compareTo(other: Version): Int {
        val coreLength = maxOf(core.size, other.core.size)
        for (i in 0 until coreLength) {
            val cmp = (core.getOrNull(i) ?: 0).compareTo(other.core.getOrNull(i) ?: 0)
            if (cmp != 0) return cmp
        }
        // Release (no prerelease) has higher precedence than prerelease.
        if (hasPrerelease != other.hasPrerelease) {
            return if (hasPrerelease) -1 else 1
        }
        if (!hasPrerelease) return 0
        val preLength = maxOf(prerelease.size, other.prerelease.size)
        for (i in 0 until preLength) {
            val a = prerelease.getOrNull(i)
            val b = other.prerelease.getOrNull(i)
            if (a == null) return -1
            if (b == null) return 1
            val aNum = a.toIntOrNull()
            val bNum = b.toIntOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1 // numeric < alphanumeric
                bNum != null -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        fun compare(a: String, b: String): Int = Version(a).compareTo(Version(b))
    }
}

operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)

operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
