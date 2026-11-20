package com.soulreturns.features.profileviewer.gui

import com.soulreturns.config.cfg
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.features.experiments.RngMeterStore
import com.soulreturns.features.fishing.FishingCounts
import com.soulreturns.features.fishing.FishingStatsStore
import com.soulreturns.features.mining.metaldetector.DetectorLootCounts
import com.soulreturns.features.mining.metaldetector.MetalDetectorLoot
import com.soulreturns.features.mining.nucleus.NucleusCounts
import com.soulreturns.features.mining.nucleus.NucleusHudSettings
import com.soulreturns.features.mining.nucleus.NucleusItems
import com.soulreturns.features.mining.nucleus.NucleusRuns
import com.soulreturns.features.profit.dragon.DragonProfitStats
import com.soulreturns.features.profit.dragon.DragonProfitStatsStore
import com.soulreturns.features.profit.dragon.DragonType
import com.soulreturns.features.profit.dragon.gui.DragonProfitHudSettings
import com.soulreturns.features.profileviewer.gui.kit.BarRow
import com.soulreturns.features.profileviewer.gui.kit.CardNode
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui
import java.math.BigDecimal
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The cards built from this computer's `stats.json` (mock `nucleusCard`, `fishingCard`, the Dragons,
 * Metal Detector and RNG meter cards of `soulSec`): [CardNode.LOCAL], shown only for yourself, reading
 * the bucket of the profile on screen (`ctx.profile.cuteName`), not only the one being played.
 *
 * Each card function appends its card and returns whether the bucket had data. With `empty = false`
 * (the Mining and Fishing sections, where the mock hides an unused tracker) nothing is appended then.
 */
object LocalSoulCards {
    /** One profile's buckets of the local trackers, plus the price/name lookups the cards sort by. */
    interface Source {
        fun nucleus(profile: String): NucleusCounts

        fun fishing(profile: String): FishingCounts

        fun dragons(profile: String): DragonProfitStats

        fun detector(profile: String): DetectorLootCounts

        /** False until the RNG Meter's table was read once. */
        fun rngRead(): Boolean

        fun rngSelected(profileId: String, profileName: String): RngMeterStore.SelectedDrop?

        /** One unit of a nucleus loot item at the Nucleus panel's price mode; 0 = no quote. */
        fun nucleusValue(name: String): Long

        fun dragonDropName(sourceId: String, itemId: String): String

        /** One unit of a dragon drop at the Dragons panel's price mode; 0 = no quote. */
        fun dragonDropValue(itemId: String): Long
    }

    /** The trackers' own stores; in-memory reads only. */
    object Stored : Source {
        override fun nucleus(profile: String) = NucleusRuns.handle.read(profile).total

        override fun fishing(profile: String) = FishingStatsStore.handle.read(profile).allTime

        override fun dragons(profile: String) = DragonProfitStatsStore.handle.read(profile)

        override fun detector(profile: String) = MetalDetectorLoot.handle.read(profile).total

        override fun rngRead() = RngMeterStore.known()

        override fun rngSelected(profileId: String, profileName: String) = RngMeterStore.selectedDrop(profileId, profileName)

        override fun nucleusValue(name: String) = NucleusItems.unitValue(name, NucleusHudSettings.priceMode)

        override fun dragonDropName(sourceId: String, itemId: String) = DropResolver.displayName(sourceId, itemId)

        override fun dragonDropValue(itemId: String) =
            PriceCache.sellValue(DropResolver.priceLookupId(itemId), DragonProfitHudSettings.priceMode, cfg.combat.dragons.useNpcPriceIfHigher)
    }

    /** Swapped by tests. */
    @Volatile
    internal var source: Source = Stored

    private const val LIST_MAX = 6
    private const val BARS_MAX = 8
    private const val HIST_BINS = 10

