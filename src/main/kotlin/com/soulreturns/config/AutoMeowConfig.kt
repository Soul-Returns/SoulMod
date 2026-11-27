package com.soulreturns.config

import kotlinx.serialization.Serializable

/**
 * Auto meow ([com.soulreturns.features.chat.AutoMeow], ticket S-70, spec `mocks/meow/`): a secret card
 * that answers "meow" with "meow". [unlocked] is the player's discovery (the `/soul` search or
 * `/soul meow`); until then the card exists nowhere in the menu.
 */
@Serializable
data class AutoMeowConfig(
    /** Found once, kept for good: the card shows in Everyday › Controls. */
    val unlocked: Boolean = false,
    /** The card's switch; unlocking turns it on. */
    val enabled: Boolean = false,
    val coop: Boolean = true,
    val party: Boolean = true,
    val privateMessages: Boolean = true,
    val guild: Boolean = false,
    val all: Boolean = false,
)
