package com.soulreturns.platform.realtime

import com.soulreturns.core.events.Event

/**
 * Backend told us a sync blob has changed (admin edit, future propagation between accounts).
 * [SyncEngine][com.soulreturns.platform.sync.SyncEngine] subscribes and triggers an out-of-band
 * reconcile for the named kind so the user sees the change without waiting for the next periodic pull.
 */
data class SyncInvalidate(val kind: String) : Event

/**
 * Backend wants to show the user a message. Currently rendered as a transient top-center toast
 * by [BackendNotificationHud][com.soulreturns.ui.hud.BackendNotificationHud].
 *
 * @property message Plain text to display.
 * @property severity Free-form severity hint (`info`, `warning`, `error`). Unknown values fall
 *   back to `info` rendering.
 */
data class BackendNotification(
    val message: String,
    val severity: String = "info",
) : Event
