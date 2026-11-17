package com.soulreturns.api

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import java.util.Locale

/**
 * Which Hypixel network the current connection is on. The Alpha network (`alpha.hypixel.net`) runs
 * copied and fake SkyBlock profiles, so nothing the game shows there may reach the Soul backend
 * (rule://backend, "Alpha gate"), and its player data lives in its own dataset
 * ([com.soulreturns.stats.PlayerDataset]).
 *
 * Any signal latches [ALPHA] for the rest of the connection: the server address, a mod-API `hello`
 * whose environment is not production, or the sidebar footer (`alpha.hypixel.net` instead of
 * `www.hypixel.net`). Only the footer confirms production ([dataset]): it names the network whatever
 * address the player typed. Cleared on disconnect.
 */
object HypixelNetwork {
    const val NONE = "none"
    const val PRODUCTION = "production"
    const val ALPHA = "alpha"

    private const val HYPIXEL_HOST = "hypixel.net"
    private const val ALPHA_HOST = "alpha.hypixel.net"

    /** Published when a connection turns Alpha; [signal] is `address`, `environment` or `sidebar`. */
    data class AlphaDetected(val signal: String) : Event

    /** Published once per connection when the sidebar footer confirms production. */
    data object ProductionConfirmed : Event

    /** [NONE], [PRODUCTION] or [ALPHA]. */
    @Volatile
    var current: String = NONE
        private set

    val isAlpha: Boolean get() = current == ALPHA

    /**
     * The player-data dataset of this connection: [ALPHA], [PRODUCTION], or null while a Hypixel
     * connection has not shown its production footer yet. Off Hypixel (and off any server) it is
     * [PRODUCTION].
     */
    val dataset: String?
        get() {
            val network = current
            return if (network == ALPHA) ALPHA else if (network == PRODUCTION && sidebar != PRODUCTION) null else PRODUCTION
        }

    /** Bumped each time a connection turns Alpha, so per-connection work can tell connections apart. */
    @Volatile
    var alphaConnection: Long = 0L
        private set

    private val lock = Any()
    private var address: String? = null
    private var environment: String? = null

    /** What the sidebar footer last said this connection; null until a footer was read. */
    @Volatile
    private var sidebar: String? = null

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

    /** The sidebar's lines, top to bottom, from the state reader's poll. A sidebar without a footer is no signal. */
    fun onSidebar(lines: List<String>) {
        val network = sidebarNetwork(lines) ?: return
        if (network == sidebar) return
        val confirmed = synchronized(lock) {
            sidebar = network
            network == PRODUCTION && current != ALPHA
        }
        reevaluate("sidebar")
        if (confirmed) Events.publish(ProductionConfirmed)
    }

    internal fun reset() {
        synchronized(lock) {
            address = null
            environment = null
            sidebar = null
            current = NONE
        }
    }

    private fun reevaluate(signal: String) {
        val entered = synchronized(lock) {
            val next = classify(current, address, environment, sidebar)
            val entered = next == ALPHA && current != ALPHA
            if (entered) alphaConnection++
            current = next
            entered
        }
        if (entered) Events.publish(AlphaDetected(signal))
    }

    /** The network for [address], the hello [environment] name and the [sidebar] footer's network; [previous] Alpha stays Alpha. */
    internal fun classify(previous: String, address: String?, environment: String?, sidebar: String? = null): String = when {
        previous == ALPHA || sidebar == ALPHA || isAlphaAddress(address) || isAlphaEnvironment(environment) -> ALPHA
        sidebar == PRODUCTION || isHypixelAddress(address) || environment != null -> PRODUCTION
        else -> NONE
    }

    /**
     * The network a sidebar's footer names: the bottom line reads `§ewww.hypixel.net` on production
     * and `§ealpha.hypixel.net` on Alpha. Color codes and spacing are ignored; any other line is no answer.
     */
    internal fun sidebarNetwork(lines: List<String>): String? {
        for (line in lines.asReversed()) {
            val text = stripCodes(line).filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }.lowercase(Locale.ROOT)
            if (text.isEmpty()) continue
            if (text == "www.$HYPIXEL_HOST") return PRODUCTION
            if (text == ALPHA_HOST) return ALPHA
        }
        return null
    }

    private fun stripCodes(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (line[i] == '§' && i + 1 < line.length) i += 2 else out.append(line[i++])
        }
        return out.toString()
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
