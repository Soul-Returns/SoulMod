package com.soulreturns.features.diana

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.skyblock.DianaStatRowEntry
import com.soulreturns.data.skyblock.DianaStatsCatalogClient
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Per-row spec for the Diana Stats HUD. Two variants cover every stat row the user wants:
 *
 *  - [MobItem]: "<Mob> since <Item>". Both axes (counter and reset) are source-segregated:
 *    the regular column counts OWN kills of [MobItem.mobName] and resets on OWN drops of
 *    [MobItem.itemId]; the [LS] column counts LOOTSHARE kills of the same mob and resets
 *    on LOOTSHARE drops of the same item. The two columns are entirely independent — an
 *    own Chimera drop doesn't reset the LS column; a lootshare Chimera doesn't reset the
 *    regular column. [MobItem.showLootshareColumn] toggles whether the [LS] suffix is
 *    rendered; the internal counter still ticks regardless so flipping the toggle later
 *    doesn't lose history.
 *
 *  - [AnyMobsSinceMob]: "Mobs since <Mob>: N". OWN-side only — every own dig of any
 *    Diana mob increments the counter, with one exception: digging out the row's
 *    [AnyMobsSinceMob.mobName] resets it to 0 instead. Lootshare kills are ignored
 *    entirely (no count, no reset). No [LS] column.
 */
sealed class DianaStatRow {
    abstract val id: String
    abstract val label: String

    /** `true` if the row renders a `, since [LS]: <N>` suffix. */
    abstract val hasLootshareColumn: Boolean

    /**
     * ARGB color for the label + separator chunks (everything that isn't a number). Null
     * = use the HUD's default — let the renderer pick, since the mod owns the theme.
     */
    abstract val labelColor: Int?

    /** ARGB color for the numeric value chunks (regular + LS). Null = use HUD default. */
    abstract val valueColor: Int?

    data class MobItem(
        override val id: String,
        override val label: String,
        val mobName: String,
        val itemId: String,
        val showLootshareColumn: Boolean = true,
        override val labelColor: Int? = null,
        override val valueColor: Int? = null,
    ) : DianaStatRow() {
        override val hasLootshareColumn: Boolean get() = showLootshareColumn
    }

    data class AnyMobsSinceMob(
        override val id: String,
        override val label: String,
        val mobName: String,
        override val labelColor: Int? = null,
        override val valueColor: Int? = null,
    ) : DianaStatRow() {
        override val hasLootshareColumn: Boolean get() = false
    }
}

/**
 * Tracks per-row counters for the Diana Stats HUD. Subscribes to [MythologicalMobKilled]
 * (increments matching rows' counters) and [MythologicalDropCredited] (resets matching
 * rows' counters when the trigger item drops via the matching source).
 *
 * **Persistence** — `config/soul/diana_stats.json`. Per-row counters survive restarts.
 * Tick-debounced save (max once per second) via [SoulExecutor], same pattern as the
 * other Diana trackers.
 *
 * **Row source** — for now [HARDCODED_ROWS]; once the backend `diana.stat_rows` catalog
 * lands these will be loaded from a `MythologicalStatRowsCatalogClient` (see
 * `prompts/diana-stats/` TBD).
 *
 * **Area gate** — kills and drop-resets only register while [LocationApi.isInArea]
 * `"Hub"`. Diana mobs only spawn on the Hub island anyway, but the gate prevents stray
 * `Events.publish` calls from non-Diana contexts (rare, but possible during a debug
 * scenario) from poisoning the counters.
 */
object MythologicalStatsTracker {
    private val logger = SoulLogger("Soul/Diana")

