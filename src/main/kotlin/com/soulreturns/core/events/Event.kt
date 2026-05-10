package com.soulreturns.core.events

/**
 * Marker interface for everything that can be published on the [Events] bus.
 *
 * Events should be **plain immutable data classes** carrying only the minimum context a
 * subscriber needs. If you find yourself adding mutable state to an event, you probably want
 * a domain `Api` instead — the event signals *that* something happened; the `Api` exposes
 * *the resulting state*.
 *
 * Conventional shape:
 * ```
 * data class AreaChanged(val previous: String?, val current: String?) : Event
 * ```
 *
 * Not `sealed` — Kotlin's sealed restriction is package-local, which would prevent us putting
 * the bus in `core/events/` and event subclasses anywhere else. Use detekt to police the
 * one-event-per-data-class shape if it ever drifts.
 */
interface Event
