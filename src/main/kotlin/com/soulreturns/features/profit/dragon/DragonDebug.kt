package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.DevOutput
import com.soulreturns.features.profit.dragon.gui.DragonProfitHudSettings
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.hud.tracker.TrackerTab
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * `/soul dev dragons` — why the tracker is or is not counting.
 *
 * "Nothing is being tracked" has five unrelated and individually silent causes here: the master
 * toggle, the sublocation gate, an empty drop catalog, a loot stand whose text nothing recognises, or
 * a price catalog that has not landed so every value reads as a dash. Each of them looks identical from
 * in game — an empty panel — so this prints all five, plus the scan ring, which is the only place an
 * unresolved stand's exact text ever appears.
 */
object DragonDebug {
    fun dump() {
        DevOutput.begin("/soul dev dragons")
        gates()
        DevOutput.line("")
        catalogs()
        DevOutput.line("")
        prices()
        DevOutput.line("")
        counters()
        DevOutput.line("")
        scanRing()
    }

    private fun gates() {
        val d = cfg.combat.dragons
        DevOutput.line("§bGates")
        DevOutput.line("  enabled=§f${d.enabled}§r profitHud=§f${d.profitHud}")
        DevOutput.line("  onSkyblock=§f${SkyblockState.onSkyblock}§r sublocation=§f${SkyblockLocation.subLocation ?: "-"}")
        val atNest = SkyblockLocation.isInSubLocation(DragonsNest.SUBLOCATION)
        DevOutput.line("  at ${DragonsNest.SUBLOCATION}=§f$atNest")
        if (!atNest) {
            DevOutput.line("  §8Everything below the death banner is gated on this. The same DRAGON DOWN! line")
            DevOutput.line("  §8fires in Catacombs Master Mode F7, which is why the gate is not optional.")
        }
        val scan = DragonLootScanner.activeDragon()
        DevOutput.line("  active scan=§f${scan?.displayName ?: "-"}§r awaitingLoot=§f${DragonLootScanner.isScanActiveWithoutLoot()}")
        DevOutput.line("  pending eyes=§f${EyePlacementTracker.pendingCount()}§r lastSpawnEyes=§f${EyePlacementTracker.lastSpawnEyes}")
        DevOutput.line("  next kill attributes as §f${DragonProfitTracker.currentKillSource}")
        DevOutput.line("  announce profit=§f${d.sendDragonProfit}§r toParty=§f${d.sendDragonProfitToPartyChat}")
        DevOutput.line("  announce legion=§f${d.sendLegionOnDeath}§r toParty=§f${d.sendLegionToPartyChat}")
    }

    private fun catalogs() {
        val drops = DropCatalogClient.status()
        val items = ItemCatalogClient.status()
        DevOutput.line("§bCatalogs")
        DevOutput.line("  drops: entries=§f${drops.entries}§r source=§f${drops.source}§r lastError=§f${drops.lastError ?: "-"}")
        DevOutput.line("  items: entries=§f${items.entries}§r source=§f${items.source}§r lastError=§f${items.lastError ?: "-"}")
        DevOutput.line("  §8display-name collisions in the item catalog: ${ItemCatalogClient.displayNameCollisions} (normal; shortest id wins)")
        // The source ids are the contract with the backend seed; a rename on either side resolves
        // nothing at all, silently, so print what actually came back rather than what should have.
        val missing = DragonType.entries.filter { DropCatalogClient.dropsFrom(it.sourceId).isEmpty() }
        if (missing.isEmpty()) {
            DevOutput.line("  §aall seven dragon sources carry drops")
        } else {
            DevOutput.line("  §cno drops under: §f${missing.joinToString(", ") { it.sourceId }}")
            DevOutput.line("  §8Either the seed did not run, or the ids diverged. The client derives them as")
            DevOutput.line("  §8\"dragon.\" + the enum name lowercased; the backend writes the same strings.")
        }
        for (type in DragonType.entries) {
            val rows = DropCatalogClient.dropsFrom(type.sourceId)
            if (rows.isEmpty()) continue
            DevOutput.line("  §8${type.sourceId}: ${rows.size} drops")
        }
    }

