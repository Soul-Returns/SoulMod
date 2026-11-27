package com.soulreturns.features.waypoints

import com.soulreturns.config.MobWaypointConfig
import com.soulreturns.config.cfg
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.animal.armadillo.Armadillo

/**
 * One mob kind [MobWaypoints] marks (rule://waypoints): everything a new mob needs is one of these,
 * its [MobWaypointConfig] field in `WaypointsConfig`, its card, and an entry in [MobWaypoints.defs].
 *
 * [matches] and [state] are only ever called on an entity the player sees (after `MobSpotter`'s
 * sight test), never on one it does not.
 */
class MobWaypointDef(
    /** DevState key and log name. */
    val id: String,
    /** Label on the waypoint and the share line's tag (`| Pangolin`); matched case-insensitively on receipt. */
    val name: String,
    /** The area (tab `Area:`) the mob lives in; scan, drawing and party shares run only there. Null = anywhere on SkyBlock. */
    val area: String?,
    val config: () -> MobWaypointConfig,
    val matches: (Entity) -> Boolean,
    /** A state word shown after the name and drawn in [MobWaypointConfig.stateColor]; null = the plain look. */
    val state: (Entity) -> String?,
)

/**
 * Torrhus Canyon's Pangolin, hunted with a lasso for its shards: a plain vanilla armadillo named
 * "Armadillo", no name-tag stand (capture 2026-11-27). It rolls in when a player comes close.
 */
object Pangolin {
    const val ROLLED_IN = "rolled in"

    val def =
        MobWaypointDef(
            id = "pangolin",
            name = "Pangolin",
            area = "Torrhus Canyon",
            config = { cfg.waypoints.pangolin },
            matches = { it is Armadillo },
            state = { e -> (e as? Armadillo)?.let { if (rolledIn(it.state.name)) ROLLED_IN else null } },
        )

    /**
     * Vanilla `Armadillo.ArmadilloState` by name (no `when` on the enum, AGENTS.md): ROLLING and SCARED
     * are rolled in; IDLE, UNROLLING and any state a later version adds are not.
     */
    fun rolledIn(state: String): Boolean = state == "ROLLING" || state == "SCARED"
}
