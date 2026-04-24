package com.soulreturns.api

import com.soulreturns.config.cfg
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object SoulExecutor {
    private val logger = LoggerFactory.getLogger("SoulMod/Backend")

    private val factory = object : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "soul-bg-${counter.incrementAndGet()}")
            t.isDaemon = true
            return t
        }
    }

    val executor = Executors.newFixedThreadPool(2, factory)

    fun log(msg: String) {
        try {
            if (cfg.debug.debugMode()) {
                logger.info(msg)
            }
        } catch (_: Throwable) {
            logger.info(msg)
        }
    }

    fun warn(msg: String, t: Throwable? = null) {
        if (t != null) logger.warn(msg, t) else logger.warn(msg)
    }
}
