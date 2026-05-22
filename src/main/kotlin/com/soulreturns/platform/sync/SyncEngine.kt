package com.soulreturns.platform.sync

import com.google.gson.JsonObject
import com.soulreturns.core.events.Events
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.realtime.SyncInvalidate
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloud sync for mod data files (config, gui_layout, stats).
 *
 * Local files remain the source of truth on disk — the engine mirrors them to the backend
 * so a fresh install on a different machine can pull them back. **There is intentionally no
 * conflict-resolution path**: only one client per Minecraft account is ever online at a time
 * (Hypixel limitation), so concurrent writes can't happen and the worst case (offline last
 * session) is resolved by the [SyncMetadata.KindMeta.pushedHash] check.
 *
 * Reconcile flow (per artifact, on startup and on periodic scan):
 *   1. If the artifact is disabled → no-op.
 *   2. Compute current local-file SHA-256.
 *   3. GET /sync/{kind}.
 *     - 404: no remote yet → if local exists, push.
 *     - 401/5xx/network error: warn the user (once per session) and use local.
 *     - 200 with `{content, updatedAt}`:
 *       - If `localHash != meta.pushedHash` → local has unpushed changes → push local.
 *       - Else if `response.updatedAt > meta.syncedAt` → remote is fresher → write local, reload.
 *       - Else → no-op.
 *
 * The tick-driven reconcile runs every [SCAN_INTERVAL_TICKS] ticks. It runs the **same** flow
 * as startup — push if local drifted, pull if the admin (the only second writer in the model)
 * edited via the web UI. Per-kind in-flight gates prevent overlapping operations.
 *
 * **Pulls are suppressed while a Soul-owned editing screen is open** ([SoulConfigScreen] or
 * [GuiEditScreen]). Without that gate, a pull mid-edit would overwrite in-memory state, then
 * the user's save on screen-close would push stale data back over the admin's edit, silently
 * undoing it. Pushes still run while screens are open — that's how the user's own edits
 * propagate.
 *
 * Shutdown (`CLIENT_STOPPING`): one final synchronous push pass to flush any pending changes.
 */
object SyncEngine {
    private val logger = SoulLogger("Soul/Sync")

    /** Scan interval for the change watcher, in client ticks. 1200 ticks = 60 s at 20 TPS. */
    private const val SCAN_INTERVAL_TICKS = 1200

    /** Max bytes per artifact. Anything larger is skipped (suggests local-state corruption). */
    private const val MAX_BYTES = 256 * 1024

    private val artifacts = ConcurrentHashMap<SyncKind, SyncedArtifact>()
    private val inFlight = ConcurrentHashMap<SyncKind, AtomicBoolean>()
    private val started = AtomicBoolean(false)
    private val initialReconcileDone = AtomicBoolean(false)
    private val warnedThisSession = AtomicBoolean(false)
    private var tickCounter = 0

