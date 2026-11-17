package com.soulreturns.platform.presence

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.HypixelNetwork
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.OnSkyblockChanged
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.data.skyblock.SubLocationChanged
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The body of `POST /presence/state`. Every key is sent on every request; `null` is legitimate and
 * the backend never 4xxes on field content (it coerces: `server` ≤ 255 chars, `area` /
 * `subLocation` / `profile` ≤ 64, non-string or empty ⇒ stored as null).
 *
 * **No property has a default on purpose.** `kotlinx.serialization` omits a property whose value
 * equals its declared default (`encodeDefaults = false`), so giving these `= null` would silently
 * drop exactly the keys the contract says are always present. Without defaults, every key is always
 * encoded.
 *
 * [event] stays a plain `String` rather than an enum: the wire values are ours, unknown ones are
 * treated as `transition` by the backend, and a subject `when` over a Kotlin enum emits the
 * `$WhenMappings` class that Fabric's KnotClassLoader can fail to resolve (see CLAUDE.md).
 */
@Serializable
data class PresenceState(
    /**
     * Where the client is: the server **address** (`mc.hypixel.net`), `singleplayer` for a local
     * world, or **null for the main menu**. Never the Hypixel instance id.
     */
    val server: String?,
    val onSkyblock: Boolean?,
    val area: String?,
    val subLocation: String?,
    val profile: String?,
    /** [PresenceService.EVENT_TRANSITION], [PresenceService.EVENT_HEARTBEAT] or [PresenceService.EVENT_DISCONNECT]. */
    val event: String,
    /**
     * Whether other Soul users may see stats Soul collected about this player
     * ([com.soulreturns.config.ProfileViewerConfig.shareSoulStats], read at push time).
     *
     * An **additive** field on a contract that was already live: the backend leaves its stored value
     * untouched when the key is absent, so an older client simply never changes it. It rides this
     * payload rather than owning an endpoint because the client pushes here anyway — and it is
     * declared without a default, like every other key, so the value is always encoded rather than
     * silently dropped when it happens to be `false`.
     */
    val shareSoulStats: Boolean,
) {
    /** True when the two carry the same *state*, whatever event each was sent as. */
    fun sameStateAs(other: PresenceState?): Boolean =
        other != null &&
            server == other.server &&
            onSkyblock == other.onSkyblock &&
            area == other.area &&
            subLocation == other.subLocation &&
            profile == other.profile &&
            shareSoulStats == other.shareSoulStats

    /** Compact one-line form for `/soul dev backend`. */
    fun describe(): String =
        "server=${server ?: "-"} · skyblock=${onSkyblock ?: "-"} · area=${area ?: "-"}" +
            " · sub=${subLocation ?: "-"} · profile=${profile ?: "-"} · shareSoulStats=$shareSoulStats"
}

