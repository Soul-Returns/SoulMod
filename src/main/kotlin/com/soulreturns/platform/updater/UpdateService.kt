package com.soulreturns.platform.updater

import com.soulreturns.Soul
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.DataBackups
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.minecraft.client.Minecraft
import java.net.URLEncoder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The auto updater's core: check, verify, download, place, and the first-start cleanup. The UI
 * reads [snapshot] and calls the actions ([checkNow], [install], [skip], [unskip], [closeGame],
 * [dismissUpdated]); the UI itself lives in `features/updater/`. Design: research doc §7 + §API.
 *
 * ### Trust
 * An offer counts only if its manifest is signed by a key in [TrustedKeys]; size, sha256, version,
 * channel and Minecraft range come from the signed bytes, never from the unsigned response fields.
 * The downloaded jar must hash to the signed sha256 and its own `fabric.mod.json` must say `soul` at
 * the signed version, with every dependency satisfied by the running loader ([DependencyGuard]).
 * Without a trusted key in the build, offers are shown but never installed.
 *
 * ### The swap
 * The verified jar is downloaded as `mods/.soul-<ver>.jar.part` (ignored by Fabric) and moved to
 * `mods/soul-<ver>.jar` next to the running jar, which stays. Next start Fabric loads the higher
 * version; that version deletes the older `soul` jars ([startupScan]). A newer `soul` jar still in
 * the folder while an older one runs means Fabric fell back (its dependencies did not resolve):
 * that becomes a [UpdateNotice.BLOCKED] notice and the version is not offered again this session.
 * See rule://updater for the loader behaviour this relies on.
 */
object UpdateService {
    private val logger = SoulLogger("Soul/Updater")

    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L

    /** Partial downloads older than this are leftovers, not another client's live download. */
    private const val STALE_PART_MS = 60 * 60 * 1000L

