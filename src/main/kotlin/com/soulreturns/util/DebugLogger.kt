package com.soulreturns.util

/**
 * Thin routing for verbose feature / chat diagnostics. Everything goes through [SoulLogger.debug]
 * so it's gated on the `debugMode` toggle. The legacy mod split these into file-only categories
 * with per-category sub-toggles — collapsed here until the baseline needs that granularity.
 */
object DebugLogger {
    private val logger = SoulLogger("Soul/Debug")

    fun logFeatureEvent(message: String) = logger.debug(message)

    fun logMessageHandler(message: String) = logger.debug(message)
}
