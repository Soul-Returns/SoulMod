package com.soulreturns.features.profileviewer.gui

import com.soulreturns.features.profileviewer.gui.kit.CardNode
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.Ui
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * What every card showing Soul-collected data shares: its card kind, its "shared by" caption, the
 * honest empty states, and the generic renderer for a soul-stats namespace this build has no card
 * for. A feature's own card (`MineshaftStatsSection`) uses these so all Soul cards read alike.
 */
object SoulCards {
    /** [CardNode.MINE] for your own shareable data, [CardNode.SHARED] for someone else's. */
    fun kind(ctx: ProfileContext): Int = if (ctx.isSelf) CardNode.MINE else CardNode.SHARED

    /** The card caption naming who shared it (`· shared by Name`), null for yourself. */
    fun sharedBy(ctx: ProfileContext): String? = if (ctx.isSelf) null else "· shared by ${ctx.name}"

    /**
     * The empty state for [ProfileContext.soulState]: yourself with nothing recorded / private (403)
     * / not a Soul user (404) / shares but nothing yet / could not load. [compact] for small cards.
     */
    fun empty(ui: Ui, ctx: ProfileContext, compact: Boolean, maxW: Int = Int.MAX_VALUE) {
        val n = ctx.name
        val s = ctx.soulState
        if (s == ProfileViewerState.SOUL_SELF_EMPTY) {
            ui.empty("pv_soul", "Nothing recorded yet", "Soul fills this in while you play: mineshafts, the Crystal Nucleus, fishing and dragons.", maxW)
        } else if (s == ProfileViewerState.SOUL_PRIVATE) {
            ui.empty("pv_lock", "$n keeps their Soul stats private", if (compact) "Nothing to show here." else "Soul users choose whether others see what Soul records for them.", maxW)
        } else if (s == ProfileViewerState.SOUL_NOT_A_USER) {
            ui.empty(
                "pv_soul",
                "$n doesn't use Soul",
                if (compact) "Soul records what Hypixel doesn't track, for players who use it." else "Soul records things Hypixel doesn't track (like mineshaft corpse odds). They show up here for players who use Soul and share them.",
                maxW,
            )
        } else if (s == ProfileViewerState.SOUL_SHARED_EMPTY) {
            ui.empty("pv_soul", "Nothing recorded yet", "$n shares their Soul stats, but Soul hasn't recorded anything for them yet.", maxW)
        } else {
            ui.empty("pv_offline", "Soul stats didn't load", "Soul's server didn't answer. Refresh to try again.", maxW)
        }
    }

    /** Display titles for namespaces without their own card; absence only means the key is prettified. */
    val KNOWN_TITLES = mapOf(
        "mineshaft" to "Glacite Mineshafts",
        "fishing" to "Fishing",
        "dragons" to "Dragons",
        "nucleus" to "Crystal Nucleus",
    )

    /**
     * A namespace this build has no designed card for, rendered from its fields (`.c.generic`).
     * Namespaces are additive on the backend and never coordinated with a mod release, so an unknown
     * one is shown, never dropped.
     */
    fun generic(ui: Ui, ctx: ProfileContext, key: String, payload: JsonObject) {
        val rows = scalarRows(payload)
        ui.card(KNOWN_TITLES[key] ?: prettify(key), kind = CardNode.GENERIC, sub = "a namespace this build has no renderer for") {
            if (rows.isEmpty()) {
                text("Nothing recorded yet.", color = com.soulreturns.features.profileviewer.gui.kit.KitStyle.TEXT_DIM)
            } else {
                kvs { for ((label, value) in rows) kv(label, value) }
            }
            foot("Shown with the generic renderer until a release adds a designed card.")
        }
    }

    /**
     * A payload flattened to label/value rows one level deep; deeper objects and arrays are counted
     * ("12 entries") rather than expanded.
     */
    fun scalarRows(payload: JsonObject, prefix: String = ""): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((key, value) in payload) {
            val label = if (prefix.isEmpty()) prettify(key) else "$prefix · ${prettify(key)}"
            if (value is JsonPrimitive) {
                out += label to primitive(value)
            } else if (value is JsonObject) {
                if (prefix.isEmpty()) out += scalarRows(value, prettify(key)) else out += label to "${value.size} field${if (value.size == 1) "" else "s"}"
            } else if (value is JsonArray) {
                out += label to "${value.size} entr${if (value.size == 1) "y" else "ies"}"
            }
        }
        return out
    }

    private fun primitive(value: JsonPrimitive): String {
        value.booleanOrNull?.let { return if (it) "yes" else "no" }
        value.longOrNull?.let { return Format.num(it) }
        value.doubleOrNull?.let { return String.format(Locale.ROOT, "%.2f", it) }
        return value.contentOrNull ?: "—"
    }

    /** `corpses_found` → `Corpses found`. */
    fun prettify(key: String): String =
        key.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase(Locale.ROOT) }.ifEmpty { key }
}
