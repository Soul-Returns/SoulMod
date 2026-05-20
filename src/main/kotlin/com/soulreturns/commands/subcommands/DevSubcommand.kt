package com.soulreturns.commands.subcommands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.profile.ProfileApi
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.features.farming.seasoning.SeasoningTracker
import com.soulreturns.features.profit.dragon.DragonDrop
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.DragonType
import com.soulreturns.features.profit.dragon.KillSource
import com.soulreturns.platform.sync.SyncEngine
import com.soulreturns.platform.sync.SyncKind
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.soulChat
import io.wispforest.owo.config.Option
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Developer-facing diagnostic commands.
 *
 *  - `/soul dev getArea`                                  → SkyBlock island from the tab list
 *  - `/soul dev getSubLocation`                           → sublocation from the scoreboard sidebar
 *  - `/soul dev getSkyblock`                              → SkyblockApi.isOnSkyblock + raw sidebar title (debug detection misses)
 *  - `/soul dev getProfile`                               → active SkyBlock profile from the tab list
 *  - `/soul dev listStatProfiles`                         → all profile slots in stats.json, marking the active one
 *  - `/soul dev resetSeasonings`                          → reset seasoning total to 0 (persisted)
 *  - `/soul dev resetConfig`                              → reset every owo-config option to its declared default + save + notify cloud sync
 *  - `/soul dev clearAlerts`                              → wipe in-flight alert overlays
 *  - `/soul dev testAlert [<message>]`                    → render a test alert
 *  - `/soul dev testMessage <type> <message>`             → simulate an incoming chat line of [type]
 *      types: `server`, `serverColor`, `party`, `partyColor`, `public`, `publicColor`, `guild`, `guildColor`
 *  - `/soul dev grantDragonDrop <dragonType> <dropId> [amount]`  → add fake drops to the dragon profit tracker (uses current kill source)
 *  - `/soul dev grantDragonDropAs <SUMMONED|LOOTSHARE> <dragonType> <dropId> [amount]`  → grant with explicit source partition
 *  - `/soul dev grantDragonEye <dragonType> [count]`             → add fake Summoning Eye placements (cost subtracted from profit)
 *  - `/soul dev grantDragonKill <dragonType> [count]`            → bump the dragon profit tracker's kill counter
 *  - `/soul dev resetDragonProfit`                               → wipe all dragon profit data (session + persisted)
 *  - `/soul dev refreshPrices`                                   → force an immediate bazaar + lowest-BIN refetch (synchronous, echoes counts)
 *  - `/soul dev getPrice <itemId>`                               → print the cached bazaar buy / sell / lowest-BIN price for one item id
 *  - `/soul dev sackGet <itemId>`                                → print the active profile's recorded sack count for one item id
 *  - `/soul dev sackDump`                                        → dump top 30 sack entries (active profile) sorted by count
 *  - `/soul dev sackReset`                                       → wipe the active profile's sack state
 *  - `/soul dev loadProfile`                                     → force a Hypixel /skyblock/profiles fetch for the current SkyBlock profile (seeds sacks)
 *  - `/soul dev catalogStatus`                                   → print item catalog size + last-applied updatedAt
 *  - `/soul dev refreshCatalog`                                  → force a /items refetch (bypasses Mercure invalidate wait)
 */
object DevSubcommand : SoulSubcommand {
    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("dev") {
            // Location helpers
            then(
                literal("getArea") {
                    runs { _ ->
                        val area = LocationApi.currentArea
                        soulChat(if (area == null) "§7Area: §c<unknown>" else "§7Area: §a$area")
                    }
                }
            )
            then(
                literal("getSubLocation") {
                    runs { _ ->
                        val sub = LocationApi.currentSublocation
                        soulChat(if (sub == null) "§7Sublocation: §c<unknown>" else "§7Sublocation: §a$sub")
                    }
                }
            )
            then(
                literal("getSkyblock") {
                    runs { _ ->
                        val onSb = SkyblockApi.isOnSkyblock
                        // Raw scoreboard title for diagnosing detection misses (resource packs,
                        // sub-game scoreboards, etc.).
                        val rawTitle =
                            try {
                                val level = Minecraft.getInstance().level
                                val objective =
                                    level?.scoreboard
                                        ?.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR)
                                objective?.displayName?.string ?: "<none>"
                            } catch (e: Throwable) {
                                "<error: ${e.message}>"
                            }
                        soulChat(
                            "§7On SkyBlock: ${if (onSb) "§atrue" else "§cfalse"} " +
                                "§7(sidebar title: §f${rawTitle.take(64)}§7)"
                        )
                    }
                }
            )
            then(
                literal("getProfile") {
                    runs { _ ->
                        val p = ProfileApi.currentProfile
                        soulChat(if (p == null) "§7Profile: §c<unknown>" else "§7Profile: §a$p")
                    }
                }
            )
            then(
                literal("listStatProfiles") {
                    runs { _ ->
                        val active = ProfileApi.currentProfile
                        val known = PersistentStats.knownProfiles()
                        if (known.isEmpty()) {
                            soulChat("§7No persisted stat profiles yet.")
                        } else {
                            soulChat("§7Stat profiles (§a${known.size}§7):")
                            known.sorted().forEach { key ->
                                val marker =
                                    when {
                                        key == PersistentStats.LEGACY_KEY -> " §8(pre-profile bucket)"
                                        key == active -> " §a(active)"
                                        else -> ""
                                    }
                                soulChat("  §7- §f$key$marker")
                            }
                        }
                    }
                }
            )

