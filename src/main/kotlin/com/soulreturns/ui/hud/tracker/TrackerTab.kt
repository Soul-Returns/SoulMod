package com.soulreturns.ui.hud.tracker

/**
 * Two-tab toggle shared by every [TrackerHud] — Session = in-memory counters that reset on
 * client launch or via the tracker's reset button; Total = persisted counters (per-profile
 * for [com.soulreturns.stats.PersistentStats]-backed trackers, per-tracker JSON for profit
 * trackers).
 *
 * The enum order doubles as the visual left-to-right order in the Tabs widget.
 */
enum class TrackerTab { Session, Total }
