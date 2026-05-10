package com.soulreturns.util

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Send a chat message with the [Soul] prefix. Safe to call from any thread. */
fun soulChat(message: String) {
    Minecraft.getInstance().execute {
        Minecraft.getInstance().player?.displayClientMessage(
            Component.literal("§8[§6Soul§8]§r $message"),
            false
        )
    }
}