            // Persisted-stat resets
            then(
                literal("resetSeasonings") {
                    runs { _ ->
                        SeasoningTracker.reset()
                        soulChat("§aSeasonings counter reset to §f0§a (persisted to stats.json).")
                    }
                }
            )

            // Full config reset — sets every owo-config option to its declared default.
            // Mirrors what the admin web UI's "reset all" button does on the backend, but
            // entirely client-side. Saves config.json5 and notifies the sync engine so the
            // wipe propagates to other devices on the account. Destructive — runs without
            // confirmation (matching the rest of /soul dev), so it's gated behind the dev
            // category by virtue of living here.
            then(
                literal("resetConfig") {
                    runs { _ ->
                        resetConfigToDefaults()
                    }
                }
            )

            // Alerts
            then(
                literal("clearAlerts") {
                    runs { _ ->
                        RenderUtils.clearAlerts()
                        soulChat("§aCleared all alerts!")
                        DebugLogger.logSentMessage("Cleared all alerts!")
                    }
                }
            )
            then(
                literal("testAlert") {
                    stringArg("message") { _, msg ->
                        RenderUtils.showAlert(msg, 0xFFFF0000.toInt(), 2.0f, 5000)
                        soulChat("§aShowing alert: §r$msg")
                        DebugLogger.logSentMessage("Showing alert: $msg")
                    }
                    runs { _ ->
                        RenderUtils.showAlert("Don Expresso is leaving in 1 minute!", 0xFFFF0000.toInt(), 2.0f, 5000)
                        soulChat("§aShowing Don Expresso alert!")
                        DebugLogger.logSentMessage("Showing Don Expresso alert!")
                    }
                }
            )

            // Test chat-message simulation
            then(
                literal("testMessage") {
                    then(literal("server").stringArg("message") { _, msg -> sendTestMessage(MessageType.SERVER, msg, false) })
                    then(literal("serverColor").stringArg("message") { _, msg -> sendTestMessage(MessageType.SERVER, msg, true) })
                    then(literal("party").stringArg("message") { _, msg -> sendTestMessage(MessageType.PARTY, msg, false) })
                    then(literal("partyColor").stringArg("message") { _, msg -> sendTestMessage(MessageType.PARTY, msg, true) })
                    then(literal("public").stringArg("message") { _, msg -> sendTestMessage(MessageType.PUBLIC, msg, false) })
                    then(literal("publicColor").stringArg("message") { _, msg -> sendTestMessage(MessageType.PUBLIC, msg, true) })
                    then(literal("guild").stringArg("message") { _, msg -> sendTestMessage(MessageType.GUILD, msg, false) })
                    then(literal("guildColor").stringArg("message") { _, msg -> sendTestMessage(MessageType.GUILD, msg, true) })
                }
            )