    private fun prices() {
        val status = PriceCache.status()
        DevOutput.line("§bPrices §8(GET /prices — bazaar + Elite lowest BIN, merged and served by our backend)")
        DevOutput.line("  entries=§f${status.entries}§r rebuilt ${age(status.updatedAt)}, last=§f${status.catalog.lastError ?: "ok"}")
        // Per upstream half, because the catalog can look perfectly healthy while one of the
        // backend's two polls has been failing — and then a whole class of item reads as unpriced.
        DevOutput.line("  bazaar: §f${status.bazaarQuotes}§r quotes, polled ${age(status.bazaarUpdatedAt)}")
        DevOutput.line("  lowestBIN: §f${status.lowestBinQuotes}§r listed, polled ${age(status.lowestBinUpdatedAt)}")
        val mode = DragonProfitHudSettings.priceMode
        // The mode is panel state, not a config row — say where it is set, or "configured" reads as
        // something to look for in /soul, which is exactly where it no longer is.
        DevOutput.line("  price mode=§f${mode.displayName}§r §8(panel footer)§r npcFloor=§f${cfg.combat.dragons.useNpcPriceIfHigher}")
        DevOutput.line("  §8sell side=${mode.sourceFor(selling = true).displayName}, buy side=${mode.sourceFor(selling = false).displayName}")
        // The three probes the acceptance list names: one bazaar-only item, one shard, one pet. A
        // zero on any of them says which feed is missing, not just that "prices are broken".
        for (id in listOf("HYPERION", DragonProfitTracker.SUMMONING_EYE_ID, "SHARD_DRACONIC", "ENDER_DRAGON;4")) {
            val p = PriceCache.of(id)
            val sellSide = mode.sourceFor(selling = true)
            // What that venue pays, then what the tracker actually values it at. They differ whenever
            // the item cannot be sold at that venue — an auction-house item under either bazaar mode,
            // or anything under NPC mode that no vendor buys — and that gap is the thing worth seeing.
            val atSource = if (!p.sellableAt(sellSide)) {
                "§8not sellable there"
            } else {
                p.at(sellSide)?.let { DragonProfitAnnouncer.formatCoins(it) } ?: "§e— no quote yet"
            }
            // Through the real valuation path, so NPC mode's deliberate lack of a market fallback
            // shows up here rather than being a surprise on the panel.
            val valued = PriceCache.sellValue(id, mode, cfg.combat.dragons.useNpcPriceIfHigher)
                .takeIf { it > 0L }?.let { DragonProfitAnnouncer.formatCoins(it) } ?: "§c— unpriced"
            DevOutput.line("  §8$id: §r$atSource §8→ valued at §f$valued")
        }
    }

    private fun counters() {
        DevOutput.line("§bCounters")
        DevOutput.line("  stats file=§f${PersistentStats.file()}")
        DevOutput.line("  namespace buckets=§f${DragonProfitStatsStore.handle.knownKeys().joinToString(", ").ifEmpty { "-" }}")
        DevOutput.line("  writing to bucket=§f${DragonProfitStatsStore.handle.currentKey()}")
        for (tab in listOf(TrackerTab.Session, TrackerTab.Total)) {
            val stats = DragonProfitTracker.scope(tab)
            if (stats.isEmpty()) {
                DevOutput.line("  ${tab.name}: §8empty")
                continue
            }
            DevOutput.line("  ${tab.name}:")
            for (type in DragonType.entries) {
                for (source in KillSource.entries) {
                    val partition = stats.partition(source)
                    val kills = partition.killsOf(type)
                    val drops = partition.dropsOf(type)
                    if (kills == 0L && drops.isEmpty()) continue
                    val eyes = partition.eyesOf(type)
                    DevOutput.line(
                        "    §f${type.name}§r/${source.name}: kills=§f$kills§r eyes=§f$eyes§r " +
                            "drops=§f${drops.entries.joinToString(", ") { "${it.key} x${it.value}" }}",
                    )
                }
            }
        }
        DevOutput.line("  panel: tab=§f${DragonProfitHudSettings.tab}§r sort=§f${DragonProfitHudSettings.sortId}§r " +
            "source=§f${DragonProfitHudSettings.sourceFilter?.name ?: "ALL"}§r filter=§f${DragonProfitHudSettings.filter.ifEmpty { setOf("all") }}")
    }

