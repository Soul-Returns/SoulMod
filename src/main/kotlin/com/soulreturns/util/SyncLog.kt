package com.soulreturns.util

import com.soulreturns.config.cfg
import org.slf4j.LoggerFactory

/**
 * Verbose trace of every realtime / secret-sync action, gated on the `dev.logWebsocket` config
 * toggle (Dev → Logging → "Sync / WebSocket logging"). Logs at INFO via SLF4J directly, so it is
 * **independent of `dev.debugMode`** — flipping this one toggle gives a full, isolated picture of
 * what each channel is doing (auth, connect, subscribe, publish, inbound frames, apply decisions).
 *
 * Real errors still go through each class's `SoulLogger.warn` (always visible). This is only the
 * high-volume action trace.
 */
object SyncLog {
    private val log = LoggerFactory.getLogger("Soul/Sync")

    fun enabled(): Boolean = try {
        cfg.dev.logWebsocket
    } catch (_: Throwable) {
        false
    }

    /** [channel] is a short tag like `realtime`, `backend`, `devonian`, `auth`, `handler`. */
    fun ws(channel: String, msg: String) {
        if (enabled()) log.info("[Soul/Sync/{}] {}", channel, msg)
    }
}