    /**
     * Dedicated single-thread executor for sync orchestration.
     *
     * **Why a separate executor?** This thread calls `BackendClient.get(...).join()` (and
     * post), which themselves dispatch to `SoulExecutor` (the 2-thread pool shared with
     * `SoulHttp`'s `HttpClient` async I/O). If we ran sync on `SoulExecutor` and blocked
     * on `.join()`, we'd starve the same pool the HTTP call is waiting for → deadlock.
     * With our own thread, the .join() blocks here but `SoulExecutor` stays free for the
     * actual network call.
     */
    private val syncExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "soul-sync").apply { isDaemon = true }
        }

    /** Coalesce window for [notifyChanged] — multiple rapid saves merge into one push. */
    private const val NOTIFY_DEBOUNCE_MS = 2_000L

    /** Pending scheduled pushes per kind, used to cancel/replace inside the debounce window. */
    private val pendingPush = ConcurrentHashMap<SyncKind, ScheduledFuture<*>>()

    fun register(artifact: SyncedArtifact) {
        artifacts[artifact.kind] = artifact
        inFlight[artifact.kind] = AtomicBoolean(false)
    }

    /**
     * Signal that the local file for [kind] has just been written and should be pushed to
     * the backend ASAP. Coalesces a burst of changes within [NOTIFY_DEBOUNCE_MS] into a
     * single push — owo-config saves once per option toggle, so a user changing five
     * settings in one screen session triggers five `notifyChanged(CONFIG)` calls but only
     * one PUT.
     *
     * Safe to call from any thread; the actual push runs on the sync thread.
     *
     * Used by:
     *   - `SoulConfigScreen.removed()` → [SyncKind.CONFIG]
     *   - `GuiLayoutManager.save()`    → [SyncKind.GUI_LAYOUT]
     *
     * `STATS` deliberately doesn't call this — `PersistentStats` writes every ~1 s during
     * active gameplay and would generate constant traffic. The 60 s periodic scan plus the
     * `CLIENT_STOPPING` flush cover stats just fine.
     */
    fun notifyChanged(kind: SyncKind) {
        if (!initialReconcileDone.get()) return
        val artifact = artifacts[kind] ?: return
        if (!artifact.enabled()) return
        pendingPush.remove(kind)?.cancel(false)
        val future =
            syncExecutor.schedule(
                {
                    pendingPush.remove(kind)
                    if (!artifact.enabled()) return@schedule
                    try {
                        pushIfChangedBlocking(artifact)
                    } catch (e: Throwable) {
                        logger.warn("notifyChanged push failed for ${kind.key}", e)
                    }
                },
                NOTIFY_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
        pendingPush[kind] = future
    }

    /**
     * Trigger an out-of-band reconcile for [kind] right now (used by realtime sync-invalidate).
     * Skipped if the user has an editing screen open — see [isPullSuppressed] for rationale.
     * Returns immediately; the actual work runs on the sync thread.
     */
    fun reconcileNow(kind: SyncKind) {
        if (!initialReconcileDone.get()) return
        if (isPullSuppressed()) {
            logger.info("Realtime invalidate for ${kind.key}: skipped (editing screen open).")
            return
        }
        val artifact = artifacts[kind] ?: return
        if (!artifact.enabled()) return
        reconcileAsync(artifact)
    }

    /**
     * Kick off the initial async reconcile and wire the periodic change watcher + shutdown flush.
     * Safe to call after all artifacts have been registered. Does not block.
     */
    fun start(masterEnabled: () -> Boolean) {
        if (!started.compareAndSet(false, true)) return
        SyncMetadata.load()

        if (!masterEnabled()) {
            logger.info("Cloud sync disabled; engine idle.")
            initialReconcileDone.set(true)
            registerShutdown()
            return
        }

        syncExecutor.submit {
            try {
                reconcileAllViaStatus()
            } finally {
                initialReconcileDone.set(true)
                SyncMetadata.save()
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                if (!initialReconcileDone.get()) return@EndTick
                if (!masterEnabled()) return@EndTick
                tickCounter++
                if (tickCounter < SCAN_INTERVAL_TICKS) return@EndTick
                tickCounter = 0
                if (isPullSuppressed()) {
                    // Pushes still run while an editing screen is open — that's how the user's
                    // own changes propagate. We just skip pulls to avoid mid-edit overwrites.
                    for (artifact in artifacts.values) {
                        if (artifact.enabled()) pushIfChanged(artifact)
                    }
                } else {
                    syncExecutor.submit {
                        try {
                            reconcileAllViaStatus()
                        } catch (e: Throwable) {
                            logger.warn("Periodic reconcile failed", e)
                        }
                    }
                }
            }
        )

        // Realtime sync-invalidate: backend tells us a blob changed → reconcile that kind now,
        // out of band with the 60 s polling cycle. Filter silently on kinds that don't map
        // to a SyncKind — every catalog client (items / drops / mobs / sea creatures /
        // aliases) handles its own kind via a direct Events.subscribe<SyncInvalidate>;
        // logging "unknown kind" for those would spam every admin-edit invalidate.
        Events.subscribe<SyncInvalidate> { event ->
            val kind = SyncKind.entries.firstOrNull { it.key == event.kind } ?: return@subscribe
            reconcileNow(kind)
        }

        registerShutdown()
    }

    private fun registerShutdown() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(
            ClientLifecycleEvents.ClientStopping { _ ->
                try {
                    flushSync()
                } catch (_: Throwable) {
                }
            }
        )
    }

    /**
     * Blocking final push pass on the sync thread, with a 5 s overall ceiling. Runs on
     * `CLIENT_STOPPING`; we don't want to keep Minecraft hanging if the backend is down.
     */
    private fun flushSync() {
        val task =
            syncExecutor.submit {
                try {
                    for (artifact in artifacts.values) {
                        if (!artifact.enabled()) continue
                        try {
                            pushIfChangedBlocking(artifact)
                        } catch (_: Throwable) {
                        }
                    }
                } finally {
                    SyncMetadata.save()
                }
            }
        try {
            task.get(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /**
     * True while the user has a Soul-owned editing screen open. The periodic reconcile
     * skips pulls in this state — pushes still run. Resolved on every tick scan so it's
     * always current (no event subscriptions to keep in sync).
     */
    private fun isPullSuppressed(): Boolean {
        val screen = Minecraft.getInstance().screen ?: return false
        return screen is com.soulreturns.config.gui.SoulConfigScreen ||
            screen is com.soulreturns.gui.GuiEditScreen
    }

    /**
     * Dispatch a full reconcile (pull + maybe-push) for [artifact] on the sync thread.
     * Returns immediately. Uses the per-kind in-flight gate so a stuck network call doesn't
     * pile up tasks.
     */
    private fun reconcileAsync(artifact: SyncedArtifact) {
        val gate = inFlight[artifact.kind] ?: return
        if (!gate.compareAndSet(false, true)) return
        try {
            syncExecutor.submit {
                try {
                    reconcile(artifact)
                } catch (e: Throwable) {
                    logger.warn("Periodic reconcile failed for ${artifact.kind.key}", e)
                } finally {
                    gate.set(false)
                }
            }
        } catch (e: Throwable) {
            gate.set(false)
            logger.warn("Reconcile dispatch rejected for ${artifact.kind.key}", e)
        }
    }

    // ───────────────────── reconcile ─────────────────────

    /**
     * Batched reconcile: one `GET /sync/status` returning per-kind `updatedAt`, then decide
     * per artifact whether anything actually needs a full content fetch.
     *
     * Wire shape:
     * ```
     * { "kinds": { "<key>": { "updatedAt": <long> } } }
     * ```
     * Kinds the player has never pushed are **omitted** from the response — absence means
     * "no remote, push if you have local."
     *
     * Used by startup and periodic reconcile. `reconcileNow(kind)` (the realtime invalidate
     * path) stays single-kind: we already know which kind changed, no point status-checking.
     *
     * Runs on the sync thread. Acquires each per-kind gate as it processes that kind, so a
     * concurrent `notifyChanged`-driven push gets to keep its slot.
     */
    private fun reconcileAllViaStatus() {
        val status = fetchStatus() ?: return
        for (artifact in artifacts.values) {
            if (!artifact.enabled()) continue
            val gate = inFlight[artifact.kind] ?: continue
            if (!gate.compareAndSet(false, true)) continue
            try {
                reconcileOneAgainstStatus(artifact, status[artifact.kind.key])
            } catch (e: Throwable) {
                logger.warn("Reconcile failed for ${artifact.kind.key}", e)
                warnUserOnce()
            } finally {
                gate.set(false)
            }
        }
    }

    /**
     * Apply the same decision tree as [reconcile], but with the remote `updatedAt` already
     * in hand from `/sync/status`. The win is in the "remote not fresher" branch — the
     * common case — which now needs no content GET at all.
     *
     * @param remoteUpdatedAt the value from `/sync/status`, or null if the kind is absent
     *                        (= "no remote yet").
     */
    private fun reconcileOneAgainstStatus(
        artifact: SyncedArtifact,
        remoteUpdatedAt: Long?,
    ) {
        val localBytes = readLocalOrNull(artifact.file)
        val localHash = localBytes?.let { sha256Hex(it) } ?: ""
        val meta = SyncMetadata.get(artifact.kind)

        // 1. Unpushed local changes — push wins. No GET needed.
        if (localBytes != null && localHash != meta.pushedHash && meta.pushedHash.isNotEmpty()) {
            logger.info("${artifact.kind.key}: local has unpushed changes — pushing.")
            pushIfChangedOnSyncThread(artifact)
            return
        }

        // 2. No remote — push local if we have it.
        if (remoteUpdatedAt == null) {
            if (localBytes != null) pushIfChangedOnSyncThread(artifact)
            return
        }

        // 3. Remote not fresher — in sync. Seed the sidecar if we never recorded a hash
        //    (e.g. fresh install picked up matching remote on a previous session).
        if (remoteUpdatedAt <= meta.syncedAt) {
            if (meta.pushedHash.isEmpty() && localBytes != null) {
                SyncMetadata.update(artifact.kind) {
                    pushedHash = localHash
                    syncedAt = remoteUpdatedAt
                }
            }
            return
        }

        // 4. Remote IS fresher — fall through to the full per-kind reconcile which fetches
        //    content and runs handleRemote.
        reconcile(artifact)
    }

    /** GET `/sync/status` and parse into a `kind → updatedAt` map. Returns null on failure. */
    private fun fetchStatus(): Map<String, Long>? {
        val result = BackendClient.get("/sync/status", intent = "sync-status").join()
        return when (result) {
            is BackendClient.Result.Ok -> {
                val obj = result.json.asJsonObject
                val kindsObj = obj.getAsJsonObject("kinds") ?: return emptyMap()
                val map = mutableMapOf<String, Long>()
                for ((key, value) in kindsObj.entrySet()) {
                    if (!value.isJsonObject) continue
                    val updatedAt = value.asJsonObject.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong
                    if (updatedAt != null) map[key] = updatedAt
                }
                map
            }

            is BackendClient.Result.Error -> {
                logger.warn("Sync status fetch failed: HTTP ${result.statusCode} ${result.message}")
                warnUserOnce()
                null
            }
        }
    }

    private fun reconcile(artifact: SyncedArtifact) {
        val localBytes = readLocalOrNull(artifact.file)
        val localHash = localBytes?.let { sha256Hex(it) } ?: ""
        val meta = SyncMetadata.get(artifact.kind)

        val result = BackendClient.get("/sync/${artifact.kind.key}", intent = "sync-pull").join()
        when (result) {
            is BackendClient.Result.Ok -> {
                val remote =
                    parseRemote(result.json.asJsonObject) ?: run {
                        logger.warn("Malformed /sync/${artifact.kind.key} response: ${result.json}")
                        warnUserOnce()
                        return
                    }
                handleRemote(artifact, localBytes, localHash, meta, remote)
            }

            is BackendClient.Result.Error -> {
                if (result.statusCode == 404) {
                    // No remote yet — push local if we have anything. Admin "reset"
                    // workflow no longer produces a 404: admin edits push defaults back
                    // via a normal PUT instead of deleting the blob.
                    if (localBytes != null) pushIfChangedOnSyncThread(artifact)
                } else {
                    logger.warn("Sync pull for ${artifact.kind.key} failed: HTTP ${result.statusCode} ${result.message}")
                    warnUserOnce()
                }
            }
        }
    }

    private data class RemotePayload(val content: String, val updatedAt: Long)

    private fun parseRemote(obj: JsonObject): RemotePayload? {
        val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val updatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        return RemotePayload(content, updatedAt)
    }

    private fun handleRemote(
        artifact: SyncedArtifact,
        localBytes: ByteArray?,
        localHash: String,
        meta: SyncMetadata.KindMeta,
        remote: RemotePayload,
    ) {
        val remoteBytes = remote.content.toByteArray(Charsets.UTF_8)
        val remoteHash = sha256Hex(remoteBytes)

        // 1. Unpushed local changes (pushedHash drift from current local) → push wins.
        // We're on the sync thread inside a held gate; call the gate-free variant.
        if (localBytes != null && localHash != meta.pushedHash && meta.pushedHash.isNotEmpty()) {
            logger.info("${artifact.kind.key}: local has unpushed changes — pushing.")
            pushIfChangedOnSyncThread(artifact)
            return
        }

        // 2. Remote is fresher than what we last synced → pull and reload.
        if (remote.updatedAt > meta.syncedAt && localHash != remoteHash) {
            logger.info(
                "${artifact.kind.key}: pulling remote (updatedAt=${remote.updatedAt} > syncedAt=${meta.syncedAt})."
            )
            writeLocal(artifact.file, remoteBytes)
            SyncMetadata.update(artifact.kind) {
                pushedHash = remoteHash
                syncedAt = remote.updatedAt
            }
            // Reload in-memory state on the client thread.
            Minecraft.getInstance().execute {
                try {
                    artifact.onAfterPull()
                } catch (e: Throwable) {
                    logger.warn("onAfterPull threw for ${artifact.kind.key}", e)
                }
            }
            return
        }

        // 3. In sync. Sync metadata in case we never seeded it (e.g. first install picked up matching remote).
        if (meta.pushedHash.isEmpty() && localBytes != null) {
            SyncMetadata.update(artifact.kind) {
                pushedHash = localHash
                syncedAt = remote.updatedAt
            }
        }
    }

    // ───────────────────── push ─────────────────────

    /**
     * Async push entry point called from the tick scanner (not on the sync thread).
     * Dispatches to [syncExecutor] and returns immediately.
     */
    private fun pushIfChanged(artifact: SyncedArtifact) {
        val gate = inFlight[artifact.kind] ?: return
        if (!gate.compareAndSet(false, true)) return
        try {
            syncExecutor.submit {
                try {
                    pushIfChangedOnSyncThread(artifact)
                } finally {
                    gate.set(false)
                }
            }
        } catch (e: Throwable) {
            // Executor rejected (e.g. already shut down) — release the gate so we can retry.
            gate.set(false)
            logger.warn("Push dispatch rejected for ${artifact.kind.key}", e)
        }
    }

    /** Blocking variant — must be called on the sync thread (reconcile path, shutdown flush). */
    private fun pushIfChangedBlocking(artifact: SyncedArtifact) {
        val gate = inFlight[artifact.kind] ?: return
        if (!gate.compareAndSet(false, true)) return
        try {
            pushIfChangedOnSyncThread(artifact)
        } finally {
            gate.set(false)
        }
    }

    /** Actual push work. Caller owns the inFlight gate. Runs on the sync thread. */
    private fun pushIfChangedOnSyncThread(artifact: SyncedArtifact) {
        val bytes = readLocalOrNull(artifact.file) ?: return
        val hash = sha256Hex(bytes)
        val meta = SyncMetadata.get(artifact.kind)
        if (hash == meta.pushedHash) return
        if (bytes.size > MAX_BYTES) {
            logger.warn("${artifact.kind.key}: file exceeds $MAX_BYTES bytes (${bytes.size}); skipping push.")
            return
        }
        val body =
            JsonObject().apply {
                addProperty("content", bytes.toString(Charsets.UTF_8))
                // Attach defaults if the artifact provides them — this is how the backend
                // learns what the mod considers the default state, so the admin UI can render
                // per-row "reset to default" buttons without ever tracking defaults itself.
                artifact.defaultsJson.invoke()?.let { add("defaults", it) }
            }
        val result =
            try {
                BackendClient.post("/sync/${artifact.kind.key}", body, intent = "sync-push").join()
            } catch (e: Throwable) {
                logger.warn("Push for ${artifact.kind.key} threw", e)
                return
            }
        when (result) {
            is BackendClient.Result.Ok -> {
                val updatedAt = result.json.asJsonObject.get("updatedAt")?.asLong ?: System.currentTimeMillis()
                SyncMetadata.update(artifact.kind) {
                    pushedHash = hash
                    syncedAt = updatedAt
                }
                SyncMetadata.save()
                logger.info("${artifact.kind.key}: pushed (${bytes.size} bytes, updatedAt=$updatedAt).")
            }

            is BackendClient.Result.Error ->
                logger.warn("Push for ${artifact.kind.key} failed: HTTP ${result.statusCode} ${result.message}")
        }
    }

    // ───────────────────── io helpers ─────────────────────

    private fun readLocalOrNull(file: File): ByteArray? {
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (e: Exception) {
            logger.warn("Could not read ${file.absolutePath}", e)
            null
        }
    }

    private fun writeLocal(
        file: File,
        bytes: ByteArray,
    ) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.warn("Could not write ${file.absolutePath}", e)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }

    private fun warnUserOnce() {
        if (warnedThisSession.compareAndSet(false, true)) {
            soulChat("§eCloud sync unavailable — using local files. (Changes will sync when the backend is reachable.)")
        }
    }
}
