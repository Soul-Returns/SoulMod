package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.profile.ProfileApi
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.features.farming.seasoning.SeasoningTracker
import com.soulreturns.platform.sync.SyncEngine
import com.soulreturns.platform.sync.SyncKind
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.soulChat
import io.wispforest.owo.config.Option
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
        }
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