/**
 * Pushes the player's coarse state — which server, on SkyBlock or not, area, sublocation, active
 * profile — to `POST /presence/state`, so the backend's admin surface can show who is online and
 * where.
 *
 * ### This is the liveness signal, deliberately
 * The backend's "is this player online" test is `disconnected_at IS NULL AND Player.lastSeenAt >=
 * now - 90s`, and `lastSeenAt` is bumped by *any* authenticated request. Before this service that
 * traffic was an accident: in the legacy mod it happened to come from settings sync polling
 * `GET /sync/status` once a minute. That feature was never ported to 26.2 and the endpoint was
 * removed from the backend entirely on 2026-08-20, so nothing else bumps `lastSeenAt` on its own —
 * a player with a live SSE connection and no REST traffic would read as offline after 90 s. Hence
 * the 60 s [EVENT_HEARTBEAT] below: online-ness is a property of this feature rather than a side
 * effect of another one, which is also why it must keep beating on the main menu.
 *
 * (The legacy `PresenceService` KDoc claimed the backend treats a live SSE connection as "online".
 * That was never true of this backend. It is not carried over.)
 *
 * ### The three events
 * The backend splits two timestamps that are easy to conflate — `Player.lastSeenAt` ("are they still
 * here", bumped by every authenticated request) and `PlayerPresence.lastSeen` ("when did their state
 * last change", only moved by a real transition). The `event` discriminator is what lets a heartbeat
 * refresh the former without corrupting the latter:
 *
 *  - [EVENT_TRANSITION] — state changed. Backend updates the columns **and** `PlayerPresence.lastSeen`.
 *  - [EVENT_HEARTBEAT] — nothing changed, we are still here. Columns updated, `lastSeen` untouched.
 *  - [EVENT_DISCONNECT] — the *client* is shutting down. Columns nulled, `disconnected_at` stamped,
 *    so the admin page flips to offline immediately instead of aging out over 90 s.
 *
 * ### Cadence
 *  - Transitions ([AreaChanged], [SubLocationChanged], [OnSkyblockChanged], [ProfileChanged],
 *    [HypixelModApi.LocationChangeEvent], server join) go through a **2 s debounce**. This is not
 *    optional: joining fires server → onSkyblock → area → sublocation within a few ticks, so without
 *    coalescing every warp would cost four POSTs and four row writes.
 *  - Transitions are also **deduped** against the last payload the backend accepted. A failed push
 *    deliberately does not update that snapshot, so the next change retries.
 *  - A [EVENT_HEARTBEAT] fires 60 s after the last push attempt, for as long as the client is
 *    running — **including on the main menu** — and is never deduped.
 *  - Exactly one [EVENT_DISCONNECT] fires per client shutdown.
 *
 * ### Scope: the whole session, not just Hypixel, not just in-game
 * Pushes are **not** gated on being on `hypixel.net` (the legacy mod's behaviour, kept deliberately):
 * a private server or a singleplayer world is reported too, with the SkyBlock fields simply null.
 *
 * **Leaving a server is a transition, not a departure.** Reaching the main menu pushes
 * `server = null` and keeps heartbeating, so the backend can list a player as *in the main menu*
 * rather than gone — a client sitting on the title screen is a different thing from a client that
 * was closed, and only the latter sends [EVENT_DISCONNECT]. "Online" therefore means *this client is
 * running*, and `server` says where it is: an address, `singleplayer`, or null for the main menu.
 *
 * Best-effort throughout: a failed push is logged at debug and retried by the next change or beat.
 * Nothing here ever blocks the client thread, with one bounded exception — see [sendDisconnect].
 */
object PresenceService {
    private val logger = SoulLogger("Soul/Presence")

    const val EVENT_TRANSITION = "transition"
    const val EVENT_HEARTBEAT = "heartbeat"
    const val EVENT_DISCONNECT = "disconnect"

    /** `server` value for a local world — see [currentServer]. Null is reserved for the main menu. */
    const val SERVER_SINGLEPLAYER = "singleplayer"

    private const val ENDPOINT = "/presence/state"
    private const val INTENT = "presence-state"

    /** Coalesce window for state changes — long enough for a server-join burst to settle. */
    private const val DEBOUNCE_MS = 2_000L

    /** Beat interval. Must stay comfortably under the backend's 90 s online threshold. */
    private const val HEARTBEAT_MS = 60_000L

    /** How often the heartbeat coroutine wakes to check whether a beat is due. */
    private const val HEARTBEAT_TICK_MS = 1_000L

    /** Ceiling for the shutdown push. Quitting must not wait on an unreachable backend. */
    private const val SHUTDOWN_BUDGET_MS = 2_000L

    /** Pretty-printer for [previewPayload] only — the real push encodes through [BackendClient]. */
    private val dumpJson = Json { prettyPrint = true }

    // ------------------------------------------------------------------ state

    /** Serializes transition + heartbeat pushes so they cannot interleave on [lastSent]. */
    private val mutex = Mutex()

    /** The pending debounced push, cancelled and replaced by each new trigger. */
    private val pending = AtomicReference<Job?>(null)

    /** The last payload the backend **accepted**; the dedupe baseline. Null = nothing accepted yet. */
    @Volatile
    private var lastSent: PresenceState? = null

    /**
     * Set once the client-shutdown disconnect has been sent, so nothing pushes after it. Cleared on
     * a join purely for completeness — in practice the process is on its way out.
     */
    private val disconnectSent = AtomicBoolean(false)

    @Volatile
    private var lastAttemptAt: Instant? = null

    @Volatile
    private var lastPayload: PresenceState? = null

