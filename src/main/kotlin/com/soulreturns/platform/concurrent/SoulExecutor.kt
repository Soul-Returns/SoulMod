package com.soulreturns.platform.concurrent

import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object SoulExecutor {
    private val logger = SoulLogger("Soul/Backend")

    private val factory =
        object : ThreadFactory {
            private val counter = AtomicInteger(0)

            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "soul-bg-${counter.incrementAndGet()}")
                t.isDaemon = true
                return t
            }
        }

    val executor = Executors.newFixedThreadPool(2, factory)

    /**
     * Debug-level backend trace (HTTP request URLs, cache hits, auth success).
     *
     * **Console:** gated on `dev.debug.debugMode AND dev.debug.logging.logBackend` — both must
     * be on. **File:** always written regardless of toggles, so the on-disk
     * `soul-latest.log` retains every backend trace for support reports. Only
     * `dev.debug.logToFile` (checked inside `SoulFileLog`) silences the file side.
     *
     * If config isn't ready yet (very first auth call runs before `SoulConfigHolder.init()`),
     * fall through to `logger.info` which handles its own startup-time gate.
     */
    fun log(msg: String) {
        val consoleOn =
            try {
                cfg.dev.debug.debugMode() && cfg.dev.debug.logging.logBackend()
            } catch (_: Throwable) {
                // Config not yet loaded — defer to SoulLogger's own startup-time fallback
                // by taking the "console on" branch.
                true
            }
        if (consoleOn) {
            // SoulLogger.info dispatches both SLF4J console and SoulFileLog tee.
            logger.info(msg)
        } else {
            // Toggles suppress console, but the file log should still receive every trace.
            com.soulreturns.util.SoulFileLog.offer("INFO", "Soul/Backend", msg, null)
        }
    }

    /** Warnings are always logged — they signal real problems, not noise. */
    fun warn(
        msg: String,
        t: Throwable? = null
    ) {
        if (t != null) logger.warn(msg, t) else logger.warn(msg)
    }
}
