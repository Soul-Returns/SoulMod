package com.soulreturns.config.gui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Keeps `/soul` → About → Used software (`UsedSoftware`) from going stale. A failure here is fixed
 * by adding or correcting a row in `UsedSoftware.entries` (and its notice in
 * `assets/soul/licenses/THIRD-PARTY-NOTICES.md`), in the same commit as the change that caused it.
 */
class UsedSoftwareTest {
    private val resources = File("src/main/resources")
    private val entries = UsedSoftware.entries
    private val covers: Set<String> = entries.flatMap { it.covers }.toSet()

    /** `group:artifact` of every dependency declaration in build.gradle.kts that can reach the jar or the runtime. */
    private val gradleDeps: Set<String> by lazy {
        val declaration = Regex("""^\s*(?:implementation|api|include|compileOnly|modImplementation|modApi|runtimeOnly)\("([^":]+):([^":]+):""")
        File("build.gradle.kts").readLines().mapNotNull { declaration.find(it)?.let { m -> "${m.groupValues[1]}:${m.groupValues[2]}" } }.filter { !it.startsWith("com.mojang:") }.toSet()
    }

    private val modDepends: Set<String> by lazy {
        val json = Json.parseToJsonElement(File(resources, "fabric.mod.json").readText()).jsonObject
        json.getValue("depends").jsonObject.keys - setOf("minecraft", "java")
    }

    private val resourceFiles: List<String> by lazy {
        resources.walkTopDown().filter { it.isFile }.map { it.relativeTo(resources).invariantSeparatorsPath }.sorted().toList()
    }

    /** The font family a font file belongs to (`Nunito-Regular.ttf`, `nunito_semibold.json`, `nunito_msdf.png` → `nunito`), else null. */
    private fun familyOf(path: String): String? {
        val name = path.substringAfterLast('/')
        return when {
            path.startsWith("assets/soul/fonts/") && name.endsWith(".ttf") -> name.substringBefore('-').lowercase()
            (path.startsWith("assets/soul/msdf/") && name.endsWith(".json")) || (path.startsWith("assets/soul/textures/font/") && name.endsWith(".png")) -> name.substringBefore('_').lowercase()
            else -> null
        }
    }

    @Test
    fun `every dependency the jar bundles or needs has a row`() {
        val missing = gradleDeps.filter { "lib:$it" !in covers } + modDepends.filter { "mod:$it" !in covers }
        assertTrue(missing.isEmpty(), "No Used software row covers: $missing. Add an Entry to UsedSoftware.entries with covers = listOf(\"lib:<group>:<artifact>\" / \"mod:<id>\").")
    }

    @Test
    fun `every bundled resource file is covered by a row or listed as own work`() {
        val accounted = covers.filter { it.startsWith("res:") }.map { it.removePrefix("res:") } + UsedSoftware.ownResources
        val loose = resourceFiles.filter { f -> accounted.none { f.startsWith(it) } && familyOf(f)?.let { "font:$it" in covers } != true }
        assertTrue(loose.isEmpty(), "Resources with no Used software row (and not own work): $loose. Add an Entry covering them (res:<path>), or list them in UsedSoftware.ownResources if they are the project's own.")
    }

    @Test
    fun `every bundled font family has a row`() {
        val families = resourceFiles.mapNotNull { familyOf(it) }.toSortedSet()
        val missing = families.filter { "font:$it" !in covers }
        assertTrue(missing.isEmpty(), "Font families without a row: $missing. Add an Entry (license: the font's, usually SIL OFL 1.1) with covers = listOf(\"font:<family>\") and the OFL text in assets/soul/fonts/.")
    }

    @Test
    fun `rows do not point at things that are gone`() {
        val stale = covers.filter { c ->
            val v = c.substringAfter(':')
            when (c.substringBefore(':')) {
                "mod" -> v !in modDepends
                "lib" -> v !in gradleDeps
                "res" -> resourceFiles.none { it.startsWith(v) }
                "font" -> resourceFiles.none { f -> f.substringAfterLast('/').lowercase().let { it.startsWith("$v-") || it.startsWith("${v}_") } }
                else -> true
            }
        }.filter { it !in resourceFiles }
        assertTrue(stale.isEmpty(), "Used software rows cover something that no longer exists: $stale. Remove the row or the cover.")
    }

    @Test
    fun `rows are well formed and the notices file names every bundled one`() {
        val names = entries.map { it.name }
        assertTrue(names.size == names.toSet().size, "Duplicate row names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
        for (e in entries) {
            assertTrue(e.url.startsWith("https://"), "${e.name}: url must be https")
            assertTrue(e.license != null || e.kind == UsedSoftware.Kind.SERVICE || e.kind == UsedSoftware.Kind.REFERENCE || e.name == "SBCombo", "${e.name}: bundled or required software needs a license (SBCombo is retained by the maintainer's decision)")
            if (e.kind == UsedSoftware.Kind.REFERENCE) assertTrue(e.covers.isEmpty(), "${e.name}: a reference-only row must not cover bundled content")
        }
        val notices = File(resources, "assets/soul/licenses/THIRD-PARTY-NOTICES.md").readText()
        val unnamed = entries.filter { it.kind != UsedSoftware.Kind.SERVICE && it.kind != UsedSoftware.Kind.REQUIRED && !notices.contains(it.name) }.map { it.name }
        assertTrue(unnamed.isEmpty(), "THIRD-PARTY-NOTICES.md does not mention: $unnamed. Add the notice the license asks for.")
    }

    @Test
    fun `a ported-from comment names a project that has a row`() {
        val known = entries.flatMap { listOf(it.name) + it.aliases }.map { it.lowercase() }.toSet() + UsedSoftware.internalSources
        val phrase = Regex("""(?i)\b(?:ported|copied|lifted|adapted)\s+(?:(?:verbatim|1:1|in spirit)\s+)?from\s+(?:the\s+)?\[?([A-Za-z][A-Za-z0-9.-]*)""")
        // Plain English that follows "copied from": a source, an entry, it...
        val filler = setOf("a", "an", "another", "one", "it", "this", "that", "some", "any", "each", "source", "sources", "their", "its")
        val unknown = sortedMapOf<String, String>()
        File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "fsh", "vsh") && it.name != "UsedSoftware.kt" }.forEach { f ->
            for (m in phrase.findAll(f.readText())) {
                val who = m.groupValues[1].trimEnd('.', '-')
                if (who.lowercase() !in known && who.lowercase() !in filler) unknown.putIfAbsent(who, f.path)
            }
        }
        assertTrue(unknown.isEmpty(), "Source says it was ported/copied/adapted from a project with no Used software row: $unknown. Add the row (UsedSoftware.entries), its notice, and check the license is GPL-3.0-or-later compatible; or reword if nothing was taken.")
    }
}
