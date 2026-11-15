package com.soulreturns.features.dungeons.leap

/** When the last open-screen packet came off the wire (netty thread, `ConnectionMixin`): the zero of the leap menu's timing log. */
object LeapTiming {
    @Volatile
    @JvmField
    var openPacketAt = 0L

    @JvmStatic
    fun onOpenPacket() {
        openPacketAt = System.currentTimeMillis()
    }
}
