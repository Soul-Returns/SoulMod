package com.soulreturns.features.dev

import java.util.concurrent.ConcurrentHashMap

/**
 * What Soul itself thinks, by name, for the dev bridge's `soul` provider. A feature registers a
 * lambda returning a small structure (maps, lists, primitives, data classes; dumped reflectively
 * with a depth cap). Nothing is read until the bridge asks, on the client thread; with no bridge
 * installed a provider is one map entry.
 *
 * ```
 * DevState.register("metal_detector") { mapOf("center" to MetalDetector.center, …) }
 * ```
 */
object DevState {
    private val providers = ConcurrentHashMap<String, () -> Any?>()

    fun register(name: String, read: () -> Any?) {
        providers[name] = read
    }

    fun names(): List<String> = providers.keys.sorted()

    /** Client thread only. A provider that throws yields its error text instead. */
    fun read(name: String): Any? {
        val provider = providers[name] ?: return null
        return try {
            provider()
        } catch (t: Throwable) {
            mapOf("error" to t.toString())
        }
    }
}
