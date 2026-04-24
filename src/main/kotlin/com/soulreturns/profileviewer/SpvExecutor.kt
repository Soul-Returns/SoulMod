package com.soulreturns.profileviewer

import com.soulreturns.Soul
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object SpvExecutor {
    private val logger = LoggerFactory.getLogger("SoulMod/SPV")

    private val factory = object : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "soul-spv-${counter.incrementAndGet()}")
            t.isDaemon = true
            return t
        }
    }

    val executor = Executors.newFixedThreadPool(2, factory)

    fun log(msg: String) {
        try {
            if (Soul.configManager.config.instance.debugCategory.debugMode) {
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