    private const val DELETE_ATTEMPTS = 5
    private const val DELETE_RETRY_MS = 3_000L

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = true }

    /**
     * A release whose manifest verified (or, without a trusted key, an unverified one to show).
     * [incompatible] names what the installed loader lacks for its signed `depends`, decided at
     * check time so the offer says so before anything downloads.
     */
    private class Offer(val release: UpdateRelease, val download: String, val manifest: ReleaseManifest?, val incompatible: String?)

    private val lock = Any()

    // Guarded by [lock].
    private var offer: Offer? = null
    private var downloading = false
    private var progress = 0f
    private var verifying = false
    private var ready: UpdateRelease? = null
    private var error: String? = null
    private var errorKind: String? = null
    private var checkError: String? = null
    private var checking = false
    private var lastCheckedAt = 0L
    private var updated: UpdatedInfo? = null
    private var autoInstalled = false
    private val notices = LinkedHashMap<String, UpdateNotice>()
    private val blockedVersions = HashSet<String>()

    @Volatile
    var snapshot: UpdateSnapshot = UpdateSnapshot()
        private set

    private lateinit var stateFile: Path
    private var target: InstallTarget = InstallTarget.Unsupported("not initialised")

    /** False in a development run: no startup/hourly check and no title-screen question or offer ([UpdatePolicy]). */
    @Volatile
    var automatic = false
        private set

    // ---------------------------------------------------------------- lifecycle

    /** Early in `onInitializeClient`, after [ConfigManager.init]. */
    fun register() {
        stateFile = FabricLoader.getInstance().configDir.resolve("soul").resolve("updates").resolve("state.json")
        automatic = UpdatePolicy.automatic(FabricLoader.getInstance().isDevelopmentEnvironment)
        target = locate()
        if (target is InstallTarget.Unsupported) logger.info("Updates are notify-only: ${(target as InstallTarget.Unsupported).reason}")
        recordStart()
        publish()
        // The first check starts during client init so its answer is there when the title screen
        // appears; after the scan, which may mark a version as blocked before it could be offered.
        SoulScope.io.launch {
            startupScan()
            while (automatic) {
                check(manual = false)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun locate(): InstallTarget {
        val loader = FabricLoader.getInstance()
        val container = loader.getModContainer(ReleaseVerifier.MOD_ID).orElse(null)
            ?: return InstallTarget.Unsupported("Soul's mod container is missing")
        val origin = container.origin
        val paths = try {
            origin.paths
        } catch (_: UnsupportedOperationException) {
            emptyList()
        }
        return ModsFolder.locate(
            isDevelopment = loader.isDevelopmentEnvironment,
            originKind = origin.kind.name,
            originPaths = paths,
            gameDir = loader.gameDir,
            modsFolderProperty = System.getProperty("fabric.modsFolder"),
        )
    }

    /** "Updated to vX" detection and the pending-install bookkeeping, before anything else runs. */
    private fun recordStart() {
        val running = Soul.version
        val stored = loadState()
        var shownUpdate = stored.updated
        val last = stored.lastRunVersion
        if (last != null && ReleaseVersions.isNewer(running, last)) {
            val notes = stored.pending?.takeIf { it.version == running }?.notes.orEmpty()
            shownUpdate = UpdatedInfo(from = last, to = running, notes = notes)
            logger.info("Updated from $last to $running")
        }
        // Applied (or superseded by a manual update): done. Still newer: the jar was removed by hand
        // or Fabric fell back; startupScan reports the latter, so it is not kept as "ready" either way.
        val pending = stored.pending?.takeIf { ReleaseVersions.isNewer(it.version, running) }
        if (pending != null) logger.warn("Soul ${pending.version} was installed but ${running} started")
        synchronized(lock) { updated = shownUpdate }
        saveState(UpdaterStateFile(lastRunVersion = running, pending = null, updated = shownUpdate))
    }

    /**
     * First-start cleanup, off the client thread: stale partial downloads, every older `soul` jar
     * (retried: antivirus or a slow launcher may hold a fresh file briefly), and newer `soul` jars
     * that Fabric did not load.
     */
    private suspend fun startupScan() {
        val folder = target as? InstallTarget.Folder ?: return
        val now = System.currentTimeMillis()
        for (part in ModsFolder.leftoverParts(folder.mods)) {
            val age = now - runCatching { Files.getLastModifiedTime(part).toMillis() }.getOrDefault(now)
            if (age > STALE_PART_MS) runCatching { Files.deleteIfExists(part) }
        }

        val jars = ModsFolder.scan(folder.mods)
        val older = ModsFolder.olderJars(jars, folder.origin, Soul.version)
        for (jar in older) SoulScope.io.launch { deleteWithRetry(jar) }

        for (jar in ModsFolder.newerJars(jars, Soul.version)) {
            val meta = JarMetadataReader.read(jar.path)
            val problems = meta?.let { DependencyGuard.check(it, ::installedVersion) }.orEmpty()
            val why = if (problems.isEmpty()) "Fabric did not load it" else problems.joinToString("; ") { it.describe() }
            logger.warn("Newer Soul ${jar.version} in the mods folder did not load: $why")
            synchronized(lock) {
                blockedVersions += jar.version
                notices[UpdateNotice.BLOCKED] = UpdateNotice(
                    UpdateNotice.BLOCKED,
                    "Soul ${jar.version} is installed but cannot start: $why. Still running ${Soul.version}.",
                )
            }
            publish()
        }
    }

    private suspend fun deleteWithRetry(jar: SoulJar) {
        repeat(DELETE_ATTEMPTS) { attempt ->
            try {
                Files.deleteIfExists(jar.path)
                logger.info("Removed old Soul ${jar.version}: ${jar.path.fileName}")
                return
            } catch (e: Exception) {
                if (attempt == DELETE_ATTEMPTS - 1) {
                    logger.warn("Could not remove old Soul jar ${jar.path.fileName} (${e.javaClass.simpleName}); retrying next start")
                    return
                }
                delay(DELETE_RETRY_MS)
            }
        }
    }

    // ---------------------------------------------------------------- actions (any thread)

    /** Re-check now, bypassing the response cache. */
    fun checkNow() {
        SoulScope.io.launch { check(manual = true) }
    }

    /**
     * Download, verify and place the offered release; it applies on the next start. [auto]: started
     * by `updates.autoInstall` rather than a click (its outcome is announced like an offer).
     */
    fun install(auto: Boolean = false) {
        val current = synchronized(lock) {
            val o = offer ?: return
            if (downloading || ready?.version == o.release.version) return
            if (!canInstall()) return
            downloading = true
            verifying = false
            progress = 0f
            error = null
            errorKind = null
            autoInstalled = auto
            o
        }
        publish()
        SoulScope.io.launch { if (preview) previewInstall(current) else runInstall(current) }
    }

    /** `updates.autoInstall` was just turned on: start the pending offer's download, like a check would have. */
    fun startPendingAutoInstall() {
        val go = synchronized(lock) {
            val o = offer
            o != null && !downloading && !isSkipped(o.release.version)
        }
        if (go) install(auto = true)
    }

    /** Skip the offered version: no announcement, no auto-install; a newer release is offered again. */
    fun skip() {
        val version = synchronized(lock) { offer?.release?.version } ?: return
        ConfigManager.update { copy(updates = updates.copy(skippedVersion = version)) }
        publish()
    }

    /** Undo [skip] ("Undo" in chat, "Unskip" in Settings › About). */
    fun unskip() {
        if (cfg.updates.skippedVersion.isEmpty()) return
        ConfigManager.update { copy(updates = updates.copy(skippedVersion = "")) }
        publish()
    }

    /** "Restart now": an orderly stop (saves options, leaves the server); the launcher restarts it. */
    fun closeGame() {
        val mc = Minecraft.getInstance()
        mc.execute { mc.stop() }
    }

    /** The UI showed "Updated to vX"; forget it. */
    fun dismissUpdated() {
        synchronized(lock) { updated = null }
        saveState(loadState().copy(updated = null))
        publish()
    }

    /** The chat line said "Updated to vX" once; keep it in memory for the UI, drop it from disk. */
    fun markUpdatedAnnounced() {
        saveState(loadState().copy(updated = null))
    }

    // ---------------------------------------------------------------- check

    private suspend fun check(manual: Boolean) {
        synchronized(lock) {
            if (checking) return
            checking = true
        }
        publish()
        try {
            val result = BackendClient.get(
                checkPath(),
                UpdateCheckResponse.serializer(),
                intent = "update.check",
                bypassCache = manual,
                authenticated = false,
            )
            if (result is BackendResult.Ok) {
                apply(result.value)
            } else {
                val failure = result as BackendResult.Failure
                logger.debug("Update check failed: ${failure.message}")
                synchronized(lock) { checkError = failure.message }
            }
        } finally {
            synchronized(lock) { checking = false }
            publish()
        }
    }

    private fun checkPath(): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        val sb = StringBuilder("/mod/update?current=").append(enc(Soul.version))
            .append("&mc=").append(enc(minecraftVersion()?.friendlyString ?: "unknown"))
            .append("&channel=").append(channel())
        val uuid = try {
            Minecraft.getInstance().user?.profileId?.toString()
        } catch (_: Throwable) {
            null
        }
        if (uuid != null) sb.append("&uuid=").append(enc(uuid))
        return sb.toString()
    }

    private fun apply(response: UpdateCheckResponse) {
        if (preview) return
        val running = Soul.version
        val next = HashMap<String, UpdateNotice>()
        response.current?.let { c ->
            if (c.revoked) {
                val reason = c.revokedReason?.takeIf { it.isNotBlank() }
                next[UpdateNotice.REVOKED] = UpdateNotice(
                    UpdateNotice.REVOKED,
                    "Soul $running was withdrawn" + (reason?.let { ": $it" } ?: "") + ". Please update.",
                    detail = reason,
                )
            }
            if (c.belowMinimum) {
                next[UpdateNotice.BELOW_MINIMUM] = UpdateNotice(
                    UpdateNotice.BELOW_MINIMUM,
                    "Soul $running is no longer supported (minimum ${response.minimumVersion ?: "newer"}). Please update.",
                    detail = response.minimumVersion,
                )
            }
        }
        response.otherMinecraft?.let { o ->
            next[UpdateNotice.OTHER_MINECRAFT] = UpdateNotice(UpdateNotice.OTHER_MINECRAFT, "Soul ${o.version} is out for Minecraft ${o.minecraft}.")
        }
        response.message?.takeIf { it.isNotBlank() }?.let { next[UpdateNotice.MESSAGE] = UpdateNotice(UpdateNotice.MESSAGE, it) }

        val accepted = response.latest?.let { evaluate(it) }
        val auto: Boolean
        synchronized(lock) {
            listOf(UpdateNotice.REVOKED, UpdateNotice.BELOW_MINIMUM, UpdateNotice.OTHER_MINECRAFT, UpdateNotice.MESSAGE).forEach { notices.remove(it) }
            notices.putAll(next)
            checkError = null
            lastCheckedAt = System.currentTimeMillis()
            // A running download or a placed jar keeps its release; a newer offer still replaces an
            // idle one (the newest placed jar wins at the next start anyway).
            if (!downloading) {
                offer = accepted?.takeIf { ready == null || ReleaseVersions.isNewer(it.release.version, ready!!.version) }
                if (accepted == null) {
                    error = null
                    errorKind = null
                }
            }
            val o = offer
            auto = o != null && !downloading && cfg.updates.autoInstall && !isSkipped(o.release.version) && canInstall()
        }
        publish()
        if (auto) install(auto = true)
    }

    /** Null when the offer is not shown at all. */
    private fun evaluate(latest: UpdateOffer): Offer? {
        if (latest.version in synchronized(lock) { blockedVersions.toSet() }) return null
        val notes = latest.notes.ifEmpty { listOf(ReleaseNote(latest.version, latest.channel, latest.publishedAt, latest.changelog)) }
        val release = { m: ReleaseManifest? ->
            UpdateRelease(
                version = m?.version ?: latest.version,
                channel = m?.channel ?: latest.channel,
                notes = notes,
                publishedAt = latest.publishedAt,
                size = m?.size ?: latest.size,
            )
        }
        if (TrustedKeys.keys.isEmpty()) {
            // Notify-only build: show what the backend says, never install it.
            if (!ReleaseVersions.isNewer(latest.version, Soul.version)) return null
            if (latest.channel == ReleaseVersions.BETA && channel() != ReleaseVersions.BETA) return null
            return Offer(release(null), latest.download, null, null)
        }
        val verdict = ReleaseVerifier.evaluate(
            manifestBase64 = latest.manifest,
            signatureBase64 = latest.signature,
            offeredVersion = latest.version,
            trustedKeys = TrustedKeys.keys,
            running = Soul.version,
            acceptBeta = channel() == ReleaseVersions.BETA,
            minecraft = minecraftVersion(),
        )
        if (verdict is OfferVerdict.Accepted) {
            if (!latest.download.startsWith("/")) {
                logger.warn("Ignoring update ${latest.version}: download '${latest.download}' is not a backend path")
                return null
            }
            val incompatible = incompatibility(verdict.manifest)
            if (incompatible != null) logger.info("Update ${latest.version} not installable here: $incompatible")
            return Offer(release(verdict.manifest), latest.download, verdict.manifest, incompatible)
        }
        logger.warn("Ignoring update ${latest.version}: ${(verdict as OfferVerdict.Rejected).reason}")
        return null
    }

    // ---------------------------------------------------------------- install

    private suspend fun runInstall(o: Offer) {
        val folder = target as? InstallTarget.Folder
        val manifest = o.manifest
        if (folder == null || manifest == null) return finishInstall(o, failure = "this installation cannot update itself", kind = UpdateError.LOCAL)

        // Two clients sharing a mods folder: only one installs at a time.
        val lockFile = folder.mods.resolve(".soul-update.lock")
        val channel = try {
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: Exception) {
            return finishInstall(o, failure = "cannot write to the mods folder (${e.javaClass.simpleName})", kind = UpdateError.LOCAL)
        }
        val part = folder.mods.resolve(ModsFolder.PART_PREFIX + manifest.version + ModsFolder.PART_SUFFIX)
        try {
            val fileLock = try {
                channel.tryLock()
            } catch (_: Exception) {
                null
            } ?: return finishInstall(o, failure = "another Minecraft is installing an update", kind = UpdateError.LOCAL)

            try {
                var lastPublished = 0f
                val result = BackendClient.download(o.download, part, intent = "update.download", maxBytes = manifest.size) { done, total ->
                    val size = if (total > 0) total else manifest.size
                    val p = (done.toFloat() / size).coerceIn(0f, 1f)
                    synchronized(lock) { progress = p }
                    if (p - lastPublished >= 0.02f || p >= 1f) {
                        lastPublished = p
                        publish()
                    }
                }
                if (result !is BackendResult.Ok) {
                    return finishInstall(o, failure = (result as BackendResult.Failure).message, kind = UpdateError.DOWNLOAD)
                }
                synchronized(lock) {
                    progress = 1f
                    verifying = true
                }
                publish()
                val problem = ReleaseVerifier.checkJar(manifest, result.value.bytes, result.value.sha256, JarMetadataReader.read(part), ::installedVersion)
                if (problem != null) return finishInstall(o, failure = problem.reason, kind = problem.kind)

                val placed = folder.mods.resolve(manifest.file)
                if (placed.toAbsolutePath().normalize() == folder.origin) return finishInstall(o, failure = "refusing to overwrite the running jar", kind = UpdateError.LOCAL)
                try {
                    Files.move(part, placed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part, placed, StandardCopyOption.REPLACE_EXISTING)
                }
                saveState(loadState().copy(pending = PendingInstall(manifest.version, manifest.file, Soul.version, o.release.notes)))
                logger.info("Placed Soul ${manifest.version} as ${placed.fileName}; applies on the next start")
                finishInstall(o, failure = null)
            } finally {
                runCatching { fileLock.release() }
            }
        } catch (e: Exception) {
            finishInstall(o, failure = "${e.javaClass.simpleName}: ${e.message ?: "install failed"}", kind = UpdateError.LOCAL)
        } finally {
            runCatching { Files.deleteIfExists(part) }
            runCatching { channel.close() }
            runCatching { Files.deleteIfExists(lockFile) }
        }
    }

    /**
     * At check time: the signed `depends` against the running loader, minus what the running jar
     * nests itself (those ship inside the new jar and are checked against its nested versions after
     * the download). Null when nothing is missing.
     */
    private fun incompatibility(manifest: ReleaseManifest): String? {
        val nestedIds = FabricLoader.getInstance().getModContainer(ReleaseVerifier.MOD_ID)
            .map { c -> c.containedMods.map { it.metadata.id }.toSet() }
            .orElse(emptySet())
        val depends = JarMetadataReader.predicates(manifest.depends).filterKeys { it !in nestedIds }
        val meta = JarMetadata(ReleaseVerifier.MOD_ID, manifest.version, depends, emptyMap(), emptyMap())
        val problems = DependencyGuard.check(meta, ::installedVersion)
        if (problems.isEmpty()) return null
        return "Soul ${manifest.version} " + problems.joinToString("; ") { it.describe() }
    }

    private fun finishInstall(o: Offer, failure: String?, kind: String? = null) {
        synchronized(lock) {
            downloading = false
            verifying = false
            if (failure == null) {
                ready = o.release
                offer = null
                error = null
                errorKind = null
            } else {
                error = failure
                errorKind = kind ?: UpdateError.LOCAL
            }
        }
        if (failure != null) logger.warn("Update to ${o.release.version} failed ($kind): $failure")
        publish()
    }

    // ---------------------------------------------------------------- dev preview (`/soul dev update <state>`)

    /** States `/soul dev update` shows with fake releases; nothing is downloaded or installed. */
    val PREVIEW_STATES = listOf(
        "available", "beta", "downloading", "verifying", "ready", "updated",
        "dlfail", "verifyfail", "incompatible", "revoked", "minimum", "uptodate", "real",
    )

    /** True while a dev preview is shown: installs run a fake download instead ([previewInstall]). */
    @Volatile
    private var preview = false

    /**
     * Dev only: puts the updater into [state] with fake releases so every surface can be inspected
     * without a backend. `real` drops the preview and checks for real.
     */
    fun preview(state: String) {
        val base = ReleaseVersions.parse(Soul.version)?.let { "${it.getVersionComponent(0)}.${it.getVersionComponent(1)}" } ?: "0.1"
        val minor = base.substringAfter('.').toIntOrNull() ?: 1
        val major = base.substringBefore('.')
        val v1 = "$major.$minor.${(ReleaseVersions.parse(Soul.version)?.getVersionComponent(2) ?: 0) + 1}"
        val v2 = "$major.${minor + 1}.0"
        val vb = "$major.${minor + 1}.1-beta.1"
        val notes = listOf(
            ReleaseNote(v2, ReleaseVersions.STABLE, "2026-11-20T18:00:00Z", "## New\n- **Auto updater**: Soul updates itself in one click\n- `/soul update` checks right away\n## Changes\n- Faster dungeon map redraws"),
            ReleaseNote(v1, ReleaseVersions.STABLE, "2026-11-14T12:00:00Z", "## Fixes\n- **Sack tracker** keeps counts across profile switches\n- Price tooltips no longer flicker in the Bazaar"),
        )
        val betaNote = ReleaseNote(vb, ReleaseVersions.BETA, "2026-11-22T09:00:00Z", "## Beta\n- New **mining HUD** layout, feedback welcome\n- May still have rough edges")
        val stable = UpdateRelease(v2, ReleaseVersions.STABLE, notes, notes.first().publishedAt, 5_638_920)
        val beta = UpdateRelease(vb, ReleaseVersions.BETA, listOf(betaNote) + notes, betaNote.publishedAt, 5_702_114)
        if (state == "real") {
            preview = false
            synchronized(lock) {
                offer = null; ready = null; downloading = false; verifying = false; error = null; errorKind = null
                listOf(UpdateNotice.REVOKED, UpdateNotice.BELOW_MINIMUM).forEach { notices.remove(it) }
            }
            publish()
            checkNow()
            return
        }
        preview = true
        synchronized(lock) {
            offer = null; ready = null; downloading = false; verifying = false; progress = 0f; error = null; errorKind = null
            updated = null
            listOf(UpdateNotice.REVOKED, UpdateNotice.BELOW_MINIMUM).forEach { notices.remove(it) }
            lastCheckedAt = System.currentTimeMillis() - 3 * 60_000
            val o = Offer(if (state == "beta") beta else stable, "", null, null)
            if (state == "available" || state == "beta") offer = o
            if (state == "downloading") { offer = o; downloading = true; progress = 0.46f }
            if (state == "verifying") { offer = o; downloading = true; progress = 1f; verifying = true }
            if (state == "ready") ready = stable
            if (state == "updated") updated = UpdatedInfo(Soul.version, v2, notes)
            if (state == "dlfail") { offer = o; error = "Request timed out after 30 s"; errorKind = UpdateError.DOWNLOAD; progress = 0.38f }
            if (state == "verifyfail") { offer = o; error = "sha256 does not match the signed manifest"; errorKind = UpdateError.VERIFY }
            if (state == "incompatible") offer = Offer(stable, "", null, "Soul $v2 needs Fabric Loader >=0.19.4 (installed: ${installedVersion("fabricloader")?.friendlyString})")
            if (state == "revoked") {
                offer = o
                notices[UpdateNotice.REVOKED] = UpdateNotice(UpdateNotice.REVOKED, "Soul ${Soul.version} was withdrawn: it can reset your sack counts when you switch profiles. Please update.", "it can reset your sack counts when you switch profiles")
            }
            if (state == "minimum") {
                offer = o
                notices[UpdateNotice.BELOW_MINIMUM] = UpdateNotice(UpdateNotice.BELOW_MINIMUM, "Soul ${Soul.version} is no longer supported (minimum $v1). Please update.", v1)
            }
        }
        publish()
    }

    /** The preview's "download": a few seconds of progress, the signature step, then ready. */
    private suspend fun previewInstall(o: Offer) {
        for (i in 1..40) {
            delay(100)
            synchronized(lock) { progress = i / 40f }
            publish()
        }
        synchronized(lock) { verifying = true }
        publish()
        delay(900)
        finishInstall(o, failure = null)
    }

    // ---------------------------------------------------------------- helpers

    /** This installation can place updates at all (regular jar in `mods/`, a trusted key in the build). */
    val canInstallUpdates: Boolean
        get() = target is InstallTarget.Folder && TrustedKeys.keys.isNotEmpty()

    private fun canInstall(): Boolean = (preview || canInstallUpdates) && offer?.incompatible == null

    private fun cannotInstallReason(): String? {
        offer?.incompatible?.let { return it }
        if (preview) return null
        val t = target
        if (t is InstallTarget.Unsupported) return t.reason
        if (TrustedKeys.keys.isEmpty()) return "this build cannot verify updates"
        return null
    }

    private fun isSkipped(version: String): Boolean = cfg.updates.skippedVersion == version

    private fun channel(): String = if (cfg.updates.channel == ReleaseVersions.BETA) ReleaseVersions.BETA else ReleaseVersions.STABLE

    private fun minecraftVersion(): Version? =
        FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version }.orElse(null)

    private fun installedVersion(id: String): Version? =
        FabricLoader.getInstance().getModContainer(id).map { it.metadata.version }.orElse(null)

    /** Rebuild [snapshot] and announce it on the client thread. */
    private fun publish() {
        val s = synchronized(lock) {
            val r = ready
            val o = offer
            val phase = when {
                downloading -> UpdatePhase.DOWNLOADING
                r != null -> UpdatePhase.READY_RESTART
                error != null && o != null -> UpdatePhase.ERROR
                o != null -> UpdatePhase.AVAILABLE
                updated != null -> UpdatePhase.UPDATED
                else -> UpdatePhase.IDLE
            }
            val release = if (phase == UpdatePhase.READY_RESTART) r else o?.release
            UpdateSnapshot(
                phase = phase,
                running = Soul.version,
                release = release,
                skipped = release != null && isSkipped(release.version),
                canInstall = canInstall(),
                cannotInstallReason = cannotInstallReason(),
                progress = progress,
                verifying = verifying,
                error = error,
                errorKind = errorKind,
                checkError = checkError,
                updated = updated,
                notices = notices.values.toList(),
                incompatible = offer?.incompatible,
                checking = checking,
                lastCheckedAt = lastCheckedAt,
                autoInstall = autoInstalled,
            )
        }
        snapshot = s
        val mc = Minecraft.getInstance() ?: return
        mc.execute { Events.publish(UpdateStateChanged(s)) }
    }

    // ---------------------------------------------------------------- state file

    @Synchronized
    private fun loadState(): UpdaterStateFile {
        if (!::stateFile.isInitialized || !Files.exists(stateFile)) return UpdaterStateFile()
        return try {
            json.decodeFromString(UpdaterStateFile.serializer(), Files.readString(stateFile))
        } catch (e: Exception) {
            DataBackups.backup(stateFile, "unreadable")
            logger.warn("updates/state.json could not be read (${e.message}); starting fresh, a backup was kept")
            UpdaterStateFile()
        }
    }

    @Synchronized
    private fun saveState(state: UpdaterStateFile) {
        if (!::stateFile.isInitialized) return
        try {
            Files.createDirectories(stateFile.parent)
            val tmp = stateFile.resolveSibling("state.json.tmp")
            Files.writeString(tmp, json.encodeToString(UpdaterStateFile.serializer(), state))
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.warn("Could not save updates/state.json", e)
        }
    }
}