    @Volatile
    private var lastResult: String? = null

    @Volatile
    private var enabledPreviously: Boolean = false

    private val pushes = AtomicLong()
    private val failures = AtomicLong()

    private var registered = false

    data class Status(
        val enabled: Boolean,
        /** When the last push was *attempted* — also the anchor the next heartbeat is due from. */
        val lastAttemptAt: Instant?,
        val lastPayload: PresenceState?,
        val lastResult: String?,
        /** Null when no beat is scheduled — i.e. presence is off, or nothing has been pushed yet. */
        val nextHeartbeatAt: Instant?,
        /** Push *attempts* this session, of which [failures] did not reach the backend. */
        val pushes: Long,
        val failures: Long,
    )

    fun status(): Status {
        val enabled = enabled()
        val attempt = lastAttemptAt
        return Status(
            enabled = enabled,
            lastAttemptAt = attempt,
            lastPayload = lastPayload,
            lastResult = lastResult,
            nextHeartbeatAt = if (enabled && attempt != null) {
                attempt.plusMillis(HEARTBEAT_MS)
            } else {
                null
            },
            pushes = pushes.get(),
            failures = failures.get(),
        )
    }

    /**
     * `/soul dev presence` — the JSON body a transition push would send **right now**: the same
     * serializer and the same null/default handling as the real push, only pretty-printed. The
     * contract says every key is present on every request (`null` is legitimate); this is how you
     * check that claim in game rather than trusting it, since `kotlinx.serialization` silently omits
     * any key whose value equals a *declared* default.
     */
    fun previewPayload(): String = dumpJson.encodeToString(PresenceState.serializer(), currentState(EVENT_TRANSITION))

    /**
     * `/soul dev presence push` — force a push now, bypassing both the debounce and the dedupe, so
     * the round trip can be exercised without walking somewhere. Returns immediately; the outcome
     * lands in [status].
     */
    fun forcePush() {
        SoulScope.io.launch {
            mutex.withLock { push(currentState(EVENT_TRANSITION)) }
        }
    }

    // ------------------------------------------------------------------ wiring

    fun register() {
        if (registered) return
        registered = true
        enabledPreviously = enabled()

        Events.subscribe<AreaChanged> { schedulePush() }
        Events.subscribe<SubLocationChanged> { schedulePush() }
        Events.subscribe<OnSkyblockChanged> { schedulePush() }
        Events.subscribe<ProfileChanged> { schedulePush() }
        // The server *address* has no event of its own, but every switch that changes it also brings
        // a location packet — so this replaces the legacy mod's per-tick `currentServer.ip` poll.
        Events.subscribe<HypixelModApi.LocationChangeEvent> { schedulePush() }
        Events.subscribe<HypixelNetwork.AlphaDetected> { schedulePush() }

        ClientPlayConnectionEvents.JOIN.register(
            ClientPlayConnectionEvents.Join { _, _, _ ->
                disconnectSent.set(false)
                schedulePush()
            },
        )
        ClientPlayConnectionEvents.DISCONNECT.register(
            // Leaving a server is NOT a departure: it is a transition to the main-menu state
            // (`server = null`), and the heartbeat keeps running. Only closing the client disconnects.
            ClientPlayConnectionEvents.Disconnect { _, _ -> schedulePush() },
        )
        ClientLifecycleEvents.CLIENT_STOPPING.register(
            ClientLifecycleEvents.ClientStopping { sendDisconnect(blocking = true) },
        )

        // Initial snapshot, so the backend does not have to wait for a transition. Sent even from the
        // main menu: "this client is up and on the title screen" is a state we report.
        //
        // On CLIENT_STARTED, not here: `register()` runs while Minecraft is still constructing itself,
        // and a push 2 s later would race the splash screen for a session it needs to authenticate —
        // failing that handshake puts [com.soulreturns.platform.http.BackendAuth] on a 30 s cooldown
        // for nothing.
        ClientLifecycleEvents.CLIENT_STARTED.register(
            ClientLifecycleEvents.ClientStarted { schedulePush() },
        )

        startHeartbeat()
        logger.debug("PresenceService registered (enabled=${enabledPreviously})")
    }

    // ------------------------------------------------------------------ triggers

