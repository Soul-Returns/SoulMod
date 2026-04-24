package com.soulreturns.command

import com.mojang.brigadier.context.CommandContext
import com.soulreturns.commands.subcommands.ClearAlertsSubcommand
import com.soulreturns.commands.subcommands.GuiSubcommand
import com.soulreturns.commands.subcommands.TestAlertSubcommand
import com.soulreturns.commands.subcommands.TestMessageSubcommand
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.util.DebugLogger
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient

object SoulCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("soul")
                    .executes { context ->
                        DebugLogger.logCommandExecution(context.input)
                        execute(context)
                    }
                    .then(TestMessageSubcommand.register())
                    .then(TestAlertSubcommand.register())
                    .then(ClearAlertsSubcommand.register())
                    .then(GuiSubcommand.register())
            )
        }
    }

    private fun execute(context: CommandContext<FabricClientCommandSource>): Int {
        val mc = MinecraftClient.getInstance()
        mc.send {
            mc.setScreen(SoulConfigScreen())
        }
        return 1
    }
}
