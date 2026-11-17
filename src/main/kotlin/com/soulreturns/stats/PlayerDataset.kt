package com.soulreturns.stats

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which on-disk dataset the local player data belongs to. Hypixel's Alpha network runs copied
 * profiles, so everything game activity records there goes to `config/soul/alpha/` instead of
 * `config/soul/`: production files are never read into, or written from, an Alpha session.
 * `config.json` is shared; game-derived values in it check [confirmedProduction].
 *
 * The dataset follows [HypixelNetwork.dataset], the network and never the address string, so every
 * production address shares one dataset. A Hypixel connection stays unresolved until its sidebar
 * footer names the network; meanwhile [writable] is false and stores keep their changes in memory.
 * Production confirmed: the changes are written. Alpha detected: they are dropped together with the
 * production state and the Alpha dataset opens. A connection that ends unresolved counts as production.
 */
object PlayerDataset {
    const val PRODUCTION = HypixelNetwork.PRODUCTION
    const val ALPHA = HypixelNetwork.ALPHA

    /** A file-backed store of player data. Called on the client thread. */
    interface Store {
        /** Write unsaved changes to the open dataset now, on the calling thread. */
        fun flush()

        /** Drop the in-memory state, unsaved changes included, and read [dataset]'s files. */
        fun open(dataset: String)
    }

    /**
     * Published on the client thread when the connection's dataset becomes known: production
     * confirmed, or a switch ([switched]) to Alpha or back to production on disconnect.
     */
    data class Resolved(val dataset: String, val switched: Boolean) : Event

    private val logger = SoulLogger("Soul/Dataset")

    internal var base: () -> Path = { FabricLoader.getInstance().configDir.resolve("soul") }

    /** The dataset the stores have open. */
    @Volatile
    var active: String = PRODUCTION
        private set

    private val stores = CopyOnWriteArrayList<Store>()

    /** Production confirmed by the sidebar this connection (client thread). */
    private var confirmed = false

    /**
     * Between a connection's first JOIN and its DISCONNECT. Hypixel fires JOIN again on every server
     * transfer (queue limbo to lobby, warps) with no DISCONNECT in between: those are the same connection.
     */
    @Volatile
    private var connected = false

    private var subscribed = false

    /** How handlers reach the client thread; tests run them in place. */
    internal var onClientThread: (() -> Unit) -> Unit = { block ->
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) block() else mc.execute(block)
    }

    fun dir(dataset: String = active): Path = if (dataset == ALPHA) base().resolve(ALPHA) else base()

    fun file(name: String, dataset: String = active): Path = dir(dataset).resolve(name)

    /**
     * The open dataset is production and so is the connection, confirmed by the sidebar or off
     * Hypixel: game-derived values may go into shared files (`config.json`) and uploads may run.
     */
    fun confirmedProduction(): Boolean = active == PRODUCTION && HypixelNetwork.dataset == PRODUCTION

    /** Whether game-derived changes of the open dataset may reach its files now. */
    fun writable(): Boolean = HypixelNetwork.dataset == active

    fun attach(store: Store) {
        stores += store
    }

    /** Before [HypixelNetwork.register]: the JOIN flush and switch back must run before the address can switch to Alpha. */
    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> onJoin() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onDisconnect() }
        subscribe()
    }

    internal fun subscribe() {
        if (subscribed) return
        subscribed = true
        Events.subscribe<HypixelNetwork.ProductionConfirmed> {
            onClientThread {
                if (!confirmed && active == PRODUCTION) {
                    confirmed = true
                    Events.publish(Resolved(PRODUCTION, switched = false))
                }
            }
        }
        Events.subscribe<HypixelNetwork.AlphaDetected> { onClientThread { switchTo(ALPHA, keepUnsaved = confirmed) } }
    }

    /** Client thread. Only the first JOIN of a connection starts one; a transfer JOIN changes nothing. */
    internal fun onJoin() {
        if (connected) return
        connected = true
        confirmed = false
        // Everything unsaved so far belongs to the dataset already open; only this connection's changes wait.
        if (active == ALPHA) switchTo(PRODUCTION, keepUnsaved = true) else flushAll()
    }

    /**
     * Alpha stays open until the next connection's JOIN: the readers clear their Alpha state after this
     * handler (the tab's pet, for one, is written once more), and those last writes still belong to Alpha.
     */
    internal fun onDisconnect() {
        connected = false
        onClientThread {
            confirmed = false
            flushAll()
        }
    }

    /**
     * Close the open dataset (writing its unsaved changes when [keepUnsaved], else dropping them) and
     * open [dataset] in every store.
     */
    internal fun switchTo(dataset: String, keepUnsaved: Boolean) {
        if (dataset == active) return
        if (keepUnsaved) flushAll()
        logger.warn("Player data: switching from the $active dataset to the $dataset dataset (${dir(dataset)})")
        active = dataset
        for (store in stores) {
            try {
                store.open(dataset)
            } catch (t: Throwable) {
                logger.warn("A store failed to open the $dataset dataset", t)
            }
        }
        Events.publish(Resolved(dataset, switched = true))
    }

    private fun flushAll() {
        for (store in stores) {
            try {
                store.flush()
            } catch (t: Throwable) {
                logger.warn("A store failed to flush the $active dataset", t)
            }
        }
    }
}