    /**
     * A **preference** carried by the presence payload changed — today only
     * [com.soulreturns.config.ProfileViewerConfig.shareSoulStats], flipped from the config screen.
     *
     * Goes through the normal debounced path rather than [forcePush], which deliberately bypasses
     * both the master switch and the dedupe: a consent flag must not be the one thing that sends a
     * `/presence/state` request while presence is switched off. It reaches the backend on the next
     * push either way, since the flag is part of [PresenceState.sameStateAs]; this only makes the
     * change take effect when it is made instead of at the next warp.
     */
    fun onPresencePreferenceChanged() = schedulePush()

    /**
     * Queue a transition push [DEBOUNCE_MS] from now, replacing any already-queued one. Safe to call
     * from any thread and from inside an event handler; returns immediately.
     */
    private fun schedulePush() {
        if (!enabled()) return
        val job = SoulScope.io.launch {
            delay(DEBOUNCE_MS)
            pushTransition()
        }
        pending.getAndSet(job)?.cancel()
    }

    private suspend fun pushTransition() {
        if (!enabled()) return
        // The shutdown disconnect has gone out; a transition landing after it would clear
        // `disconnected_at` and resurrect a client that no longer exists. (Reachable because
        // CLIENT_STOPPING fires before the netty DISCONNECT when the window is closed in-game.)
        if (disconnectSent.get()) return
        val state = currentState(EVENT_TRANSITION)
        mutex.withLock {
            // Only transitions dedupe. A heartbeat is *about* nothing having changed.
            if (state.sameStateAs(lastSent)) return
            push(state)
        }
    }

