package com.soulreturns.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object TestAlertCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            registerCommand(dispatcher)
        }
    }

    private fun registerCommand(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            ClientCommandManager.literal("testalert")
                .then(
                    ClientCommandManager.argument("message", StringArgumentType.greedyString())
                        .executes { context ->
                            DebugLogger.logCommandExecution(context.input)
                            val message = StringArgumentType.getString(context, "message")
                            RenderUtils.showAlert(message, 0xFFFF0000.toInt(), 2.0f, 5000) // Show for 5 seconds at 2x size
                            soulChat("§aShowing alert: §r$message")
                            DebugLogger.logSentMessage("Showing alert: $message")
                            1
                        }
                )
                .executes { context ->
                    DebugLogger.logCommandExecution(context.input)
                    // Default test message
                    RenderUtils.showAlert("Don Expresso is leaving in 1 minute!", 0xFFFF0000.toInt(), 2.0f, 5000)
                    soulChat("§aShowing Don Expresso alert!")
                    DebugLogger.logSentMessage("Showing Don Expresso alert!")
                    1
                }
        )

        dispatcher.register(
            ClientCommandManager.literal("clearalerts")
                .executes { context ->
                    DebugLogger.logCommandExecution(context.input)
                    RenderUtils.clearAlerts()
                    soulChat("§aCleared all alerts!")
                    DebugLogger.logSentMessage("Cleared all alerts!")
                    1
                }
        )
    }
}

