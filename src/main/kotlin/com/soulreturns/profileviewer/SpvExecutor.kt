package com.soulreturns.profileviewer

import com.soulreturns.config.cfg
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.SoulFileLog
import com.soulreturns.util.SoulLogger

/**
 * SPV-specific logging wrapper around [SoulExecutor]. Uses the `[SPV]` tag.
 *
 * **Console** info is gated on `dev.debug.debugMode`; **file** logging always fires so
 * `soul-latest.log` retains every SPV trace for support reports. See the comment on
 * [SoulExecutor.log] for the rationale.
 */
object SpvExecutor {
    private val logger = SoulLogger("Soul/SPV")

    val executor get() = SoulExecutor.executor

    fun log(msg: String) {
        val full = "[SPV] $msg"
        val consoleOn =
            try {
                cfg.dev.debug.debugMode()
            } catch (_: Throwable) {
                true
            }
        if (consoleOn) {
            logger.info(full)
        } else {
            SoulFileLog.offer("INFO", "Soul/SPV", full, null)
        }
    }

    fun warn(
        msg: String,
        t: Throwable? = null
    ) {
        if (t != null) logger.warn("[SPV] $msg", t) else logger.warn("[SPV] $msg")
    }
}
