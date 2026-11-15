package com.soulreturns.platform.updater

import net.fabricmc.loader.api.SemanticVersion

/**
 * Soul release versions: `x.y.z` (stable) and `x.y.z-<prerelease>` (beta), compared with Fabric's
 * own [SemanticVersion].
 *
 * Fabric's order is the one that matters: with two `soul` jars in the mods folder the loader picks
 * the higher one by exactly this comparison, and the backend's `latest` uses the same SemVer 2.0
 * order. A hand-rolled "natural" order would disagree with both, so the updater could offer a jar
 * Fabric never loads, or delete the one it would.
 *
 * SemVer compares alphanumeric prerelease identifiers lexically: `0.2.0-beta10 < 0.2.0-beta9`. Past
 * beta 9, tag `-beta.10` (a dot-separated numeric identifier compares numerically); `make bump`
 * refuses the ambiguous form.
 */
object ReleaseVersions {
    /** Strict SemVer parse; null for anything else (wildcards, `Unknown`, garbage). */
    fun parse(text: String): SemanticVersion? =
        try {
            SemanticVersion.parse(text.trim()).takeIf { !it.hasWildcard() }
        } catch (_: Exception) {
            null
        }

    /** True when [candidate] is strictly newer than [current]; false when either does not parse. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        return a > b
    }

    /** `beta` for a version with a prerelease part, else `stable` (the backend's channel rule). */
    fun channelOf(version: SemanticVersion): String = if (version.prereleaseKey.isPresent) BETA else STABLE

    const val STABLE = "stable"
    const val BETA = "beta"
}
