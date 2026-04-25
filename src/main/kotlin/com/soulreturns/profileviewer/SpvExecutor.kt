package com.soulreturns.profileviewer

import com.soulreturns.api.SoulExecutor
import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger

/** SPV-specific logging wrapper around [SoulExecutor]. Uses the [SPV] tag. */
object SpvExecutor {
    private val logger = SoulLogger("Soul/SPV")

    val executor get() = SoulExecutor.executor

    fun log(msg: String) {
        try {
            if (cfg.debug.debugMode()) {
                logger.info("[SPV] $msg")
            }
        } catch (_: Throwable) {
            logger.info("[SPV] $msg")
        }
    }

    fun warn(msg: String, t: Throwable? = null) {
        if (t != null) logger.warn("[SPV] $msg", t) else logger.warn("[SPV] $msg")
    }
}
