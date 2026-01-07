package com.soulreturns.config

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.ui.DynamicOptionLabelRegistry
import com.soulreturns.features.party.PartyManager

/**
 * Central place to register dynamic label providers for config options.
 */
object DynamicLabelProviders {
    fun registerAll() {
        // Bobbin Time: "Sync with party (N)" where N = (party size - 1) capped at 5.
        DynamicOptionLabelRegistry.register("bobbin_sync_party") { option: OptionData ->
            val partySize = try {
                PartyManager.getPartySize()
            } catch (_: Throwable) {
                0
            }
            val threshold = if (partySize > 0) {
                (partySize - 1).coerceIn(1, 5)
            } else {
                1
            }
            "${option.name} ($threshold)"
        }
    }
}
