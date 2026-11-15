package com.soulreturns.util

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import org.slf4j.LoggerFactory
import org.slf4j.helpers.MessageFormatter

/**
 * Console logger wrapper.
 *
 * `info` / `debug` are gated on `cfg.dev.debugMode` so the mod is quiet on a fresh install;
 * `warn` / `error` always print. Before the config loads (early startup) info/debug log freely.
 * Prepends `[tag]` so output is identifiable in launchers that hide the logger name.
 *
 * NOTE: the legacy mod also teed every line to a dedicated `soul-latest.log` file. That
 * infrastructure was intentionally left out of this baseline — add it back when support
 * reporting matters.
 */
class SoulLogger(val tag: String) {
    private val delegate = LoggerFactory.getLogger(tag)
    private val prefix = "[$tag]"

    fun info(msg: String) {
        if (consoleEnabled()) delegate.info("$prefix $msg")
    }

    fun info(
        msg: String,
        vararg args: Any?,
    ) {
        if (consoleEnabled()) delegate.info("$prefix ${fmt(msg, *args)}")
    }

    fun debug(msg: String) {
        if (consoleEnabled()) delegate.debug("$prefix $msg")
    }

    fun warn(msg: String) {
        delegate.warn("$prefix $msg")
    }

    fun warn(
        msg: String,
        t: Throwable,
    ) {
        delegate.warn("$prefix $msg", t)
    }

    fun error(msg: String) {
        delegate.error("$prefix $msg")
    }

    fun error(
        msg: String,
        t: Throwable,
    ) {
        delegate.error("$prefix $msg", t)
    }

    private fun fmt(
        pattern: String,
        vararg args: Any?,
    ): String = MessageFormatter.arrayFormat(pattern, args).message

    private companion object {
        /** Log info/debug freely before config loads; otherwise honor the debug toggle. Any throw → log. */
        fun consoleEnabled(): Boolean =
            try {
                !ConfigManager.isReady() || cfg.dev.debugMode
            } catch (_: Throwable) {
                true
            }
    }
}