    /** The Overview Soul card's local rows (mock `soulOverview`), yourself only, each only with data. */
    fun overviewRows(ctx: ProfileContext): List<Pair<String, String>> {
        if (!ctx.isSelf) return emptyList()
        val s = source
        val p = ctx.profile.cuteName
        val out = ArrayList<Pair<String, String>>(3)
        val n = s.nucleus(p)
        if (n.runs > 0) out += "Crystal Nucleus" to "${Format.num(n.runs)} runs" + (if (n.bestRunMs > 0) " · best ${Format.dur(n.bestRunMs)}" else "")
        val f = s.fishing(p)
        if (f.catches > 0) out += "Sea creatures caught" to Format.num(f.catches)
        val d = s.dragons(p)
        if (!d.isEmpty()) out += "Dragons" to "${Format.num(d.summoned.kills.values.sum())} summoned · ${Format.num(d.lootshare.kills.values.sum())} lootshared"
        return out
    }

    fun nucleus(ui: Ui, ctx: ProfileContext, empty: Boolean = true): Boolean {
        if (!ctx.isSelf) return false
        val s = source
        val n = s.nucleus(ctx.profile.cuteName)
        val has = n.runs > 0
        if (!has && !empty) return false
        ui.card("Crystal Nucleus runs", kind = CardNode.LOCAL) {
            if (!has) {
                empty("pv_pick", "No runs recorded yet", "Soul times your Crystal Nucleus runs while the Nucleus tracker is on.")
                return@card
            }
            kvs(2) {
                kv("Runs", Format.num(n.runs))
                kv("Best", if (n.bestRunMs > 0) Format.dur(n.bestRunMs) else "—", KitStyle.MAXED)
                kv("Median", if (n.runTimes.isNotEmpty()) Format.dur(n.medianRunMs()) else "—")
                kv("Last", if (n.lastRunMs > 0) Format.dur(n.lastRunMs) else "—")
            }
            runHistogram(n.runTimes)?.let { h ->
                histogram(h.counts, "${h.fromMin} min", "${h.toMin}${if (h.overflow) "+" else ""} min", "Run times, 1-minute buckets from ${h.fromMin} to ${h.toMin} min")
            }
            val loot = n.loot.entries
                .filter { it.value > 0 && !NucleusItems.isPowder(it.key) }
                .map { Triple(it.key, it.value, s.nucleusValue(it.key)) }
                .sortedWith(compareByDescending<Triple<String, Long, Long>> { it.third }.thenByDescending { it.second }.thenBy { it.first })
                .take(LIST_MAX)
            if (loot.isNotEmpty()) kvs { for ((name, amount) in loot) kv(name, Format.num(amount)) }
        }
        return has
    }

