package com.soulreturns.platform.updater

import net.fabricmc.loader.api.Version
import net.fabricmc.loader.api.metadata.version.VersionPredicate

/** One reason a jar would not load next to the installed mods. */
data class DependencyProblem(
    val id: String,
    /** The predicate(s) as written in `fabric.mod.json`, joined with " or ". */
    val requirement: String,
    /** The installed version, or null when the mod is not installed. */
    val installed: String?,
    /** True for a `breaks` entry that matches an installed mod. */
    val breaks: Boolean = false,
) {
    /** Player-facing: "needs Fabric API >=0.160.0 (installed: 0.153.0+26.2)". */
    fun describe(): String {
        val name = DependencyGuard.displayName(id)
        if (breaks) return "does not work with $name $installed"
        if (installed == null) return "needs $name $requirement (not installed)"
        return "needs $name $requirement (installed: $installed)"
    }
}

/**
 * Checks a jar's `depends`/`breaks` against the running loader, with Fabric's own
 * [VersionPredicate], before the jar goes into the mods folder.
 *
 * Fabric would otherwise decide at the next launch, and its answer for an unsatisfiable newer
 * `soul` jar is to silently start the older one (research doc §2.2): the player would see "restart
 * to update", restart, and still run the old version. Checking here turns that into a message naming
 * what to update.
 *
 * A dependency the jar nests itself (`jars`, e.g. `hypixel-mod-api`) counts as satisfied by the
 * nested version as well as by an installed one, like the loader's solver. `java` and `minecraft`
 * are Fabric builtin mods, so the same lookup covers them.
 */
object DependencyGuard {
    private val NAMES = mapOf(
        "minecraft" to "Minecraft",
        "java" to "Java",
        "fabricloader" to "Fabric Loader",
        "fabric-api" to "Fabric API",
        "fabric-language-kotlin" to "Fabric Language Kotlin",
        "hypixel-mod-api" to "Hypixel Mod API",
    )

    fun displayName(id: String): String = NAMES[id] ?: id

    /**
     * @param installed version of the installed mod with that id (Fabric `provides` included), or
     *   null when none is loaded.
     * @return every problem; empty = the jar will load.
     */
    fun check(meta: JarMetadata, installed: (String) -> Version?): List<DependencyProblem> {
        val problems = ArrayList<DependencyProblem>()
        for ((id, predicates) in meta.depends) {
            val candidates = listOfNotNull(
                installed(id),
                meta.nested[id]?.let { parseVersion(it) },
            )
            val ok = candidates.any { v -> matchesAny(predicates, v) }
            if (!ok) problems += DependencyProblem(id, predicates.joinToString(" or "), installed(id)?.friendlyString)
        }
        for ((id, predicates) in meta.breaks) {
            val v = installed(id) ?: continue
            if (matchesAny(predicates, v)) problems += DependencyProblem(id, predicates.joinToString(" or "), v.friendlyString, breaks = true)
        }
        return problems
    }

    /** One predicate string against a version; an unparseable predicate never matches. */
    fun matches(predicate: String, version: Version): Boolean =
        try {
            VersionPredicate.parse(predicate).test(version)
        } catch (_: Exception) {
            false
        }

    /** A Fabric version (semantic or plain string), or null when even that fails. */
    fun parseVersion(text: String): Version? =
        try {
            Version.parse(text)
        } catch (_: Exception) {
            null
        }

    /** An empty list carries no constraint this check can evaluate; only presence counts. */
    private fun matchesAny(predicates: List<String>, version: Version): Boolean =
        predicates.isEmpty() || predicates.any { matches(it, version) }
}
