package com.soulreturns.features.mining.mineshaft

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.SoulCards
import com.soulreturns.features.profileviewer.gui.kit.Cell
import com.soulreturns.features.profileviewer.gui.kit.Col
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.TableRow
import com.soulreturns.features.profileviewer.gui.kit.Ui
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The Profile Viewer's card for the `mineshaft` soul-stats namespace (mock `mineshaftCard`).
 *
 * It lives with the feature that produces the data: the viewer renders any namespace generically and
 * a feature that wants better brings its own card. [card] returns false when it cannot read the
 * payload, and the caller falls back to [SoulCards.generic] — the contract is additive-only, but
 * "should not happen" is not a rendering strategy.
 *
 * **Per profile.** A segmented control switches between the profile shown in the viewer and every
 * profile; a backend without the per-profile split shows the total alone.
 */
object MineshaftStatsSection {
    /** Mineshaft types listed before "Show all" in the full card. */
    private const val TYPES_SHOWN = 6

    /**
     * @param full the Soul section's full breakdown ("Glacite Mineshafts", full width, both tables);
     *   false = the Mining section's summary ("Mineshaft odds", two columns, corpse table only).
     */
    fun card(ui: Ui, ctx: ProfileContext, payload: JsonObject, full: Boolean): Boolean {
        val total = MineshaftStats.from(payload) ?: return false
        if (total.totalVisits == 0 && !total.splitsProfiles()) return false
        val own = total.forProfile(ctx.profile.profileId)
        val scope = if (own == null) "all" else ui.value("msScope", "profile")
        val m = if (scope == "profile" && own != null) own else total

        ui.card(
            if (full) "Glacite Mineshafts" else "Mineshaft odds",
            kind = SoulCards.kind(ctx),
            sub = SoulCards.sharedBy(ctx),
            span = if (full) Node.FULL else 2,
            tools = if (own == null) null else ({ seg("msScope", "profile", listOf("profile" to ctx.profile.cuteName, "all" to "All profiles")) }),
        ) {
            if (m.totalVisits == 0) {
                empty("pv_pick", "No visits recorded", if (scope == "profile") "Nothing on ${ctx.profile.cuteName} yet. Switch to All profiles for the rest." else "")
                return@card
            }
            kvs(cols = 2) {
                kv("Visits", Format.num(m.totalVisits))
                kv("Time inside", Format.hm(m.totalDurationSec))
                kv("Average visit", Format.hm(m.averageDurationSec.toLong()))
                kv("Littlefoot", "${Format.num(m.littlefootFound)} §8(${Format.pct(m.littlefootFindRate)})", color = if (m.littlefootFound > 0) KitStyle.ACCENT_LIGHT else KitStyle.TEXT)
                val found = m.bySource[VisitSource.DISCOVERED]?.visits ?: 0
                val warped = m.bySource[VisitSource.WARPED]?.visits ?: 0
                if (m.bySource.isNotEmpty()) {
                    kv("Self-discovered", "${Format.num(found)} §8(${share(found, m.totalVisits)})", tip = "Only these describe your own odds: a mineshaft you were warped into was someone else's roll.")
                    kv("Warped in", "${Format.num(warped)} §8(${share(warped, m.totalVisits)})")
                }
            }
            grid(260) {
                stack(0) { corpseTable(this, m) }
                if (full) {
                    stack(4) {
                        typeTable(this, m, if (flag("msAll")) Int.MAX_VALUE else TYPES_SHOWN)
                        val types = m.byType.size
                        if (types > TYPES_SHOWN) link(if (flag("msAll")) "Show fewer" else "Show all $types mineshaft types") { toggleFlag("msAll") }
                    }
                }
            }
        }
        return true
    }

    private fun corpseTable(ui: Ui, m: MineshaftStats) {
        if (m.byCorpse.isEmpty()) return
        ui.table(
            listOf(Col("Corpse"), Col("Spawns", tip = "Visits where it spawned"), Col("Avg found", tip = "How many when it spawns"), Col("Looted"), Col("Found")),
            m.byCorpse.entries.sortedByDescending { it.value.spawnRate }.map { (type, c) ->
                TableRow(
                    listOf(
                        Cell.of(pretty(type), bold = true),
                        Cell.of(Format.pct(c.spawnRate)),
                        Cell.of(String.format(Locale.ROOT, "%.2f", c.avgFoundWhenSpawned)),
                        Cell.of(Format.pct(c.lootRate, 0)),
                        Cell.of(Format.num(c.found)),
                    ),
                )
            },
        )
    }

    private fun typeTable(ui: Ui, m: MineshaftStats, limit: Int) {
        if (m.byType.isEmpty()) return
        ui.table(
            listOf(Col("Mineshaft"), Col("Visits"), Col("Littlefoot"), Col("Vanguard"), Col("Avg time")),
            m.byType.entries.sortedByDescending { it.value.visits }.take(limit).map { (type, b) ->
                TableRow(
                    listOf(
                        Cell.of(pretty(type)),
                        Cell.of(Format.num(b.visits)),
                        Cell.of(Format.pct(b.littlefootRate)),
                        Cell.of(Format.pct(b.corpses["VANGUARD"]?.spawnRate ?: 0.0)),
                        Cell.of(Format.hm(b.averageDurationSec.toLong())),
                    ),
                )
            },
        )
    }

    private fun share(n: Int, of: Int): String = Format.pct(if (of > 0) n.toDouble() / of else 0.0)

    /** `TOPAZ_1` → `Topaz 1`, `VANGUARD` → `Vanguard`. Raw ids: the backend sends no display names. */
    private fun pretty(id: String): String = id.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }
}
