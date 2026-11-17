package com.soulreturns.api

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import java.util.Locale

/**
 * Which Hypixel network the current connection is on. The Alpha network (`alpha.hypixel.net`) runs
 * copied and fake SkyBlock profiles, so nothing the game shows there may reach the Soul backend
 * (rule://backend, "Alpha gate").
 *
 * Either signal latches [ALPHA] for the rest of the connection: the server address, or a mod-API
 * `hello` whose environment is not production. Cleared on disconnect.
 */
object HypixelNetwork {
    const val NONE = "none"
    const val PRODUCTION = "production"
    const val ALPHA = "alpha"

    private const val HYPIXEL_HOST = "hypixel.net"
    private const val ALPHA_HOST = "alpha.hypixel.net"

    /** Published when a connection turns Alpha; [signal] is `address` or `environment`. */
    data class AlphaDetected(val signal: String) : Event

    /** [NONE], [PRODUCTION] or [ALPHA]. */
    @Volatile
    var current: String = NONE
        private set

    val isAlpha: Boolean get() = current == ALPHA

    /** Bumped each time a connection turns Alpha, so per-connection work can tell connections apart. */
    @Volatile
    var alphaConnection: Long = 0L
        private set

    private val lock = Any()
    private var address: String? = null
    private var environment: String? = null

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, mc -> onAddress(mc.currentServer?.ip) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    /** `Minecraft.currentServer?.ip` of the connection. */
    fun onAddress(address: String?) {
        synchronized(lock) { this.address = address }
        reevaluate("address")
    }

    /** The mod API `hello` packet's `Environment` name ([HypixelModApi]). */
    fun onEnvironment(environment: String) {
        synchronized(lock) { this.environment = environment }
        reevaluate("environment")
    }

    internal fun reset() {
        synchronized(lock) {
            address = null
            environment = null
            current = NONE
        }
    }

    private fun reevaluate(signal: String) {
        val entered = synchronized(lock) {
            val next = classify(current, address, environment)
            val entered = next == ALPHA && current != ALPHA
            if (entered) alphaConnection++
            current = next
            entered
        }
        if (entered) Events.publish(AlphaDetected(signal))
    }

    /** The network for [address] and the hello [environment] name; [previous] Alpha stays Alpha. */
    internal fun classify(previous: String, address: String?, environment: String?): String = when {
        previous == ALPHA || isAlphaAddress(address) || isAlphaEnvironment(environment) -> ALPHA
        isHypixelAddress(address) || environment != null -> PRODUCTION
        else -> NONE
    }

    internal fun isAlphaAddress(address: String?): Boolean {
        val host = host(address) ?: return false
        return host == ALPHA_HOST || host.endsWith(".$ALPHA_HOST")
    }

    internal fun isHypixelAddress(address: String?): Boolean {
        val host = host(address) ?: return false
        return host == HYPIXEL_HOST || host.endsWith(".$HYPIXEL_HOST")
    }

    internal fun isAlphaEnvironment(environment: String?): Boolean =
        environment != null && !environment.equals("PRODUCTION", ignoreCase = true)

    /** A server-list address as a bare lowercase host: port, IPv6 brackets and the root dot removed. */
    internal fun host(address: String?): String? {
        val trimmed = address?.trim()?.lowercase(Locale.ROOT) ?: return null
        val bare = if (trimmed.startsWith("[")) {
            trimmed.substringAfter('[').substringBefore(']')
        } else if (trimmed.count { it == ':' } == 1) {
            trimmed.substringBefore(':')
        } else {
            trimmed
        }
        return bare.trimEnd('.').ifEmpty { null }
    }
}
