package com.soulreturns.command

import com.mojang.brigadier.context.CommandContext
import com.soulreturns.Soul
import com.soulreturns.config.ConfigGuiCloser
import io.github.notenoughupdates.moulconfig.common.IMinecraft
import io.github.notenoughupdates.moulconfig.common.text.StructuredText
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

object SoulCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
            dispatcher.register(
                ClientCommandManager.literal("soul")
                    .executes { context ->
                        execute(context)
                    }
            )
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
            dispatcher.register(
                ClientCommandManager.literal("test")
                    .executes { context ->
                        val test = Soul.configManager.config.instance.RenderCategory.hideHeldItemTooltip
                        IMinecraft.getInstance().sendChatMessage(StructuredText.of("Setting: $test"))
                        0
                    }
            )
        }
    }

    private fun execute(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.player.sendMessage(Text.literal("Opening Soul config..."), false)

        MinecraftClient.getInstance().send {
            val editor = Soul.configManager.config.getEditor()
            editor.setWide(false)

            // Open the MoulConfig GUI
            Soul.configManager.config.openConfigGui()

            // Tack the current screen and auto-save when it closes
            ConfigGuiCloser.watch(Soul.configManager, MinecraftClient.getInstance().currentScreen)
        }

        return 1 // Success
    }
}