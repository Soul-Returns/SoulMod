package com.soulreturns.features.diana

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.MayorState
import com.soulreturns.data.skyblock.MythologicalMobCatalog
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Diana-event mob tracker — counts mobs the player digs out of Griffin Burrows during Mayor
 * Diana's "Mythological Ritual" term.
 *
 * **Three scopes** mapped to the three [com.soulreturns.ui.hud.tracker.TrackerTab] tabs:
 *  - **Session** — in-memory counters; reset on client launch or via the HUD Reset button.
 *  - **Event** — persisted counters keyed by the current Hypixel mayor term
 *    (`MayorState.currentMayorKey()`). When a new mayor takes office the previous bucket
 *    stays on disk (so a /soul dev command could be added to inspect history) but the HUD
 *    only renders the active term.
 *  - **Total** — persisted all-time counters across every mayor.
 *
 * **One mob per chat line.** Hypixel always emits exactly one "You dug out a ..." per dig
 * (you can't dig two simultaneously). Trailing parenthetical / bracket suffixes seen in
 * the wild (`" §e(92)"`, `" (8/10) [2]"`) are appended by third-party chat-counter mods
 * and are NOT a Hypixel chat-compactor count — every match counts as +1, never +N.
 *
 * **Why a dedicated file instead of [com.soulreturns.stats.PersistentStats]?** Same reason
 * as the profit trackers — event buckets are keyed by mayor term, which doesn't fit
 * `stats.json`'s per-profile shape. Lives at `config/soul/mythological_mobs.json`.
 */
object MythologicalMobTracker {
    private val logger = SoulLogger("Soul/Diana")

    private const val SAVE_FILE_NAME = "mythological_mobs.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/$SAVE_FILE_NAME")
    }

    /** Per-mob session counts. In-memory only; reset by [resetSession]. */
    @Volatile var sessionByMob: Map<String, Long> = emptyMap()
        private set

    @Volatile var sessionTotal: Long = 0L
        private set

    /** Per-mob session cocoon counts ("CAUGHT! You cocooned a ..." chat lines). */
    @Volatile var sessionCocoonsByMob: Map<String, Long> = emptyMap()
        private set

    @Volatile var sessionCocoonsTotal: Long = 0L
        private set

    /**
     * Persisted state. `totalByMob` accumulates across every mayor; `eventBuckets` is
     * `mayorTermKey -> mob name -> count` so historical Diana terms (and any other mayor
     * runs the user happens to mythologise during, e.g. Jerry's Perkpocalypse cloning
     * Diana's perk) stay distinguishable.
     */
    private data class Data(
        var totalByMob: MutableMap<String, Long> = mutableMapOf(),
        var totalAll: Long = 0L,
        var eventBuckets: MutableMap<String, MutableMap<String, Long>> = mutableMapOf(),
        /** Cumulative active-time milliseconds across every mayor — Total tab denominator. */
        var totalActiveMs: Long = 0L,
        /** Cumulative active-time milliseconds per mayor term — Event tab denominator. */
        var eventActiveMsByMayor: MutableMap<String, Long> = mutableMapOf(),
        /** Per-mob cocoon totals across every mayor. */
        var totalCocoonsByMob: MutableMap<String, Long> = mutableMapOf(),
        var totalCocoonsAll: Long = 0L,
        /** Per-mob cocoon counts per mayor term — Event tab cocoon view. */
        var eventCocoonsByMayor: MutableMap<String, MutableMap<String, Long>> = mutableMapOf(),
    )

    @Volatile private var data = Data()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile private var initialized: Boolean = false

    fun init() {
        if (initialized) return
        initialized = true
        load()
        // Avoid double-counting time elapsed between the timer's first tick and our first
        // tick — the timer's session clock may have already grown by a few hundred ms when
        // the player joins a fishing-festival world before this object is ready.
        MythologicalActivityTimer.resetConsumeCursor()
        Events.subscribe(this)
        var ticksSinceSave = 0
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                accumulateActiveTime()
                ticksSinceSave++
                if (dirty && !saving && ticksSinceSave >= 20) {
                    ticksSinceSave = 0
                    saveAsync()
                }
            },
        )
    }

    /**
     * Per-tick drain of [MythologicalActivityTimer]'s delta into the persistent Event +
     * Total clocks. The activity timer remains the session source of truth; we just
     * shadow-accumulate into the per-mayor and all-time buckets here so the HUD's Event /
     * Total tabs survive a client restart.
     */
    private fun accumulateActiveTime() {
        val delta = MythologicalActivityTimer.consumeDelta()
        if (delta <= 0L) return
        data.totalActiveMs += delta
        MayorState.currentMayorKey()?.let { key ->
            data.eventActiveMsByMayor[key] = (data.eventActiveMsByMayor[key] ?: 0L) + delta
        }
        dirty = true
    }

    /** Cumulative active-time ms for the current Event bucket (current mayor term). */
    fun eventActiveMs(): Long {
        val key = MayorState.currentMayorKey() ?: return 0L
        return data.eventActiveMsByMayor[key] ?: 0L
    }

    /** Cumulative active-time ms across every mayor. */
    fun totalActiveMs(): Long = data.totalActiveMs

    /** Reset Session button — wipes in-memory only. Activity timer is cleared by caller. */
    fun resetSession() {
        sessionByMob = emptyMap()
        sessionTotal = 0L
        sessionCocoonsByMob = emptyMap()
        sessionCocoonsTotal = 0L
        MythologicalActivityTimer.resetSession()
    }

    /** Dev / admin entry point — wipes everything (session + event buckets + total). */
    fun resetAll() {
        resetSession()
        data = Data()
        dirty = true
    }

    /** Per-mob count in the current Event bucket (current mayor term). Empty when no mayor known. */
    fun eventByMob(): Map<String, Long> {
        val key = MayorState.currentMayorKey() ?: return emptyMap()
        return data.eventBuckets[key]?.toMap() ?: emptyMap()
    }

    fun eventTotal(): Long = eventByMob().values.sum()

    fun totalByMob(): Map<String, Long> = data.totalByMob.toMap()

    fun totalAll(): Long = data.totalAll

    /** Per-mob cocoon counts in the current Event bucket. Empty when no mayor known. */
    fun eventCocoonsByMob(): Map<String, Long> {
        val key = MayorState.currentMayorKey() ?: return emptyMap()
        return data.eventCocoonsByMayor[key]?.toMap() ?: emptyMap()
    }

    fun eventCocoonsTotal(): Long = eventCocoonsByMob().values.sum()

    fun totalCocoonsByMob(): Map<String, Long> = data.totalCocoonsByMob.toMap()

    fun totalCocoonsAll(): Long = data.totalCocoonsAll

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.dev.trackers.mythologicalTracker()) return
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        // Cocoon lines and dig-out lines are disjoint — cocoons fire on cocoon-equipment
        // kills (separate outcome from a regular dig). Try cocoon first since its prefix
        // pattern is more specific; fall through to dig-out otherwise.
        MythologicalMobCatalog.matchCocoon(stripped)?.let {
            recordCocoon(it.name)
            return
        }
        MythologicalMobCatalog.matchDigOut(stripped)?.let { recordKill(it.name) }
    }

    private fun recordCocoon(mobName: String) {
        sessionCocoonsByMob = sessionCocoonsByMob + (mobName to ((sessionCocoonsByMob[mobName] ?: 0L) + 1L))
        sessionCocoonsTotal += 1L
        data.totalCocoonsByMob[mobName] = (data.totalCocoonsByMob[mobName] ?: 0L) + 1L
        data.totalCocoonsAll += 1L
        MayorState.currentMayorKey()?.let { key ->
            val bucket = data.eventCocoonsByMayor.getOrPut(key) { mutableMapOf() }
            bucket[mobName] = (bucket[mobName] ?: 0L) + 1L
        }
        dirty = true
    }

    private fun recordKill(mobName: String) {
        sessionByMob = sessionByMob + (mobName to ((sessionByMob[mobName] ?: 0L) + 1L))
        sessionTotal += 1L
        // Persisted total — always grows regardless of mayor knowledge.
        data.totalByMob[mobName] = (data.totalByMob[mobName] ?: 0L) + 1L
        data.totalAll += 1L
        // Persisted event bucket — only if we know the active mayor term. Until the
        // election API responds, kills land in session + total but not in any event bucket;
        // when the mayor is resolved later in the session, fresh kills will start filling
        // the right bucket.
        MayorState.currentMayorKey()?.let { key ->
            val bucket = data.eventBuckets.getOrPut(key) { mutableMapOf() }
            bucket[mobName] = (bucket[mobName] ?: 0L) + 1L
        }
        dirty = true
    }

    private fun load() {
        if (!saveFile.exists()) {
            logger.info("No $SAVE_FILE_NAME — starting from defaults")
            return
        }
        try {
            val json = JsonParser.parseReader(saveFile.reader())
            if (!json.isJsonObject) {
                logger.warn("$SAVE_FILE_NAME not a JSON object — using defaults")
                return
            }
            data = gson.fromJson(json, Data::class.java) ?: Data()
            logger.info(
                "Loaded $SAVE_FILE_NAME: total=${data.totalAll}, " +
                    "buckets=${data.eventBuckets.size}",
            )
        } catch (e: Exception) {
            logger.warn("Failed to read $SAVE_FILE_NAME — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot =
            Data(
                totalByMob = data.totalByMob.toMutableMap(),
                totalAll = data.totalAll,
                eventBuckets =
                    data.eventBuckets
                        .mapValues { (_, v) -> v.toMutableMap() }
                        .toMutableMap(),
            )
        SoulExecutor.executor.submit {
            try {
                saveFile.parentFile?.mkdirs()
                val tmp = File(saveFile.parentFile, "${saveFile.name}.tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (saveFile.exists()) saveFile.delete()
                if (!tmp.renameTo(saveFile)) {
                    logger.warn("Could not rename ${tmp.name} → ${saveFile.name}; falling back to direct write")
                    saveFile.writeText(gson.toJson(snapshot))
                    tmp.delete()
                }
            } catch (e: Exception) {
                logger.warn("Failed to persist $SAVE_FILE_NAME", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
