package com.soulreturns.util

import com.soulreturns.config.cfg

/**
 * Category-gated debug logger.
 *
 * **Console:** suppressed unless `dev.debug.debugMode` AND the per-category predicate are both
 * true. Matches the rest of the codebase where `debugMode` is the master mod-to-console gate.
 *
 * **File:** entries always reach [SoulFileLog] regardless of the toggles — the on-disk log at
 * `config/soul/logs/soul-latest.log` is the support-bundle source of truth, so it should
 * contain every event we'd otherwise filter out. Only `dev.debug.logToFile` can turn the file
 * tee off, and that's checked inside `SoulFileLog` itself.
 */
object DebugLogger {
    private val logger = SoulLogger("Soul")
    private const val TAG = "Soul"

    private inline fun log(
        prefix: String,
        message: String,
        predicate: () -> Boolean,
    ) {
        val full = "[$prefix] $message"
        val consoleOn =
            try {
                cfg.dev.debug.debugMode() && predicate()
            } catch (_: Throwable) {
                // Config not ready (early startup) — log freely; SoulLogger.info handles the
                // same fallback for the console gate.
                true
            }
        if (consoleOn) {
            // SoulLogger.info dispatches to both SLF4J console + SoulFileLog.
            logger.info(full)
        } else {
            // Console suppressed by toggles, but the file log still gets the entry.
            SoulFileLog.offer("INFO", TAG, full, null)
        }
    }

    fun logConfigChange(message: String) = log("Config", message) { cfg.dev.debug.logging.logConfigChanges() }

    fun logWidgetInteraction(message: String) = log("Widget", message) { cfg.dev.debug.logging.logWidgetInteractions() }

    fun logFeatureEvent(message: String) = log("Feature", message) { cfg.dev.debug.logging.logFeatureEvents() }

    fun logGuiLayout(message: String) = log("GuiLayout", message) { cfg.dev.debug.logging.logGuiLayout() }

    // ── Message-category helpers ──
    //
    // Chat traffic + command execution. These NEVER reach the console — high-volume, low
    // signal, and there's no console toggle to flip them on. They land in `soul-latest.log`
    // only when `dev.debug.includeMessagesInLog` is enabled (default off). Default-off
    // because actively playing emits hundreds of these lines per minute.

    fun logMessageHandler(message: String) = logMessage("Message", message)

    fun logCommandExecution(commandInput: String) = logMessage("Command", commandInput)

    fun logSentMessage(message: String) = logMessage("SentMessage", message)

    fun logChatInput(input: String) = logMessage("ChatInput", input)

    private fun logMessage(
        prefix: String,
        message: String,
    ) {
        val include =
            try {
                cfg.dev.debug.includeMessagesInLog()
            } catch (_: Throwable) {
                // Config not ready — drop the line; this is a stay-quiet category by default.
                false
            }
        if (!include) return
        SoulFileLog.offer("INFO", TAG, "[$prefix] $message", null)
    }
}
