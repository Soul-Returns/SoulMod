package com.soulreturns.platform.sync

/**
 * Identifies a single mirrorable artifact for [SyncEngine]. The [key] is part of the
 * backend URL path (`/sync/{key}`); the [label] is shown in user-facing messages.
 */
enum class SyncKind(val key: String, val label: String) {
    CONFIG("config", "Config"),
    GUI_LAYOUT("gui_layout", "GUI Layout"),
    STATS("stats", "Stats"),
}