            // Dragon profit tracker test commands — drop detection isn't wired yet; these
            // populate the tracker manually so HUD layout / sort / filter / reset can be
            // validated end-to-end. <dragonType> is the enum name (PROTECTOR, OLD, WISE, …);
            // <dropId> is also the enum name from DragonDrop (PROTECTOR_FRAGMENT, ASPECT_OF_THE_DRAGONS, …).
            then(
                literal("grantDragonDrop") {
                    then(
                        ClientCommandManager.argument("dragonType", StringArgumentType.word())
                            .then(
                                ClientCommandManager.argument("dropId", StringArgumentType.word())
                                    .executes { ctx ->
                                        executeGrantDragonDrop(
                                            StringArgumentType.getString(ctx, "dragonType"),
                                            StringArgumentType.getString(ctx, "dropId"),
                                            1,
                                        )
                                        1
                                    }
                                    .then(
                                        ClientCommandManager.argument("amount", IntegerArgumentType.integer(1))
                                            .executes { ctx ->
                                                executeGrantDragonDrop(
                                                    StringArgumentType.getString(ctx, "dragonType"),
                                                    StringArgumentType.getString(ctx, "dropId"),
                                                    IntegerArgumentType.getInteger(ctx, "amount"),
                                                )
                                                1
                                            },
                                    ),
                            ),
                    )
                },
            )
            then(
                literal("grantDragonDropAs") {
                    then(
                        ClientCommandManager.argument("source", StringArgumentType.word())
                            .then(
                                ClientCommandManager.argument("dragonType", StringArgumentType.word())
                                    .then(
                                        ClientCommandManager.argument("dropId", StringArgumentType.word())
                                            .executes { ctx ->
                                                executeGrantDragonDropAs(
                                                    StringArgumentType.getString(ctx, "source"),
                                                    StringArgumentType.getString(ctx, "dragonType"),
                                                    StringArgumentType.getString(ctx, "dropId"),
                                                    1,
                                                )
                                                1
                                            }
                                            .then(
                                                ClientCommandManager.argument("amount", IntegerArgumentType.integer(1))
                                                    .executes { ctx ->
                                                        executeGrantDragonDropAs(
                                                            StringArgumentType.getString(ctx, "source"),
                                                            StringArgumentType.getString(ctx, "dragonType"),
                                                            StringArgumentType.getString(ctx, "dropId"),
                                                            IntegerArgumentType.getInteger(ctx, "amount"),
                                                        )
                                                        1
                                                    },
                                            ),
                                    ),
                            ),
                    )
                },
            )
            then(
                literal("grantDragonEye") {
                    then(
                        ClientCommandManager.argument("dragonType", StringArgumentType.word())
                            .executes { ctx ->
                                executeGrantDragonEye(StringArgumentType.getString(ctx, "dragonType"), 1)
                                1
                            }
                            .then(
                                ClientCommandManager.argument("count", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        executeGrantDragonEye(
                                            StringArgumentType.getString(ctx, "dragonType"),
                                            IntegerArgumentType.getInteger(ctx, "count"),
                                        )
                                        1
                                    },
                            ),
                    )
                },
            )
            then(
                literal("grantDragonKill") {
                    then(
                        ClientCommandManager.argument("dragonType", StringArgumentType.word())
                            .executes { ctx ->
                                executeGrantDragonKill(StringArgumentType.getString(ctx, "dragonType"), 1)
                                1
                            }
                            .then(
                                ClientCommandManager.argument("count", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        executeGrantDragonKill(
                                            StringArgumentType.getString(ctx, "dragonType"),
                                            IntegerArgumentType.getInteger(ctx, "count"),
                                        )
                                        1
                                    },
                            ),
                    )
                },
            )
            then(
                literal("resetDragonProfit") {
                    runs { _ ->
                        DragonProfitTracker.resetAll()
                        soulChat("§aDragon profit tracker wiped (session + persisted).")
                    }
                },
            )

            // Price cache diagnostics.
            then(
                literal("refreshPrices") {
                    runs { _ ->
                        val status = PriceCache.refreshNow()
                        soulChat("§aPrices refreshed: §f$status")
                        soulChat("§7${PriceCache.snapshot()}")
                    }
                },
            )
            // Inspect the cached price for a single item id — quickest way to tell whether
            // a profit-tracker row shows 0 because the cache hasn't populated yet, the
            // item id is wrong, or the item is genuinely worthless. Usage:
            //   /soul dev getPrice ASPECT_OF_THE_DRAGON
            //   /soul dev getPrice ENDER_DRAGON;4
            then(
                literal("getPrice") {
                    stringArg("itemId") { _, itemId ->
                        val buy = PriceCache.price(itemId, com.soulreturns.data.prices.PriceSource.BAZAAR_INSTANT_BUY)
                        val sell = PriceCache.price(itemId, com.soulreturns.data.prices.PriceSource.BAZAAR_INSTANT_SELL)
                        val bin = PriceCache.price(itemId, com.soulreturns.data.prices.PriceSource.LOWEST_BIN)
                        soulChat("§7Prices for §f$itemId§7:")
                        soulChat("  §7Bazaar buy:  §f${formatLong(buy)}")
                        soulChat("  §7Bazaar sell: §f${formatLong(sell)}")
                        soulChat("  §7Lowest BIN:  §f${formatLong(bin)}")
                    }
                },
            )

