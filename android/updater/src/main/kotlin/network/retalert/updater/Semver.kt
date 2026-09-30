package network.retalert.updater

/** Parsed semantic version with optional dot-separated prerelease identifiers.
 *  Handles tags like `v0.1.0-rc2`, `0.1.0`, `v0.2.0-rc1`. Pure, unit-testable. */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
) : Comparable<Semver> {

    /** True when the tag carried a prerelease marker (anything after `-`). */
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: Semver): Int {
        // Core version decides first.
        var c = major.compareTo(other.major); if (c != 0) return c
        c = minor.compareTo(other.minor); if (c != 0) return c
        c = patch.compareTo(other.patch); if (c != 0) return c
        // Equal core: no-prerelease > prerelease.
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1   // this is a release, other is prerelease
        if (other.prerelease.isEmpty()) return -1
        return comparePrerelease(prerelease, other.prerelease)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (prerelease.isNotEmpty()) "-" + prerelease.joinToString(".") else ""

    companion object {
        /** Parse `v0.1.0-rc2` -> Semver(0,1,0,["rc2"]). Strips a leading `v`/`V`. */
        fun parse(tag: String): Semver {
            val raw = tag.trim().removePrefix("v").removePrefix("V")
            val (core, pre) = raw.split("-", limit = 2)
                .let { if (it.size == 1) it[0] to emptyList() else it[0] to it[1].split(".").filter(String::isNotEmpty) }
            val parts = core.split(".").map { it.trim().toIntOrNull() ?: 0 }
            return Semver(
                major = parts.getOrElse(0) { 0 },
                minor = parts.getOrElse(1) { 0 },
                patch = parts.getOrElse(2) { 0 },
                prerelease = pre,
            )
        }

        /** Semver precedence for prerelease identifier lists (spec §11). */
        internal fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a[i]; val y = b[i]
                val xn = x.toIntOrNull(); val yn = y.toIntOrNull()
                val c = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1        // numeric < non-numeric
                    yn != null -> 1
                    else -> x.compareTo(y)  // lexical ASCII
                }
                if (c != 0) return c
            }
            return a.size.compareTo(b.size)  // larger set of equal prefix is higher
        }
    }
}