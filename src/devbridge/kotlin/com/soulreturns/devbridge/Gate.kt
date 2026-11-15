package com.soulreturns.devbridge

import org.slf4j.LoggerFactory

/**
 * The only bridge class the mixins touch before anything else, and it references nothing from
 * Soul: while the bridge is disabled (Soul speaks another bridge API) no class that links against
 * Soul is ever loaded, so a mismatched pair can never crash the game.
 */
object Gate {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")

    /** The bridge runs (compatible Soul, no linkage failure since). */
    @JvmField
    @Volatile
    var on = false

    /** Some stream or recording is attached; everything optional checks this first. */
    @JvmField
    @Volatile
    var listening = false

    @Volatile
    var failure: String? = null
        private set

    /** Runs [block] when on; a linkage error (Soul changed under the bridge) turns the bridge off for good. */
    @JvmStatic
    fun run(block: Runnable) {
        if (!on) return
        try {
            block.run()
        } catch (e: LinkageError) {
            fail(e)
        } catch (t: Throwable) {
            logger.warn("[Soul/DevBridge] hook failed: $t")
        }
    }

    fun fail(e: Throwable) {
        if (!on) return
        on = false
        listening = false
        failure = "disabled after a linkage error ($e): this Soul build changed the bridge API without a version bump; reinstall the bridge (tools/devbridge/install)"
        logger.error("[Soul/DevBridge] $failure")
    }
}
