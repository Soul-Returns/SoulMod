package com.soulreturns.platform.updater

import com.soulreturns.core.events.Event
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------- backend contract (GET /mod/update)

/** `GET /mod/update` (research doc §API). Only the manifest + signature inside [latest] are trusted. */
@Serializable
data class UpdateCheckResponse(
    val schemaVersion: Int = 1,
    val latest: UpdateOffer? = null,
    val otherMinecraft: OtherMinecraftRelease? = null,
    val minimumVersion: String? = null,
    val current: CurrentVersionStatus? = null,
    val message: String? = null,
)

@Serializable
data class UpdateOffer(
    val version: String,
    val channel: String = "",
    val minecraft: String = "",
    val manifest: String = "",
    val signature: String = "",
    val download: String = "",
    val size: Long = 0,
    val sha256: String = "",
    /** Not signed; display only. Markdown. Superseded by [notes] where the backend sends them. */
    val changelog: String? = null,
    val publishedAt: String? = null,
    /** Every release between the running version and this one on the channel, newest first. */
    val notes: List<ReleaseNote> = emptyList(),
)

/** One release's notes (unsigned markdown: `## ` headings, `- ` bullets, `**bold**`, `` `code` ``). */
@Serializable
data class ReleaseNote(
    val version: String,
    val channel: String = "",
    val publishedAt: String? = null,
    val changelog: String? = null,
)

@Serializable
data class OtherMinecraftRelease(val version: String, val minecraft: String)

@Serializable
data class CurrentVersionStatus(
    val known: Boolean = false,
    val revoked: Boolean = false,
    val revokedReason: String? = null,
    val belowMinimum: Boolean = false,
)

// ---------------------------------------------------------------- persisted (config/soul/updates/state.json)

/** The updater's own player file. New fields need defaults; a rename needs a load-time step. */
@Serializable
data class UpdaterStateFile(
    val schemaVersion: Int = 1,
    /** The Soul version of the previous start; drives "Updated to vX". */
    val lastRunVersion: String? = null,
    /** A verified jar placed in the mods folder, waiting for the restart. */
    val pending: PendingInstall? = null,
    /** "Updated to vX" not yet shown in chat. */
    val updated: UpdatedInfo? = null,
)

@Serializable
data class PendingInstall(val version: String, val file: String, val from: String, val notes: List<ReleaseNote> = emptyList())

@Serializable
data class UpdatedInfo(val from: String, val to: String, val notes: List<ReleaseNote> = emptyList())

// ---------------------------------------------------------------- what the UI reads

/**
 * Where the updater stands, for the UI. Compare with `==`, never a subject `when` (rule://mc-api).
 *
 * Precedence when several apply: DOWNLOADING, READY_RESTART, ERROR, AVAILABLE, UPDATED, IDLE.
 */
enum class UpdatePhase { IDLE, AVAILABLE, DOWNLOADING, READY_RESTART, UPDATED, ERROR }

/** A release the UI can describe. [notes]: every version it skips over, newest first. */
data class UpdateRelease(
    val version: String,
    val channel: String,
    val notes: List<ReleaseNote>,
    val publishedAt: String?,
    val size: Long,
)

/** Why an install failed ([UpdateSnapshot.errorKind]); each gets its own message and buttons. */
object UpdateError {
    /** Transport, timeout, HTTP error: retry or use the download page. */
    const val DOWNLOAD = "download"

    /** The file did not match the signed release (size, sha256, jar id/version); nothing installed. */
    const val VERIFY = "verify"

    /** The jar's dependencies are not met by this loader. */
    const val INCOMPATIBLE = "incompatible"

    /** Disk, lock or anything else local. */
    const val LOCAL = "local"
}

/**
 * A standing notice (persistent banner material), independent of [UpdatePhase].
 *
 * Kinds: [REVOKED] and [BELOW_MINIMUM] (the running version; features keep working), [BLOCKED] (a
 * newer Soul jar is in the mods folder but Fabric could not load it), [OTHER_MINECRAFT] (a newer Soul
 * exists only for another Minecraft version), [MESSAGE] (admin text from the backend).
 */
data class UpdateNotice(val kind: String, val message: String, val detail: String? = null) {
    companion object {
        const val REVOKED = "revoked"
        const val BELOW_MINIMUM = "minimum"
        const val BLOCKED = "blocked"
        const val OTHER_MINECRAFT = "otherMinecraft"
        const val MESSAGE = "message"
    }
}

/**
 * Immutable view of the updater. Read [UpdateService.snapshot] every frame or subscribe to
 * [UpdateStateChanged].
 *
 * @property release the offered release (AVAILABLE, DOWNLOADING, ERROR) or the placed one (READY_RESTART).
 * @property skipped [release] is the player's skipped version: not announced, not auto-installed.
 * @property canInstall false in dev runs, when Soul's jar is not directly in the mods folder, when
 *   this build has no trusted key, or when [incompatible] is set; [cannotInstallReason] says why.
 * @property incompatible what the installed loader lacks for [release] ("Soul 0.3.0 needs Fabric
 *   Loader >=0.20.0 (installed: 0.19.3)"), decided at check time from the signed `depends`.
 * @property progress 0..1 while downloading.
 * @property verifying the download finished and the jar is being checked against the signed release.
 * @property error why the last install failed (ERROR); cleared by the next install or check.
 * @property errorKind an [UpdateError] constant for [error].
 * @property checkError why the last check failed, for a manual "Check now".
 * @property updated set on the first start of a newer version until [UpdateService.dismissUpdated].
 * @property lastCheckedAt epoch ms of the last successful check, 0 = never.
 * @property autoInstall the current (or last) install was started by `updates.autoInstall`, not a click.
 */
data class UpdateSnapshot(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val running: String = "",
    val release: UpdateRelease? = null,
    val skipped: Boolean = false,
    val canInstall: Boolean = false,
    val cannotInstallReason: String? = null,
    val progress: Float = 0f,
    val verifying: Boolean = false,
    val error: String? = null,
    val errorKind: String? = null,
    val checkError: String? = null,
    val updated: UpdatedInfo? = null,
    val notices: List<UpdateNotice> = emptyList(),
    val incompatible: String? = null,
    val checking: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val autoInstall: Boolean = false,
) {
    /** The running version was withdrawn or is below the minimum: the red banner's notice. */
    val urgent: UpdateNotice?
        get() = notices.firstOrNull { it.kind == UpdateNotice.REVOKED } ?: notices.firstOrNull { it.kind == UpdateNotice.BELOW_MINIMUM }
}

/** Published on the client thread whenever [UpdateService.snapshot] changes. */
data class UpdateStateChanged(val snapshot: UpdateSnapshot) : Event
