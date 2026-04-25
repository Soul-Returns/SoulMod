package com.soulreturns.util

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/** Send a chat message with the [Soul] prefix. Safe to call from any thread. */
fun soulChat(message: String) {
    MinecraftClient.getInstance().execute {
        MinecraftClient.getInstance().player?.sendMessage(
            Text.literal("§8[§6Soul§8]§r $message"), false
        )
    }
}
