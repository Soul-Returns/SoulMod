package com.soulreturns.features.mining.mineshaft

import com.soulreturns.features.dev.DevOutput
import java.util.Locale
import kotlin.math.floor

/**
 * `/soul dev corpsespots`: the learned corpse spots ([CorpseSpotMemory]) per layout, then the same
 * in `corpse_spots.json`'s form (crystal layouts under `crystal`, blocks floored) to hand over for
 * bundling. Learned spots are own sightings of stands on spots the bundled data does not list.
 */
object CorpseSpotsDebug {
    fun dump() {
        DevOutput.begin("/soul dev corpsespots")
        val learned = CorpseSpotMemory.all().layouts.filterValues { it.isNotEmpty() }.toSortedMap()
        if (learned.isEmpty()) {
            DevOutput.line("No learned corpse spots: every stand seen so far stood on a bundled spot.")
            return
        }
        DevOutput.line("§bLearned corpse spots§r (stands seen on spots the bundled data does not list)")
        for ((layout, spots) in learned) {
            DevOutput.line("  §f$layout§r${if (CorpseSpotData.isCrystal(layout)) " (crystal)" else ""}")
            for (s in spots) {
                val pos = s.pos.joinToString(", ") { String.format(Locale.ROOT, "%.3f", it) }
                DevOutput.line("    $pos  ${s.type ?: "-"}  seen ${s.seen}x")
            }
        }
        DevOutput.line("")
        DevOutput.line("§bcorpse_spots.json form§r (floored blocks; merge into the bundled file)")
        for (line in bundleJson(learned)) DevOutput.line(line)
    }

    /** The learned spots as the data file writes them: `crystal` for every `_C` layout, `layouts` for the rest. */
    internal fun bundleJson(learned: Map<String, List<LearnedCorpseSpot>>): List<String> {
        fun block(s: LearnedCorpseSpot) = "[${floor(s.pos[0]).toInt()},${floor(s.pos[1]).toInt()},${floor(s.pos[2]).toInt()}]"
        val crystal = learned.filterKeys { CorpseSpotData.isCrystal(it) }.values.flatten().map(::block).distinct()
        val layouts = learned.filterKeys { !CorpseSpotData.isCrystal(it) }.toSortedMap()
        val out = ArrayList<String>()
        out += "{"
        if (crystal.isNotEmpty()) out += "  \"crystal\": [${crystal.joinToString(",")}]" + if (layouts.isNotEmpty()) "," else ""
        if (layouts.isNotEmpty()) {
            out += "  \"layouts\": {"
            val entries = layouts.entries.toList()
            for ((i, e) in entries.withIndex()) {
                out += "    \"${e.key}\": [${e.value.map(::block).distinct().joinToString(",")}]" + if (i < entries.size - 1) "," else ""
            }
            out += "  }"
        }
        out += "}"
        return out
    }
}
