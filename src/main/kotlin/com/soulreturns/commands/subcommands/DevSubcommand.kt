package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.data.profile.ProfileApi
import com.soulreturns.features.farming.seasoning.SeasoningTracker
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.SkyblockLocation
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Developer-facing diagnostic commands.
 *
 *  - `/soul dev getArea`                                  → SkyBlock island from the tab list
 *  - `/soul dev getSubLocation`                           → sublocation from the scoreboard sidebar
 *  - `/soul dev getProfile`                               → active SkyBlock profile from the tab list
 *  - `/soul dev listStatProfiles`                         → all profile slots in stats.json, marking the active one
 *  - `/soul dev resetSeasonings`                          → reset seasoning total to 0 (persisted)
 *  - `/soul dev clearAlerts`                              → wipe in-flight alert overlays
 *  - `/soul dev testAlert [<message>]`                    → render a test alert
 *  - `/soul dev testMessage <type> <message>`             → simulate an incoming chat line of [type]
 *      types: `server`, `serverColor`, `party`, `partyColor`, `public`, `publicColor`, `guild`, `guildColor`
 */
object DevSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("dev") {
            // Location helpers
            then(literal("getArea") {
                runs { _ ->
                    val area = SkyblockLocation.area
                    soulChat(if (area == null) "§7Area: §c<unknown>" else "§7Area: §a$area")
                }
            })
            then(literal("getSubLocation") {
                runs { _ ->
                    val sub = SkyblockLocation.sublocation
                    soulChat(if (sub == null) "§7Sublocation: §c<unknown>" else "§7Sublocation: §a$sub")
                }
            })
            then(literal("getProfile") {
                runs { _ ->
                    val p = ProfileApi.currentProfile
                    soulChat(if (p == null) "§7Profile: §c<unknown>" else "§7Profile: §a$p")
                }
            })
            then(literal("listStatProfiles") {
                runs { _ ->
                    val active = ProfileApi.currentProfile
                    val known = PersistentStats.knownProfiles()
                    if (known.isEmpty()) {
                        soulChat("§7No persisted stat profiles yet.")
                    } else {
                        soulChat("§7Stat profiles (§a${known.size}§7):")
                        known.sorted().forEach { key ->
                            val marker = when {
                                key == PersistentStats.LEGACY_KEY -> " §8(pre-profile bucket)"
                                key == active                     -> " §a(active)"
                                else                              -> ""
                            }
                            soulChat("  §7- §f$key$marker")
                        }
                    }
                }
            })

            // Persisted-stat resets
            then(literal("resetSeasonings") {
                runs { _ ->
                    SeasoningTracker.reset()
                    soulChat("§aSeasonings counter reset to §f0§a (persisted to stats.json).")
                }
            })

            // Alerts
            then(literal("clearAlerts") {
                runs { _ ->
                    RenderUtils.clearAlerts()
                    soulChat("§aCleared all alerts!")
                    DebugLogger.logSentMessage("Cleared all alerts!")
                }
            })
            then(literal("testAlert") {
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
            })

            // Test chat-message simulation
            then(literal("testMessage") {
                then(literal("server").stringArg("message")      { _, msg -> sendTestMessage(MessageType.SERVER, msg, false) })
                then(literal("serverColor").stringArg("message") { _, msg -> sendTestMessage(MessageType.SERVER, msg, true)  })
                then(literal("party").stringArg("message")       { _, msg -> sendTestMessage(MessageType.PARTY,  msg, false) })
                then(literal("partyColor").stringArg("message")  { _, msg -> sendTestMessage(MessageType.PARTY,  msg, true)  })
                then(literal("public").stringArg("message")      { _, msg -> sendTestMessage(MessageType.PUBLIC, msg, false) })
                then(literal("publicColor").stringArg("message") { _, msg -> sendTestMessage(MessageType.PUBLIC, msg, true)  })
                then(literal("guild").stringArg("message")       { _, msg -> sendTestMessage(MessageType.GUILD,  msg, false) })
                then(literal("guildColor").stringArg("message")  { _, msg -> sendTestMessage(MessageType.GUILD,  msg, true)  })
            })
        }
    }

    private enum class MessageType { SERVER, PARTY, PUBLIC, GUILD }

    private fun sendTestMessage(type: MessageType, userMessage: String, withColor: Boolean) {
        val formatted = when (type) {
            MessageType.SERVER -> if (withColor) "§e$userMessage" else userMessage
            MessageType.PARTY -> if (withColor)
                "§9Party §8> §6[MVP§3++§6] TestPlayer§f: $userMessage"
                else "Party > [MVP++] TestPlayer: $userMessage"
            MessageType.PUBLIC -> if (withColor)
                "§7[§a123§7] §6[MVP§c++§6] TestPlayer§f: $userMessage"
                else "[123] [MVP++] TestPlayer: $userMessage"
            MessageType.GUILD -> if (withColor)
                "§2Guild §8> §6[MVP§3++§6] TestPlayer§f: $userMessage"
                else "Guild > [MVP++] TestPlayer: $userMessage"
        }
        Minecraft.getInstance().gui.chat.addMessage(Component.literal(formatted))
        DebugLogger.logSentMessage(formatted)
        MessageHandler.simulateMessage(formatted)
    }
}
