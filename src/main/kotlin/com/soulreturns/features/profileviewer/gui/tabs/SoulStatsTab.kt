package com.soulreturns.features.profileviewer.gui.tabs

import com.soulreturns.features.profileviewer.gui.ProfileCanvas
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileTab
import com.soulreturns.features.mining.mineshaft.MineshaftStatsSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Statistics **Soul** collected, as opposed to anything Hypixel publishes — the namespaced payload of
 * `GET /player/{uuid}/soul-stats`.
 *
 * ### It is absent, not empty, until there is something in it
 * [visible] is false whenever `soulStats` is null, which
 * [com.soulreturns.data.hypixel.SoulStatsClient] returns for every reason the endpoint can come back
 * with nothing: the target has not opted in (403), the backend has never heard of them (404), rate
 * limited (429), unreachable, or simply nothing recorded for them yet. The first namespace,
 * `mineshaft`, arrived with port doc 04, and the tab turned itself on the moment the backend started
 * answering with one — no client change was needed, which was the point of building it hidden.
 *
 * A player who did not opt in has done nothing wrong, so none of those reasons produces an error, a
 * greyed-out tab or a hint that there was something to see.
 *
 * ### Unknown namespaces render, they are never rejected
 * Namespaces are additive on the backend and a mod release is not coordinated with one. So a
 * namespace this build has never heard of is rendered **generically** from its scalar fields rather
 * than dropped, and a known one only gets a nicer title. Never validate the map against a closed set.
 */
object SoulStatsTab : ProfileTab {
    override val id = "soul"
    override val label = "Soul"

    /**
     * Display titles for namespaces this build knows about. Absence is not an error — see the class
     * KDoc — it only means the raw key is used as the title.
     */
    private val KNOWN_TITLES = mapOf(
        "mineshaft" to "Mineshaft",
        "fishing" to "Fishing",
        "dragons" to "Dragons",
    )

    /**
     * Namespaces that bring their own drawing. The renderer lives **with its feature**, not here, so
     * a new tracker adds a file in its own package and one line to this map rather than growing this
     * tab a branch per namespace.
     *
     * A renderer returns false when it cannot make sense of the payload, and the generic scalar
     * renderer takes over — so a backend that restructures a namespace ahead of a mod release
     * degrades instead of showing nothing.
     */
    private val RENDERERS: Map<String, (ProfileCanvas, JsonObject) -> Boolean> = mapOf(
        "mineshaft" to MineshaftStatsSection::draw,
    )

    override fun visible(ctx: ProfileContext): Boolean = ctx.soulStats?.namespaces?.isNotEmpty() == true

    override fun draw(c: ProfileCanvas, ctx: ProfileContext) {
        val envelope = ctx.soulStats ?: return

        for ((key, payload) in envelope.namespaces) {
            if (RENDERERS[key]?.invoke(c, payload) == true) continue
            c.section(KNOWN_TITLES[key] ?: prettify(key)) {
                val rows = scalarRows(payload)
                if (rows.isEmpty()) {
                    note("Nothing recorded yet.")
                    return@section
                }
                for ((label, value) in rows) statRow(label, value)
            }
        }

        c.section("About these numbers") {
            note(
                if (ctx.self) {
                    "Soul collected these about your own gameplay. Other players see them only while " +
                        "\"Share my Soul stats\" is on in /soul → Profile Viewer."
                } else {
                    "${ctx.identity.name} chose to share these. They are collected by Soul, not by " +
                        "Hypixel, and nobody can see them unless the player turns sharing on."
                },
            )
        }
    }

    /**
     * Flatten a namespace payload to label/value rows, one nesting level deep. Objects and arrays
     * below that are summarised by size rather than expanded: a namespace that grows a nested
     * structure gets a proper renderer in the lane that ships it, and until then "12 entries" is
     * honest where a wall of raw JSON would not be.
     */
    private fun scalarRows(payload: JsonObject, prefix: String = ""): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((key, value) in payload) {
            val label = if (prefix.isEmpty()) prettify(key) else "$prefix · ${prettify(key)}"
            when (value) {
                is JsonPrimitive -> out.add(label to formatPrimitive(value))
                is JsonObject ->
                    if (prefix.isEmpty()) {
                        out.addAll(scalarRows(value, prettify(key)))
                    } else {
                        out.add(label to "${value.size} field${if (value.size == 1) "" else "s"}")
                    }
                is JsonArray -> out.add(label to "${value.size} entr${if (value.size == 1) "y" else "ies"}")
            }
        }
        return out
    }

    private fun formatPrimitive(value: JsonPrimitive): String {
        value.booleanOrNull?.let { return if (it) "yes" else "no" }
        value.longOrNull?.let { return groupThousands(it) }
        value.doubleOrNull?.let { return String.format(Locale.ROOT, "%.2f", it) }
        return value.contentOrNull ?: "—"
    }

    /** `1 234 567` — a thin space, because a comma or a dot means different things per locale. */
    private fun groupThousands(value: Long): String =
        String.format(Locale.ROOT, "%,d", value).replace(',', ' ')

    /** `corpses_found` → `Corpses found`. */
    private fun prettify(key: String): String =
        key.replace('_', ' ').replace('-', ' ').trim()
            .replaceFirstChar { it.uppercase(Locale.ROOT) }
            .ifEmpty { key }
}