    private const val SAVE_FILE_NAME = "diana_stats.json"
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/$SAVE_FILE_NAME")
    }

    /**
     * Initial hardcoded row set for mod-first development. Mixes both row variants and
     * uses mob/item pairings the user confirmed (Hilts drop only from Minos Hunter).
     * Will be replaced by a backend-driven catalog in a follow-up.
     */
    private val HARDCODED_ROWS: List<DianaStatRow> =
        listOf(
            // MobItem with [LS] — regular counts OWN Hunter kills and resets on OWN Hilt
            // drop; [LS] counts LOOTSHARE Hunter kills and resets on LOOTSHARE Hilt drop.
            DianaStatRow.MobItem(
                id = "hunters-since-hilt",
                label = "Hunters since Hilt",
                mobName = "Minos Hunter",
                itemId = "HILT_OF_REVELATIONS",
            ),
            // Same row shape but [LS] column hidden. Useful when the user doesn't care
            // about lootshare attribution for that pair. Internal counter still ticks.
            DianaStatRow.MobItem(
                id = "hunters-since-hilt-own-only",
                label = "Hunters since Hilt (own)",
                mobName = "Minos Hunter",
                itemId = "HILT_OF_REVELATIONS",
                showLootshareColumn = false,
            ),
            // AnyMobsSinceMob — dry streak. Reset on the player's own dig of the specific
            // mob. Lootshare-killing someone else's dig doesn't end the streak.
            DianaStatRow.AnyMobsSinceMob(
                id = "mobs-since-hunter",
                label = "Mobs since Hunter",
                mobName = "Minos Hunter",
            ),
            DianaStatRow.AnyMobsSinceMob(
                id = "mobs-since-harpy",
                label = "Mobs since Harpy",
                mobName = "Harpy",
            ),
            DianaStatRow.AnyMobsSinceMob(
                id = "mobs-since-inq",
                label = "Mobs since Inq",
                mobName = "Minos Inquisitor",
            ),
        )

    /**
     * Read-only view of the active rows. Backend-driven: reads from
     * [DianaStatsCatalogClient.snapshot] every call (rebuild is cheap — handful of rows)
     * and falls back to [HARDCODED_ROWS] only when the catalog is still empty (first
     * launch before the disk cache has been seeded by a successful fetch, OR backend
     * offline AND no prior cache). Once a fetch lands, the hardcoded set stops being
     * consulted — admin edits become the source of truth.
     *
     * Catalog rows that don't decode cleanly into one of the [DianaStatRow] variants are
     * dropped with a warning; the rest of the list still renders so a single malformed
     * row can't blank the HUD.
     */
    fun rows(): List<DianaStatRow> {
        val catalogRows = DianaStatsCatalogClient.snapshot.rows
        if (catalogRows.isEmpty()) return HARDCODED_ROWS
        return catalogRows.mapNotNull { toRow(it) }
    }

    private fun toRow(entry: DianaStatRowEntry): DianaStatRow? {
        // Discriminator string lives on the wire; map to sealed variants here.
        if (entry.rowType == "mob_item") {
            val itemId = entry.itemId
            if (itemId.isNullOrBlank()) {
                logger.warn("Diana stats row ${entry.id}: mob_item rowType requires itemId — dropped")
                return null
            }
            return DianaStatRow.MobItem(
                id = entry.id,
                label = entry.label,
                mobName = entry.mobName,
                itemId = itemId,
                showLootshareColumn = entry.showLootshareColumn,
                labelColor = entry.labelColor,
                valueColor = entry.valueColor,
            )
        }
        if (entry.rowType == "any_mobs_since_mob") {
            return DianaStatRow.AnyMobsSinceMob(
                id = entry.id,
                label = entry.label,
                mobName = entry.mobName,
                labelColor = entry.labelColor,
                valueColor = entry.valueColor,
            )
        }
        logger.warn("Diana stats row ${entry.id}: unknown rowType '${entry.rowType}' — dropped")
        return null
    }

    private data class Counters(
        var regular: Long = 0L,
        var lootshare: Long = 0L,
    )

    private data class Data(
        var counters: MutableMap<String, Counters> = mutableMapOf(),
    )

    @Volatile private var data = Data()

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile private var initialized: Boolean = false

    fun init() {
        if (initialized) return
        initialized = true
        load()
        Events.subscribe(this)
        var ticksSinceSave = 0
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                ticksSinceSave++
                if (dirty && !saving && ticksSinceSave >= 20) {
                    ticksSinceSave = 0
                    saveAsync()
                }
            },
        )
    }

    fun regular(rowId: String): Long = data.counters[rowId]?.regular ?: 0L

    fun lootshare(rowId: String): Long = data.counters[rowId]?.lootshare ?: 0L

    @HandleEvent
    fun onMobKilled(event: MythologicalMobKilled) {
        if (!LocationApi.isInArea("Hub")) return
        // Per-variant handling:
        //   - MobItem: increment whichever counter axis matches the kill source
        //     (own→regular, lootshare→[LS]) when the row's mob matches.
        //   - AnyMobsSinceMob: OWN-side ONLY. Lootshare kills ignored entirely. Own dig
        //     of the row's specific mob = reset; own dig of any other mob = +1.
        for (row in rows()) {
            // `when (sealedSubject)` triggers Kotlin's $WhenMappings synthetic that Fabric's
            // KnotClassLoader can fail to resolve — using subjectless when + `is` checks
            // (the type-pattern path) avoids it.
            when {
                row is DianaStatRow.MobItem -> {
                    if (row.mobName == event.mobName) {
                        val c = data.counters.getOrPut(row.id) { Counters() }
                        if (event.source == DianaEventSource.OWN) {
                            c.regular += 1L
                        } else if (event.source == DianaEventSource.LOOTSHARE) {
                            c.lootshare += 1L
                        }
                        dirty = true
                    }
                }
                row is DianaStatRow.AnyMobsSinceMob -> {
                    if (event.source != DianaEventSource.OWN) continue
                    val c = data.counters.getOrPut(row.id) { Counters() }
                    if (row.mobName == event.mobName) {
                        if (c.regular != 0L) {
                            c.regular = 0L
                            dirty = true
                            logger.info(
                                "Diana stats: row=${row.id} reset by own dig of ${event.mobName}",
                            )
                        }
                    } else {
                        c.regular += 1L
                        dirty = true
                    }
                }
            }
        }
    }

    @HandleEvent
    fun onDropCredited(event: MythologicalDropCredited) {
        if (!LocationApi.isInArea("Hub")) return
        for (row in rows()) {
            // Only MobItem rows have item-driven resets.
            if (row !is DianaStatRow.MobItem) continue
            if (row.itemId != event.itemId) continue
            val c = data.counters[row.id] ?: continue
            if (event.source == DianaEventSource.OWN) {
                if (c.regular != 0L) {
                    c.regular = 0L
                    dirty = true
                    logger.info("Diana stats: row=${row.id} regular reset by item ${event.itemId}")
                }
            } else if (event.source == DianaEventSource.LOOTSHARE) {
                if (c.lootshare != 0L) {
                    c.lootshare = 0L
                    dirty = true
                    logger.info("Diana stats: row=${row.id} lootshare reset by item ${event.itemId}")
                }
            }
        }
    }

    /** Dev/admin reset — zeros every counter and persists. */
    fun resetAll() {
        data.counters.clear()
        dirty = true
        saveAsync()
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
            logger.info("Loaded $SAVE_FILE_NAME: ${data.counters.size} row counter(s)")
        } catch (e: Exception) {
            logger.warn("Failed to read $SAVE_FILE_NAME — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        // data.copy(...) so future fields ride through unchanged.
        val snapshot =
            data.copy(
                counters =
                    data.counters
                        .mapValues { (_, v) -> v.copy() }
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
