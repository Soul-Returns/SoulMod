package com.soulreturns.ui.hud.tracker

/**
 * Tab toggle shared by every [TrackerHud]. Two-tab trackers default to [Session] + [Total];
 * trackers that also track per-mayor-term ("event") buckets opt-in to [Event] via
 * [TrackerSpec.tabs].
 *
 *  - [Session] = in-memory counters that reset on client launch or via the tracker's reset
 *    button.
 *  - [Event] = persisted counters keyed by the current Hypixel mayor term — used by the
 *    Mythological mob tracker so totals reset cleanly when Diana cycles out. Older terms
 *    are retained in storage but only the active one is rendered.
 *  - [Total] = persisted all-time counters (per-profile for
 *    [com.soulreturns.stats.PersistentStats]-backed trackers, per-tracker JSON for everything
 *    else).
 *
 * The enum order doubles as the visual left-to-right order in the Tabs widget.
 */
enum class TrackerTab { Session, Event, Total }
