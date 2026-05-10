package com.soulreturns.data.model

import com.soulreturns.core.events.Event

/**
 * Published once each time the Harvest Feast chest GUI is opened and parseable.
 *
 *  - `total` follows the cumulative-aware decoder rules: the X of the in-progress milestone
 *    if one exists, `0` if every milestone shows `0/Y` (fresh event), or `null` when all
 *    milestones are maxed or in transition (caller should leave its count alone).
 *  - `targets` is the sorted ascending list of milestone Y values
 *    (e.g. `[5, 25, 75, 150, 250]`). Always populated when the menu was readable.
 */
data class HarvestFeastSnapshot(
    val total: Long?,
    val targets: List<Long>,
) : Event
