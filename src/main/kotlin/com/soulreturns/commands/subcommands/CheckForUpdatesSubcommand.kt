package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.update.UpdateChecker
import com.soulreturns.update.UpdateModal
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient

object CheckForUpdatesSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("checkForUpdates") {
            runs { _ ->
                val mc = MinecraftClient.getInstance()
                soulChat("§7Checking for updates...")
                UpdateChecker.checkNow { info ->
                    if (info != null) {
                        UpdateModal.dismissed = false
                        mc.execute { mc.setScreen(UpdateModal(info)) }
                    } else {
                        soulChat("§aNo update available.")
                    }
                }
            }
        }
    }
}