            // Sack-state diagnostics.
            then(
                literal("sackGet") {
                    stringArg("itemId") { _, itemId ->
                        val count = com.soulreturns.features.sacks.SackState.get(itemId)
                        soulChat("§7Sack[§f$itemId§7]: §a${formatLong(count)}")
                    }
                },
            )
            then(
                literal("sackDump") {
                    runs { _ ->
                        val all = com.soulreturns.features.sacks.SackState.getAll()
                        if (all.isEmpty()) {
                            soulChat("§7Sack state empty (active profile has no recorded items).")
                            return@runs
                        }
                        val top = all.entries.sortedByDescending { it.value }.take(30)
                        soulChat("§7Sack state — top ${top.size}/${all.size} items, active profile:")
                        for ((id, count) in top) {
                            soulChat("  §f$id §7→ §a${formatLong(count)}")
                        }
                    }
                },
            )
            then(
                literal("sackReset") {
                    runs { _ ->
                        com.soulreturns.features.sacks.SackState.resetActiveProfile()
                        soulChat("§aSack state wiped for active profile.")
                    }
                },
            )
            // Force a Hypixel /skyblock/profiles fetch for the current SkyBlock profile,
            // bypassing the wait for the next ProfileChanged event. Useful for verifying
            // the API loader without restarting / switching profiles.
            then(
                literal("loadProfile") {
                    runs { _ ->
                        val status = com.soulreturns.data.skyblock.SkyblockProfileLoader.loadActiveNow()
                        soulChat("§7Profile load: §f$status")
                    }
                },
            )
            // Item catalog diagnostics — print the in-memory snapshot size + last
            // backend-reported updatedAt timestamp. Useful for verifying the disk cache
            // loaded and the async fetch succeeded after launch.
            then(
                literal("catalogStatus") {
                    runs { _ ->
                        soulChat("§7${com.soulreturns.data.items.ItemCatalogClient.status()}")
                    }
                },
            )
            // Force a /items refetch without waiting for the next Mercure invalidate.
            then(
                literal("refreshCatalog") {
                    runs { _ ->
                        com.soulreturns.data.items.ItemCatalogClient.refreshAsync()
                        soulChat("§7Catalog refresh dispatched.")
                    }
                },
            )
        }
    }

    private fun formatLong(v: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%,d", v)

    private fun executeGrantDragonDrop(
        dragonTypeName: String,
        dropIdName: String,
        amount: Int,
    ) {
        val bucket =
            DragonType.byName(dragonTypeName.uppercase())
                ?: run {
                    val opts = DragonType.entries.joinToString(", ") { it.name }
                    soulChat("§cUnknown dragon type '§f$dragonTypeName§c'. Valid: §7$opts")
                    return
                }
        val drop =
            DragonDrop.byId(dropIdName.uppercase())
                ?: run {
                    val opts = DragonDrop.entries.filter { bucket in it.dragonTypes }.joinToString(", ") { it.name }
                    soulChat("§cUnknown drop id '§f$dropIdName§c'. Valid for ${bucket.displayName}: §7$opts")
                    return
                }
        DragonProfitTracker.grantDrop(bucket, drop, amount.toLong())
        soulChat("§aGranted §f$amount§a × §f${drop.displayName}§a to §f${bucket.displayName}§a.")
    }

    private fun executeGrantDragonDropAs(
        sourceName: String,
        dragonTypeName: String,
        dropIdName: String,
        amount: Int,
    ) {
        val source =
            runCatching { KillSource.valueOf(sourceName.uppercase()) }.getOrNull()
                ?: run {
                    val opts = KillSource.entries.joinToString(", ") { it.name }
                    soulChat("§cUnknown source '§f$sourceName§c'. Valid: §7$opts")
                    return
                }
        val bucket =
            DragonType.byName(dragonTypeName.uppercase())
                ?: run {
                    val opts = DragonType.entries.joinToString(", ") { it.name }
                    soulChat("§cUnknown dragon type '§f$dragonTypeName§c'. Valid: §7$opts")
                    return
                }
        val drop =
            DragonDrop.byId(dropIdName.uppercase())
                ?: run {
                    val opts = DragonDrop.entries.filter { bucket in it.dragonTypes }.joinToString(", ") { it.name }
                    soulChat("§cUnknown drop id '§f$dropIdName§c'. Valid for ${bucket.displayName}: §7$opts")
                    return
                }
        DragonProfitTracker.grantDrop(bucket, drop, amount.toLong(), source)
        soulChat(
            "§aGranted §f$amount§a × §f${drop.displayName}§a to §f${bucket.displayName}§a " +
                "as §f${source.displayName}§a.",
        )
    }

    private fun executeGrantDragonEye(
        dragonTypeName: String,
        count: Int,
    ) {
        val bucket =
            DragonType.byName(dragonTypeName.uppercase())
                ?: run {
                    val opts = DragonType.entries.joinToString(", ") { it.name }
                    soulChat("§cUnknown dragon type '§f$dragonTypeName§c'. Valid: §7$opts")
                    return
                }
        DragonProfitTracker.grantEye(bucket, count.toLong())
        soulChat("§aGranted §f$count§a Summoning Eye(s) to §f${bucket.displayName}§a.")
    }

    private fun executeGrantDragonKill(
        dragonTypeName: String,
        count: Int,
    ) {
        val bucket =
            DragonType.byName(dragonTypeName.uppercase())
                ?: run {
                    val opts = DragonType.entries.joinToString(", ") { it.name }
                    soulChat("§cUnknown dragon type '§f$dragonTypeName§c'. Valid: §7$opts")
                    return
                }
        DragonProfitTracker.grantKill(bucket, count.toLong())
        soulChat("§aGranted §f$count§a kill(s) for §f${bucket.displayName}§a.")
    }

    /**
     * Walk every owo-config option and write its declared default back into the live
     * wrapper, then persist + notify cloud sync. Same per-option API the config screen's
     * per-row reset chip uses, just applied wholesale via `forEachOption`. Failures on
     * individual options are swallowed and counted so a single bad type cast doesn't
     * abort the whole sweep — there's no useful recovery beyond "report what worked".
     */
    private fun resetConfigToDefaults() {
        if (!SoulConfigHolder.isConfigReady()) {
            soulChat("§cConfig wrapper not initialised yet — can't reset.")
            return
        }
        var ok = 0
        var failed = 0
        SoulConfigHolder.INSTANCE.forEachOption { opt ->
            try {
                @Suppress("UNCHECKED_CAST")
                (opt as Option<Any>).set(opt.defaultValue() as Any)
                ok++
            } catch (t: Throwable) {
                failed++
                DebugLogger.logFeatureEvent("resetConfig: failed on ${opt.key().asString()}: ${t.message}")
            }
        }
        try {
            SoulConfigHolder.INSTANCE.save()
        } catch (t: Throwable) {
            soulChat("§cReset wrote $ok defaults in memory but failed to save: ${t.message}")
            return
        }
        // Push to cloud sync so other devices on this account pick up the wipe. Skipped
        // silently if sync is disabled / not yet initialised.
        try {
            SyncEngine.notifyChanged(SyncKind.CONFIG)
        } catch (_: Throwable) {
        }
        val suffix = if (failed > 0) " §7($failed option${if (failed == 1) "" else "s"} failed, see soul-latest.log)" else ""
        soulChat("§aConfig reset — §f$ok§a option${if (ok == 1) "" else "s"} restored to defaults$suffix.")
    }

    private enum class MessageType { SERVER, PARTY, PUBLIC, GUILD }

    private fun sendTestMessage(
        type: MessageType,
        userMessage: String,
        withColor: Boolean
    ) {
        val formatted =
            when (type) {
                MessageType.SERVER -> if (withColor) "§e$userMessage" else userMessage
                MessageType.PARTY ->
                    if (withColor) {
                        "§9Party §8> §6[MVP§3++§6] TestPlayer§f: $userMessage"
                    } else {
                        "Party > [MVP++] TestPlayer: $userMessage"
                    }
                MessageType.PUBLIC ->
                    if (withColor) {
                        "§7[§a123§7] §6[MVP§c++§6] TestPlayer§f: $userMessage"
                    } else {
                        "[123] [MVP++] TestPlayer: $userMessage"
                    }
                MessageType.GUILD ->
                    if (withColor) {
                        "§2Guild §8> §6[MVP§3++§6] TestPlayer§f: $userMessage"
                    } else {
                        "Guild > [MVP++] TestPlayer: $userMessage"
                    }
            }
        Minecraft.getInstance().gui.chat.addMessage(Component.literal(formatted))
        DebugLogger.logSentMessage(formatted)
        MessageHandler.simulateMessage(formatted)
    }
}
