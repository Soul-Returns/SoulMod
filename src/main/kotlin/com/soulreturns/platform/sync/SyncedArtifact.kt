package com.soulreturns.platform.sync

import java.io.File

/**
 * Describes a single artifact under cloud-sync management.
 *
 * @property kind        Logical identifier — used as the backend URL suffix and meta key.
 * @property file        Local file path. The engine reads/writes this verbatim and never parses it.
 * @property enabled     Per-tick toggle. Returning `false` makes the engine skip this artifact entirely.
 * @property onAfterPull Called after the engine writes new remote content to [file]. Use this to refresh
 *                       in-memory representations (e.g. owo-config wrapper, GuiLayoutManager state).
 *                       Invoked on the client tick thread.
 */
data class SyncedArtifact(
    val kind: SyncKind,
    val file: File,
    val enabled: () -> Boolean,
    val onAfterPull: () -> Unit,
)
