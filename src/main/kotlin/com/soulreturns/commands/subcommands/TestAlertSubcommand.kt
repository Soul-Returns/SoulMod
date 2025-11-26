package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.util.RenderUtils
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object TestAlertSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("testalert") {
            stringArg("message") { _, msg ->
                RenderUtils.showAlert(msg, 0xFFFF0000.toInt(), 2.0f, 5000)
                MinecraftClient.getInstance().player?.sendMessage(Text.literal("§aShowing alert: §r$msg"), false)
            }
            runs { _ ->
                RenderUtils.showAlert("Don Expresso is leaving in 1 minute!", 0xFFFF0000.toInt(), 2.0f, 5000)
                MinecraftClient.getInstance().player?.sendMessage(Text.literal("§aShowing Don Expresso alert!"), false)
            }
        }
    }
}

