package com.soulreturns.features.mining.mineshaft

import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import net.minecraft.client.Minecraft

/**
 * Frozen Corpse counts for the current mineshaft, scanned off the tab list. The single source of
 * truth for the corpse HUD, both corpse alerts, and the `corpses` block of a logged visit.
 *
 * Hypixel renders **one tab row per corpse instance**, as ` <Type>: NOT LOOTED` or ` <Type>: LOOTED`,
 * sometimes with a `✔` in front of the state. So the counts are a group-by over rows, not a parse of
 * a single summary line.
 *
 * ### Never whitelist the type
 * `Lapis`, `Umber`, `Tungsten` and `Vanguard` are what exists today; Hypixel ships new ones without
 * warning and the whole pipeline — this scan, the HUD's colour lookup, the wire contract, the backend
 * — treats the key set as open. An unknown corpse type must still be counted, still be drawn and
 * still reach the backend.
 *
 * ### A missing signal is not an empty mineshaft
 * This is the same rule the SkyBlock state readers encode, and it bit here first: a visit that
 * observed 3 Lapis corpses was logged with `corpses: {}`, because at some point before the exit a
 * scan came back with no rows and overwrote the counts. The tab list is empty for a moment around a
 * server transfer, and Hypixel may also stop listing a corpse once it has been looted — either way an
 * empty read means *we learned nothing this tick*, not *this mineshaft has no corpses*.
 *
 * So [scan] never replaces a non-empty map with an empty one, and skips entirely when the tab list
 * has not synced. `byType` is therefore the **high-water mark of what was seen this visit**, which is
 * exactly the semantic `found` needs on the wire. [clear] at the start of a visit is what makes that
 * bounded — and it is deliberately not done on leaving, because the visit tracker has to snapshot the
 * map **synchronously** on the way out (see [MineshaftVisitTracker]).
 *
 * The scan is driven by [MineshaftTick], which only runs inside the mineshaft area, so this costs
 * nothing anywhere else.
 */
object MineshaftCorpses {
    /** Per-type breakdown. [total] is what the wire contract calls `found`. */
    data class Counts(val unlooted: Int, val looted: Int) {
        val total: Int get() = unlooted + looted
    }

    private val LINE = Regex("""\s*(\w+):\s+(?:✔\s*)?(NOT LOOTED|LOOTED)\s*""")

    @Volatile
    var byType: Map<String, Counts> = emptyMap()
        private set

    /** Total corpses of [type] (looted + not), or 0. Case-sensitive — the tab list's own casing. */
    fun totalOf(type: String): Int = byType[type]?.total ?: 0

    /** Whether we have already said we are holding counts the tab list stopped reporting, this visit. */
    @Volatile
    private var reportedHold = false

    /** Drop everything. Called when a new visit starts, so one mineshaft never inherits another's. */
    fun clear() {
        reportedHold = false
        if (byType.isNotEmpty()) byType = emptyMap()
    }

    /** One tab-list pass. Called from [MineshaftTick]; only meaningful inside the mineshaft area. */
    fun scan() {
        val connection = Minecraft.getInstance().player?.connection ?: return
        val listed = connection.listedOnlinePlayers
        // Not synced yet (or mid-transfer). Reading zero corpses out of a tab list we do not have is
        // the failure this whole class comments about.
        if (listed.isEmpty()) return

        val acc = HashMap<String, Counts>()
        for (info in listed) {
            val displayName = info.tabListDisplayName?.string ?: continue
            val match = LINE.matchEntire(MessageDetector.stripColorCodes(displayName)) ?: continue
            val type = match.groupValues[1]
            val looted = match.groupValues[2] == "LOOTED"
            val prev = acc[type] ?: Counts(0, 0)
            acc[type] = if (looted) prev.copy(looted = prev.looted + 1) else prev.copy(unlooted = prev.unlooted + 1)
        }

        // See the class KDoc: an empty read is "we learned nothing", never "they are gone".
        if (acc.isEmpty() && byType.isNotEmpty()) {
            // Once per visit: this is a per-tick condition, and logging it at 20 Hz would bury the
            // transition that actually tells you what Hypixel did.
            if (!reportedHold) {
                reportedHold = true
                DebugLogger.logFeatureEvent("Mineshaft: tab list stopped listing corpses — holding $byType")
            }
            return
        }
        if (acc != byType) {
            DebugLogger.logFeatureEvent("Mineshaft: corpses $byType -> $acc")
            byType = acc
        }
    }
}