    private fun startHeartbeat() {
        SoulScope.io.launch {
            while (isActive) {
                delay(HEARTBEAT_TICK_MS)
                try {
                    heartbeatTick()
                } catch (c: CancellationException) {
                    throw c // shutdown: let the loop end, don't treat it as a tick failure
                } catch (t: Throwable) {
                    logger.debug("Heartbeat tick failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private suspend fun heartbeatTick() {
        val on = enabled()
        if (on != enabledPreviously) {
            enabledPreviously = on
            // Opted back in mid-session: send a fresh snapshot rather than waiting for a transition.
            if (on) schedulePush()
        }
        if (!on) return
        // Only a client shutdown stops the beat. The main menu keeps beating on purpose: without it
        // a player sitting on the title screen would age out of the backend's 90 s window and become
        // indistinguishable from one whose client died.
        if (disconnectSent.get()) return

        val since = lastAttemptAt
        if (since == null) {
            // Nothing has ever been pushed this session — nudge the debounced path to establish the
            // baseline, but ONLY if nothing is already counting down. [schedulePush] *restarts* the
            // 2 s timer, so calling it every tick postpones the push forever instead of hurrying it:
            // this loop cancelled its own pending push once per second and the first push never
            // landed at all.
            if (pending.get()?.isActive != true) schedulePush()
            return
        }
        if (Duration.between(since, Instant.now()).toMillis() < HEARTBEAT_MS) return
        mutex.withLock { push(currentState(EVENT_HEARTBEAT)) }
    }

    /**
     * Report that the **client is shutting down**: state columns nulled, `disconnected_at` stamped,
     * so the admin page shows offline immediately instead of aging out over 90 s. Leaving a server is
     * *not* this — that is a transition to the main-menu state, and the client keeps heartbeating.
     *
     * Runs on its own daemon thread. [ClientLifecycleEvents.CLIENT_STOPPING] fires on the render
     * thread, which may not be parked on a blocking HTTP call, and [SoulScope] is already cancelled by
     * the time our handler runs (it registers its own shutdown first in `Soul.onInitializeClient`), so
     * a coroutine here would simply never start.
     *
     * With [blocking] the caller waits up to [SHUTDOWN_BUDGET_MS] for the send — bounded on purpose:
     * an unreachable backend must not delay quitting. The thread is abandoned, not interrupted, if it
     * overruns, and the 90 s liveness timeout is the fallback for a push that never lands.
     */
    private fun sendDisconnect(blocking: Boolean) {
        if (!enabled()) return
        // Nothing was ever reported this session — there is no presence to retract, and a pointless
        // push here would cost a Mojang handshake on the way out.
        if (lastAttemptAt == null) return
        if (!disconnectSent.compareAndSet(false, true)) return
        // A debounced transition still in the queue would land *after* this and undo it.
        pending.getAndSet(null)?.cancel()
        val thread = Thread(
            {
                // Deliberately not under `mutex`: a heartbeat parked in a 15 s HTTP call would eat
                // the whole shutdown budget, and a racing `lastSent` write cannot matter to a
                // client that is exiting.
                runBlocking { push(currentState(EVENT_DISCONNECT, cleared = true)) }
            },
            "soul-presence-disconnect",
        )
        thread.isDaemon = true
        thread.start()
        if (blocking) thread.join(SHUTDOWN_BUDGET_MS)
    }

    // ------------------------------------------------------------------ the push

    private suspend fun push(state: PresenceState) {
        lastAttemptAt = Instant.now()
        lastPayload = state
        pushes.incrementAndGet()

        val result = BackendClient.post(ENDPOINT, state, PresenceState.serializer(), intent = INTENT)
        if (result is BackendResult.Ok) {
            val updatedAt = result.value["updatedAt"]?.jsonPrimitive?.longOrNull
            lastResult = "200 OK" + (updatedAt?.let { " (updatedAt $it)" } ?: "")
            // A disconnect leaves no state to dedupe against; the next join must push again.
            lastSent = if (state.event == EVENT_DISCONNECT) null else state
            logger.debug("Pushed ${state.event}: ${state.describe()}")
            return
        }
        val failure = result as BackendResult.Failure
        failures.incrementAndGet()
        lastResult = "failed: ${failure.status} ${failure.message}"
        // lastSent is deliberately left alone so the next transition retries this state.
        logger.debug("Push (${state.event}) failed: ${failure.status} ${failure.message}")
    }

    /**
     * Snapshot the current state. With [cleared] every state field is null — what a disconnect sends.
     *
     * The SkyBlock fields are masked by `onSkyblock` on purpose: the readers hold their last value
     * for a poll or two after a transition and the profile name survives a warp entirely, so an
     * unmasked read can ship a stale Hub area from a Bedwars lobby. Null means "unknown", which is
     * exactly right here. On the Hypixel Alpha network the player reads as off SkyBlock: its area
     * and profiles are test data that must not reach the backend.
     */
    private fun currentState(event: String, cleared: Boolean = false): PresenceState {
        if (cleared) return PresenceState(null, null, null, null, null, event, shareSoulStats())
        val onSkyblock = SkyblockState.onSkyblock && !HypixelNetwork.isAlpha
        return PresenceState(
            server = currentServer(),
            onSkyblock = onSkyblock,
            area = if (onSkyblock) SkyblockLocation.area else null,
            subLocation = if (onSkyblock) SkyblockLocation.subLocation else null,
            profile = if (onSkyblock) SkyblockProfileState.current else null,
            event = event,
            shareSoulStats = shareSoulStats(),
        )
    }

    /**
     * Where this client is, as the `server` field: the server **address** (`mc.hypixel.net`), the
     * literal `singleplayer` for a local world, or **null for the main menu** — which the backend
     * lists separately, so null has to mean the title screen and nothing else.
     *
     * Never the Hypixel instance id (`mini62V`): that would make the admin "which server" value
     * meaningless and churn the row on every warp. (`singleplayer` matches what `/soul dev backend`
     * has always printed for a local world, so the two diagnostics read the same.)
     */
    private fun currentServer(): String? = try {
        val mc = Minecraft.getInstance()
        mc.currentServer?.ip ?: if (mc.isLocalServer) SERVER_SINGLEPLAYER else null
    } catch (_: Throwable) {
        null
    }

    /**
     * The player's Soul-stats sharing consent. Defaults to **false** when the config cannot be read:
     * a consent flag must never be reported as granted because we could not check it.
     */
    private fun shareSoulStats(): Boolean = try {
        cfg.profileViewer.shareSoulStats
    } catch (_: Throwable) {
        false
    }

    private fun enabled(): Boolean = try {
        cfg.dev.presence.enabled
    } catch (_: Throwable) {
        // Config not ready yet (or already gone) — say no rather than pushing with unknown consent.
        false
    }
}
