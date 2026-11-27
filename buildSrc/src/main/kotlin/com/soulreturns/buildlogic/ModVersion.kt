package com.soulreturns.buildlogic

/**
 * The version a build is named, see `.agents/rules/build-run.md` ("Build version").
 *
 * `mod_version` in gradle.properties is the LAST RELEASE (`make bump` writes the exact tag version
 * there). A build that is not a release is named after the next version instead, so it never
 * claims to be a release it merely contains:
 *
 * - `0.3.3`        -> `0.3.4-beta.0.dev`   (SemVer: above 0.3.3, below 0.3.4-beta.1 and 0.3.4)
 * - `0.3.4-beta.2` -> `0.3.4-beta.2.dev`   (above beta.2, below beta.3)
 *
 * A release build passes `-Pmod_version=<tag version>` and gets exactly that.
 */
object ModVersion {
    private val release = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""")
    const val DEV = "dev"

    /** [explicit] (a `-Pmod_version` given to the build) wins as is; otherwise the dev name derived from [declared]. */
    fun resolve(declared: String, explicit: String?): String =
        explicit?.trim()?.takeIf { it.isNotEmpty() } ?: dev(declared)

    /** The name of a non-release build made on top of the release [lastRelease] (`x.y.z` or `x.y.z-<prerelease>`). */
    fun dev(lastRelease: String): String {
        val m = release.matchEntire(lastRelease.trim())
            ?: error("mod_version '$lastRelease' is not x.y.z or x.y.z-<prerelease>")
        val (major, minor, patch, pre) = m.destructured
        return when {
            pre.isEmpty() -> "$major.$minor.${patch.toInt() + 1}-beta.0.$DEV"
            pre.split('.').last() == DEV -> lastRelease.trim()
            else -> "$major.$minor.$patch-$pre.$DEV"
        }
    }
}
