package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/**
 * Published when the active Hypixel SkyBlock mayor changes (the API's `mayor.name` /
 * `mayor.election.year` pair differs from what we last saw).
 *
 * Trackers that bucket data per mayor term (Mythological mob tracker → Diana) subscribe to
 * this to seed a fresh `Event` bucket and freeze the previous one.
 */
data class MayorChanged(
    val previousKey: String?,
    val previousName: String?,
    val currentKey: String?,
    val currentName: String?,
) : Event
