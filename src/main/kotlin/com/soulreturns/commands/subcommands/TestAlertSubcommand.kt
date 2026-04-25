package com.soulreturns.commands.subcommands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object TestAlertSubcommand : SoulSubcommand {

    override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("testalert") {
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
        }
    }
}

