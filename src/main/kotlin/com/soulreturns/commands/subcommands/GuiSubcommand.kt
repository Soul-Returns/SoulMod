package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.gui.GuiEditScreen
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/**
 * Opens the GUI edit screen via "/soul gui".
 */
object GuiSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("gui") {
            runs { _ ->
                val client = Minecraft.getInstance()
                client.schedule {
                    client.setScreen(GuiEditScreen())
                }
            }
        }
    }
}
