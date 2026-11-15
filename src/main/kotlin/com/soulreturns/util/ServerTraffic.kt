package com.soulreturns.util

/** When the last packet from the server arrived (`ConnectionMixin`), to tell a silent server from a missing answer. */
object ServerTraffic {
    @JvmField
    @Volatile
    var lastInboundMs: Long = -1L
}
