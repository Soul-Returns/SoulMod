package com.soulreturns.features.dungeons.devices

import com.soulreturns.api.LocationApi
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonRunState

/**
 * The F7/M7 Goldor-phase device solvers (card Dungeons › Bosses › Device solvers). Display plus
 * blocking only: they never click, interact or send anything of their own.
 */
object DeviceSolvers {
    fun register() {
        SimonSays.register()
        Lights.register()
        ArrowAlign.register()
        Sharpshooter.register()
        GoldorPhase.register()
    }

    /** In a Floor 7 or Master Mode 7 run (the devices sit at fixed boss-room coordinates). */
    fun inF7Boss(): Boolean {
        if (!LocationApi.inDungeon) return false
        val f = DungeonRunState.floor
        return f == DungeonFloor.F7 || f == DungeonFloor.M7
    }
}
