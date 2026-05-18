package com.soulreturns.util

import com.soulreturns.config.cfg
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

/**
 * Prefix [body] with `[Soul] ` when [com.soulreturns.config.SoulConfigModel.Chat.prefixOutgoingMessages]
 * is on; pass it through unchanged otherwise. Used by the mod's **conversational**
 * announcements sent to `/pc` / `/ac` (Double Hook, Legion-on-death, Dragon Profit) so
 * other party / lobby members can tell the message came from a Soul mod user.
 *
 * **Protocol messages stay raw** by design — `!ptme` alerts and `x: N, y: N, z: N`
 * waypoint shares are part of an inter-mod contract (community convention parsed by
 * SkyHanni / Skytils / Patcher etc.); prefixing them would break those parsers. Those
 * features intentionally do not call this helper.
 *
 * Returns the original [body] (no allocation) when the toggle is off, so it's safe to
 * route every conversational send through this helper unconditionally.
 */
fun withOutgoingPrefix(body: String): String =
    if (cfg.general.chat.prefixOutgoingMessages()) "[Soul] $body" else body
