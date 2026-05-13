package com.soulreturns.platform.sync

import com.google.gson.JsonObject
import java.io.File

/**
 * Describes a single artifact under cloud-sync management.
 *
 * @property kind         Logical identifier — used as the backend URL suffix and meta key.
 * @property file         Local file path. The engine reads/writes this verbatim and never parses it.
 * @property enabled      Per-tick toggle. Returning `false` makes the engine skip this artifact entirely.
 * @property onAfterPull  Called after the engine writes new remote content to [file]. Use this to refresh
 *                        in-memory representations (e.g. owo-config wrapper, GuiLayoutManager state).
 *                        Invoked on the client tick thread.
 * @property defaultsJson Optional defaults snapshot, attached to every PUT as a sibling of `content`.
 *                        The backend stores it for the admin UI's "reset to default" buttons — so the
 *                        backend never needs to know what the mod's defaults are. Most artifacts return
 *                        `null` (gui_layout's defaults are per-element seeding via `updateTextBlock`,
 *                        and stats have no meaningful "default" — zero everywhere is uninformative).
 *                        CONFIG returns the full owo-config default tree via [com.soulreturns.config.SoulConfigHolder.defaultsJson].
 */
data class SyncedArtifact(
    val kind: SyncKind,
    val file: File,
    val enabled: () -> Boolean,
    val onAfterPull: () -> Unit,
    val defaultsJson: () -> JsonObject? = { null },
)
