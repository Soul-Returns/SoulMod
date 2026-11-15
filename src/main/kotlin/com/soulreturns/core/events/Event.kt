package com.soulreturns.core.events

/**
 * Marker interface for everything published on the [Events] bus.
 *
 * Events are plain immutable data classes carrying the minimum context a subscriber needs.
 * Inheritance is not walked — subscribe to the concrete subclass you want.
 */
interface Event
