package com.soulreturns.util

import org.slf4j.LoggerFactory
import org.slf4j.helpers.MessageFormatter

/** Logger wrapper that prepends [tag] to every message so it shows in launchers that hide the logger name. */
class SoulLogger(val tag: String) {
    private val delegate = LoggerFactory.getLogger(tag)
    private val prefix = "[$tag]"

    fun info(msg: String) = delegate.info("$prefix $msg")
    fun info(msg: String, vararg args: Any?) = delegate.info("$prefix ${fmt(msg, *args)}")
    fun info(msg: String, t: Throwable) = delegate.info("$prefix $msg", t)
    fun debug(msg: String) = delegate.debug("$prefix $msg")
    fun debug(msg: String, vararg args: Any?) = delegate.debug("$prefix ${fmt(msg, *args)}")
    fun warn(msg: String) = delegate.warn("$prefix $msg")
    fun warn(msg: String, vararg args: Any?) = delegate.warn("$prefix ${fmt(msg, *args)}")
    fun warn(msg: String, t: Throwable) = delegate.warn("$prefix $msg", t)
    fun error(msg: String) = delegate.error("$prefix $msg")
    fun error(msg: String, vararg args: Any?) = delegate.error("$prefix ${fmt(msg, *args)}")
    fun error(msg: String, t: Throwable) = delegate.error("$prefix $msg", t)

    private fun fmt(pattern: String, vararg args: Any?) =
        MessageFormatter.arrayFormat(pattern, args).message
}
