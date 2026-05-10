package com.soulreturns.commands

import com.mojang.brigadier.context.CommandContext
import com.soulreturns.commands.subcommands.CheckForUpdatesSubcommand
import com.soulreturns.commands.subcommands.ConfigSubcommand
import com.soulreturns.commands.subcommands.DevSubcommand
import com.soulreturns.commands.subcommands.GuiSubcommand
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.util.DebugLogger
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

object SoulCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("soul")
                    .executes { context ->
                        DebugLogger.logCommandExecution(context.input)
                        execute(context)
                    }
                    .then(GuiSubcommand.register())
                    .then(CheckForUpdatesSubcommand.register())
                    .then(ConfigSubcommand.register())
                    .then(DevSubcommand.register())
            )
        }
    }

    private fun execute(context: CommandContext<FabricClientCommandSource>): Int {
        val mc = Minecraft.getInstance()
        mc.schedule {
            mc.setScreen(SoulConfigScreen())
        }
        return 1
    }
}