    fun fishing(ui: Ui, ctx: ProfileContext, empty: Boolean = true): Boolean {
        if (!ctx.isSelf) return false
        val f = source.fishing(ctx.profile.cuteName)
        val has = !f.isEmpty()
        if (!has && !empty) return false
        ui.card("Fishing tracker", kind = CardNode.LOCAL) {
            if (!has) {
                empty("pv_fish", "No catches recorded yet", "Soul counts your sea creatures and double hooks while you fish.")
                return@card
            }
            kvs(2) {
                kv("Sea creatures", Format.num(f.catches))
                val pct = if (f.catches > 0) " §8(${Format.pct(f.doubleHooks.toDouble() / f.catches)})" else ""
                kv("Double hooks", "${Format.num(f.doubleHooks)}$pct")
                kv("Cocoons", Format.num(f.cocoons))
            }
            val top = f.catchesByCreature.entries.filter { it.value > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(BARS_MAX)
            val mx = top.firstOrNull()?.value ?: 0L
            if (mx > 0) bars(top.map { BarRow(it.key, Format.num(it.value), (it.value.toDouble() / mx).toFloat()) })
        }
        return has
    }

    fun dragons(ui: Ui, ctx: ProfileContext, empty: Boolean = true): Boolean {
        if (!ctx.isSelf) return false
        val s = source
        val d = s.dragons(ctx.profile.cuteName)
        val has = !d.isEmpty()
        if (!has && !empty) return false
        ui.card("Dragons", kind = CardNode.LOCAL) {
            if (!has) {
                empty("pv_flame", "No dragons recorded yet", "Soul counts your dragon kills, Summoning Eyes and loot in the Dragon's Nest.")
                return@card
            }
            kvs(2) {
                kv("Summoned", Format.num(d.summoned.kills.values.sum()))
                kv("Lootshared", Format.num(d.lootshare.kills.values.sum()))
                kv("Summoning Eyes placed", Format.num(d.summoned.eyes.values.sum() + d.lootshare.eyes.values.sum()))
            }
            val amounts = HashMap<String, Long>()
            val sources = HashMap<String, String>()
            for (part in listOf(d.summoned, d.lootshare)) {
                for ((type, drops) in part.drops) {
                    val sourceId = DragonType.entries.firstOrNull { it.name == type }?.sourceId ?: continue
                    for ((itemId, amount) in drops) {
                        if (amount <= 0) continue
                        amounts[itemId] = (amounts[itemId] ?: 0L) + amount
                        sources.putIfAbsent(itemId, sourceId)
                    }
                }
            }
            val top = amounts.entries
                .map { Triple(s.dragonDropName(sources[it.key]!!, it.key), it.value, s.dragonDropValue(it.key)) }
                .sortedWith(compareByDescending<Triple<String, Long, Long>> { it.third }.thenByDescending { it.second }.thenBy { it.first })
                .take(LIST_MAX)
            if (top.isNotEmpty()) kvs { for ((name, amount) in top) kv(name, Format.num(amount)) }
        }
        return has
    }

    fun detector(ui: Ui, ctx: ProfileContext, empty: Boolean = true): Boolean {
        if (!ctx.isSelf) return false
        val m = source.detector(ctx.profile.cuteName)
        val has = m.treasures > 0 || m.sets > 0
        if (!has && !empty) return false
        ui.card("Metal Detector", kind = CardNode.LOCAL) {
            if (!has) {
                empty("pv_pick", "No treasures found yet", "Soul counts your Metal Detector finds and tool sets in the Mines of Divan.")
                return@card
            }
            val timed = m.timedSets
            kvs(2) {
                kv("Full sets", Format.num(m.sets))
                kv("Average set", if (timed > 0) Format.dur(m.setTimeMs / timed) else "—")
                kv("Last set", if (m.lastSetMs > 0) Format.dur(m.lastSetMs) else "—")
                kv("Treasures", Format.num(m.treasures))
            }
        }
        return has
    }

    fun rng(ui: Ui, ctx: ProfileContext, empty: Boolean = true): Boolean {
        if (!ctx.isSelf) return false
        val s = source
        val sel = s.rngSelected(ctx.profile.profileId, ctx.profile.cuteName)
        if (sel == null && !empty) return false
        ui.card("Experimentation RNG meter", kind = CardNode.LOCAL) {
            if (sel == null) {
                if (s.rngRead()) {
                    empty("pv_swirl", "No drop selected", "Select a drop in /rng › Experimentation Table; Soul reads it when you open the meter.")
                } else {
                    empty("pv_swirl", "Meter not read yet", "Open /rng › Experimentation Table once and Soul remembers your selected drop.")
                }
                return@card
            }
            kvs {
                kv("Selected drop", sel.name, Rich.mcColor('d') ?: KitStyle.TEXT)
                kv("Base odds", BigDecimal.valueOf(sel.odds).stripTrailingZeros().toPlainString() + "%")
            }
            if (sel.readAt > 0) foot("Read from the meter ${Format.ago(ctx.nowMs - sel.readAt)}.")
        }
        return sel != null
    }

    /** Run times in 1-minute buckets from the fastest run's minute; at most [HIST_BINS], slower runs in the last. */
    internal class RunHistogram(val counts: List<Double>, val fromMin: Int, val toMin: Int, val overflow: Boolean)

    internal fun runHistogram(times: List<Long>): RunHistogram? {
        if (times.isEmpty()) return null
        val lo = floor(times.min() / 60_000.0).toInt()
        val top = maxOf(lo + 1, ceil(times.max() / 60_000.0).toInt())
        val hi = minOf(top, lo + HIST_BINS)
        val counts = DoubleArray(hi - lo)
        for (t in times) counts[(floor(t / 60_000.0).toInt() - lo).coerceIn(0, counts.size - 1)] += 1.0
        return RunHistogram(counts.toList(), lo, hi, top > hi)
    }
}
