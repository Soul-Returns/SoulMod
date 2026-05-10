package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.config.gui.SoulConfigScreen
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/**
 * Opens the config screen via "/soul config", optionally with a pre-filled search:
 * "/soul config <search>".
 */
object ConfigSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("config") {
            runs { _ -> openConfig("") }
            stringArg("search") { _, search -> openConfig(search) }
        }
    }

    private fun openConfig(search: String) {
        val mc = Minecraft.getInstance()
        mc.schedule { mc.setScreen(SoulConfigScreen(search)) }
    }
}
