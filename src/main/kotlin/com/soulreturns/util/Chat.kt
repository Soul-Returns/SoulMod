package com.soulreturns.util

import com.soulreturns.config.cfg
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Send a client-side chat message with the [Soul] prefix. Safe to call from any thread. */
fun soulChat(message: String) {
    Minecraft.getInstance().execute {
        // 26.2: Player.displayClientMessage(Component, boolean) → sendSystemMessage(Component)
        // (the action-bar variant is sendOverlayMessage).
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§8[§6Soul§8]§r $message"),
        )
    }
}

/**
 * Prefix [body] with `[Soul] ` when the outgoing-prefix toggle is on; pass it through unchanged
 * otherwise. Used for **conversational** sends to `/pc` so other party members can tell the
 * message came from a Soul user. Protocol messages (`!ptme`, waypoint shares) must stay raw and
 * should not route through here.
 */
fun withOutgoingPrefix(body: String): String =
    if (cfg.general.prefixOutgoingMessages) "[Soul] $body" else body
