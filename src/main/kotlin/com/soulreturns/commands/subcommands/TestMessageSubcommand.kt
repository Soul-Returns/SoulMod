package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.util.MessageHandler
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object TestMessageSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("testmessage") {
            then(literal("server").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.SERVER, msg, false)
            })
            then(literal("serverColor").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.SERVER, msg, true)
            })
            then(literal("party").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.PARTY, msg, false)
            })
            then(literal("partyColor").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.PARTY, msg, true)
            })
            then(literal("public").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.PUBLIC, msg, false)
            })
            then(literal("publicColor").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.PUBLIC, msg, true)
            })
            then(literal("guild").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.GUILD, msg, false)
            })
            then(literal("guildColor").stringArg("message") { _, msg ->
                sendTestMessage(MessageType.GUILD, msg, true)
            })
        }
    }

    private enum class MessageType {
        SERVER, PARTY, PUBLIC, GUILD
    }

    private fun sendTestMessage(type: MessageType, userMessage: String, withColor: Boolean) {
        val formattedMessage = when (type) {
            MessageType.SERVER -> {
                if (withColor) {
                    "§e$userMessage"
                } else {
                    userMessage
                }
            }
            MessageType.PARTY -> {
                if (withColor) {
                    "§9Party §8> §6[MVP§3++§6] TestPlayer§f: $userMessage"
                } else {
                    "Party > [MVP++] TestPlayer: $userMessage"
                }
            }
            MessageType.PUBLIC -> {
                if (withColor) {
                    "§7[§a123§7] §6[MVP§c++§6] TestPlayer§f: $userMessage"
                } else {
                    "[123] [MVP++] TestPlayer: $userMessage"
                }
            }
            MessageType.GUILD -> {
                if (withColor) {
                    "§2Guild §8> §6[MVP§3++§6] TestPlayer§f: $userMessage"
                } else {
                    "Guild > [MVP++] TestPlayer: $userMessage"
                }
            }
        }

        // Send the formatted message to chat for visibility
        val minecraft = MinecraftClient.getInstance()
        minecraft.inGameHud.chatHud.addMessage(Text.literal(formattedMessage))

        // Process the message through MessageHandler to trigger handlers
        MessageHandler.simulateMessage(formattedMessage)
    }
}

