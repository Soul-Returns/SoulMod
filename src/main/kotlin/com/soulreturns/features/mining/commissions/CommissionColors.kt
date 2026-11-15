package com.soulreturns.features.mining.commissions

import java.util.Locale

/** Commissions HUD name colors by keyword (user's choice); white is taken by titanium, so the rest is gray. */
object CommissionColors {
    const val RED = 0xFFFF5555.toInt() // §c
    const val YELLOW = 0xFFFFFF55.toInt() // §e
    const val WHITE = 0xFFFFFFFF.toInt() // §f
    const val GREEN = 0xFF55FF55.toInt() // §a
    const val OTHER = 0xFFAAAAAA.toInt() // §7

    /** First keyword wins, in this order. */
    private val KEYWORDS = listOf("goblin" to RED, "raffle" to YELLOW, "titanium" to WHITE, "mithril" to GREEN)

    fun forName(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return KEYWORDS.firstOrNull { lower.contains(it.first) }?.second ?: OTHER
    }
}
