package com.soulreturns.util

import org.slf4j.LoggerFactory
import org.slf4j.helpers.MessageFormatter

/**
 * Logger wrapper. Two destinations per call:
 *
 *  1. **Console** (via SLF4J → Minecraft's Log4j) — `info`/`debug` are **gated on
 *     `cfg.dev.debug.debugMode()`**. With the master toggle off, the mod is silent on the
 *     console; users only see `WARN`/`ERROR` lines (real problems should always surface).
 *     Before the config wrapper loads, info/debug log freely — that captures the very
 *     first startup lines.
 *  2. **File** ([SoulFileLog]) — **always** receives every call regardless of `debugMode`,
 *     so `config/soul/logs/soul-latest.log` is a complete record of mod activity even when
 *     the user has debug off. Useful for support reports.
 *
 * Prepends `[tag]` to every console message so output is identifiable in launchers that
 * hide the logger name.
 */
class SoulLogger(val tag: String) {
    private val delegate = LoggerFactory.getLogger(tag)
    private val prefix = "[$tag]"

    fun info(msg: String) {
        if (consoleEnabled()) delegate.info("$prefix $msg")
        SoulFileLog.offer("INFO", tag, msg, null)
    }

    fun info(
        msg: String,
        vararg args: Any?
    ) {
        val formatted = fmt(msg, *args)
        if (consoleEnabled()) delegate.info("$prefix $formatted")
        SoulFileLog.offer("INFO", tag, formatted, null)
    }

    fun info(
        msg: String,
        t: Throwable
    ) {
        if (consoleEnabled()) delegate.info("$prefix $msg", t)
        SoulFileLog.offer("INFO", tag, msg, t)
    }

    fun debug(msg: String) {
        if (consoleEnabled()) delegate.debug("$prefix $msg")
        SoulFileLog.offer("DEBUG", tag, msg, null)
    }

    fun debug(
        msg: String,
        vararg args: Any?
    ) {
        val formatted = fmt(msg, *args)
        if (consoleEnabled()) delegate.debug("$prefix $formatted")
        SoulFileLog.offer("DEBUG", tag, formatted, null)
    }

    fun warn(msg: String) {
        delegate.warn("$prefix $msg")
        SoulFileLog.offer("WARN", tag, msg, null)
    }

    fun warn(
        msg: String,
        vararg args: Any?
    ) {
        val formatted = fmt(msg, *args)
        delegate.warn("$prefix $formatted")
        SoulFileLog.offer("WARN", tag, formatted, null)
    }

    fun warn(
        msg: String,
        t: Throwable
    ) {
        delegate.warn("$prefix $msg", t)
        SoulFileLog.offer("WARN", tag, msg, t)
    }

    fun error(msg: String) {
        delegate.error("$prefix $msg")
        SoulFileLog.offer("ERROR", tag, msg, null)
    }

    fun error(
        msg: String,
        vararg args: Any?
    ) {
        val formatted = fmt(msg, *args)
        delegate.error("$prefix $formatted")
        SoulFileLog.offer("ERROR", tag, formatted, null)
    }

    fun error(
        msg: String,
        t: Throwable
    ) {
        delegate.error("$prefix $msg", t)
        SoulFileLog.offer("ERROR", tag, msg, t)
    }

    private fun fmt(
        pattern: String,
        vararg args: Any?
    ): String = MessageFormatter.arrayFormat(pattern, args).message

    private companion object {
        /**
         * Console gate for info/debug. Returns true when:
         *  - The config wrapper isn't loaded yet (early startup — log freely), OR
         *  - `debugMode` is on.
         * Any throw is treated as "log freely" so a bug in this check can't silence problems.
         */
        @JvmStatic
        fun consoleEnabled(): Boolean =
            try {
                !com.soulreturns.config.SoulConfigHolder.isConfigReady() ||
                    com.soulreturns.config.cfg.dev.debug.debugMode()
            } catch (_: Throwable) {
                true
            }
    }
}
