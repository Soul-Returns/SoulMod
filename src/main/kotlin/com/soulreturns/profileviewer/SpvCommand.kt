package com.soulreturns.profileviewer

import com.mojang.brigadier.arguments.StringArgumentType
import com.soulreturns.profileviewer.service.ProfileViewerService
import com.soulreturns.util.DebugLogger
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback

object SpvCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("spv")
                    .then(
                        ClientCommandManager.argument("name", StringArgumentType.word())
                            .then(
                                ClientCommandManager.argument("profile", StringArgumentType.word())
                                    .executes { ctx ->
                                        DebugLogger.logCommandExecution(ctx.input)
                                        val name = StringArgumentType.getString(ctx, "name")
                                        val profile = StringArgumentType.getString(ctx, "profile")
                                        ProfileViewerService.openFor(name, profile)
                                        1
                                    }
                            )
                            .executes { ctx ->
                                DebugLogger.logCommandExecution(ctx.input)
                                val name = StringArgumentType.getString(ctx, "name")
                                ProfileViewerService.openFor(name, null)
                                1
                            }
                    )
                    .executes { ctx ->
                        DebugLogger.logCommandExecution(ctx.input)
                        val mc = net.minecraft.client.MinecraftClient.getInstance()
                        val self = mc.session?.username
                        if (self != null) {
                            ProfileViewerService.openFor(self, null)
                        } else {
                            ctx.source.sendError(net.minecraft.text.Text.literal("[SPV] Could not determine your username."))
                        }
                        1
                    }
            )
        }
    }
}
