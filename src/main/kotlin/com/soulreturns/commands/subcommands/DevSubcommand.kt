package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
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
