package com.soulreturns.platform.realtime

import com.soulreturns.core.events.Event

/**
 * The backend says a body of data it owns has changed and should be re-read.
 *
 * **Filter on [topic] first, then [kind].** Today every `sync-invalidate` arrives on
 * `sync/catalogs` and carries a global catalog kind (`sea_creatures`, `items`, `drops`, …) — the
 * per-player `user/<uuid>` variant belonged to settings sync, which was **removed from the backend
 * on 2026-08-20** and is not coming back (see the mod's CLAUDE.md). That topic now carries only
 * `notification`.
 *
 * The [topic] field survives that removal deliberately: the same event *type* is the backend's
 * generic "re-read this" signal, so a future per-player or per-feature invalidate would reuse it
 * with a vocabulary of its own. A consumer that matches on [kind] alone cannot tell two such
 * vocabularies apart, and the legacy mod's sync engine logged "unknown kind" on every catalog
 * invalidate for exactly that reason. Ignore another topic's kinds **silently** — they are not
 * errors, they are someone else's mail.
 *
 * @property topic the Mercure topic it arrived on, or null if the hub/backend did not tell us.
 */
data class SyncInvalidate(val topic: String?, val kind: String) : Event {
    /** True when this invalidate came from the global catalog topic — the only source there is. */
    fun isCatalog(): Boolean = topic == "sync/catalogs"
}

/**
 * The backend wants to show the player a message (`POST /admin/players/{uuid}/notify`).
 *
 * @property message plain text, ≤ 500 chars (enforced backend-side).
 * @property severity free-form hint — `error`, `warning`/`warn`, anything else renders as info.
 */
data class BackendNotification(
    val message: String,
    val severity: String = "info",
) : Event
