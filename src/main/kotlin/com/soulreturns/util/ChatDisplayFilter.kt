package com.soulreturns.util

import net.minecraft.network.chat.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Predicate registry consulted by `ChatComponentMixin` to hide a chat line's **display** without
 * cancelling it. The message still flows through the network + Fabric chat events (which fire
 * earlier, in the packet listener), so other mods can still read it — only the visual chat entry is
 * suppressed. This is the legacy mod's approach for `/party list` display suppression.
 */
object ChatDisplayFilter {
    private val predicates = CopyOnWriteArrayList<(Component) -> Boolean>()

    fun register(predicate: (Component) -> Boolean) {
        predicates.add(predicate)
    }

    /** True if any registered predicate wants [message] hidden from the chat GUI. Called from the mixin. */
    @JvmStatic
    fun shouldHide(message: Component): Boolean {
        for (p in predicates) {
            try {
                if (p(message)) return true
            } catch (_: Throwable) {
                // A misbehaving predicate must never break vanilla chat display.
            }
        }
        return false
    }
}
