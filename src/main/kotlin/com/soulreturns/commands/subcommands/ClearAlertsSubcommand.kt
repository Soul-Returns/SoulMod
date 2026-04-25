package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object ClearAlertsSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("clearalerts") {
            runs { _ ->
                RenderUtils.clearAlerts()
                soulChat("§aCleared all alerts!")
                DebugLogger.logSentMessage("Cleared all alerts!")
            }
        }
    }
}