    private fun scanRing() {
        val entries = DragonScanLog.snapshot()
        DevOutput.line("§bLast ${entries.size} parsed loot stands")
        if (entries.isEmpty()) {
            DevOutput.line("  §8Nothing yet. Stands are only recorded while a scan window is open, which needs")
            DevOutput.line("  §8a DRAGON DOWN! banner at ${DragonsNest.SUBLOCATION} — and only within ~20 blocks,")
            DevOutput.line("  §8because that is as far as Hypixel renders them.")
            return
        }
        val now = Instant.now()
        for (entry in entries) {
            val agoSeconds = Duration.between(Instant.ofEpochMilli(entry.atMs), now).seconds
            val resolved = entry.itemId?.let { "§a$it" } ?: "§cunresolved"
            DevOutput.line(
                String.format(
                    Locale.ROOT,
                    "  §8%3ds ago §r%-9s '§f%s§r' → %s§r x%d (granted %d)",
                    agoSeconds, entry.dragon.name, entry.raw, resolved, entry.count, entry.granted,
                ),
            )
        }
        val unresolved = entries.filter { it.itemId == null }.distinctBy { it.raw }
        if (unresolved.isEmpty()) return

        // Two very different populations end up here, and conflating them sends an admin chasing a
        // signpost. A name the ITEM catalog knows is a real item that the drop graph is missing — the
        // actionable case. A name it does not know is almost always End-island scenery: the scan is a
        // radius sweep of every armour stand nearby, so travel-scroll holograms and floating labels
        // come along with the loot. They can never resolve, because resolution is scoped to the
        // dragon's own drop list — they are noise, not a gap.
        val known = unresolved.filter { ItemCatalogClient.byDisplayName(standItemName(it.raw)) != null }
        val unknown = unresolved - known.toSet()

        if (known.isNotEmpty()) {
            DevOutput.line("")
            DevOutput.line("§cUnresolved, but the item catalog knows the name — the drop graph is missing a row:")
            for (entry in known) {
                val item = ItemCatalogClient.byDisplayName(standItemName(entry.raw))
                DevOutput.line("  '§f${entry.raw}§r' → §f${item?.id}§7 under §f${entry.dragon.sourceId}")
            }
            DevOutput.line("  §8Add the drop row, or a displayNameOverride if the stand text differs from the")
            DevOutput.line("  §8catalog name — the override has to match the stand text exactly.")
        }

        if (unknown.isNotEmpty()) {
            DevOutput.line("")
            DevOutput.line("§8Not recognised at all — almost certainly scenery, not loot:")
            for (entry in unknown) DevOutput.line("  §8'${entry.raw}'")
            DevOutput.line("  §8The scan sweeps every armour stand within 30 blocks, so End holograms land here.")
            DevOutput.line("  §8Only worth a look if one of these is genuinely an item Hypixel just added.")
        }
    }

    /** `"Ender Pearl x16"` → `"Ender Pearl"`, so a stand's text can be probed against the catalog. */
    private fun standItemName(raw: String): String =
        Regex("""^(.+?)(?:\s+x\d+)?$""").matchEntire(raw)?.groupValues?.get(1)?.trim() ?: raw

    /** An epoch-ms timestamp as an age. Null is a finding — nothing has ever been fetched. */
    private fun age(epochMs: Long?): String =
        if (epochMs == null) "§cnever fetched" else "§f${(System.currentTimeMillis() - epochMs) / 1000}s ago"
}
