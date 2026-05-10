package com.soulreturns.ui.config.model

import io.wispforest.owo.config.Option
import net.minecraft.network.chat.Component

/**
 * One category in the config sidebar. The entries are normalized — names are translated,
 * subcategories are filtered/sorted, virtual subs (no backing fields) are already merged in.
 */
internal data class CategoryEntry(
    val id: String,
    val displayName: Component,
    val subcategories: List<SubcategoryEntry>,
)

/** One subcategory: a list of [Option]s plus its category coordinates and display label. */
internal data class SubcategoryEntry(
    val catId: String,
    val subId: String,
    val displayName: Component,
    val options: List<Option<*>>,
)

/** Cross-navigation row: clicking jumps to (`targetCat`, `targetSub`) in the same screen. */
internal data class LinkTarget(
    val label: String,
    val targetCat: String,
    val targetSub: String,
)

/**
 * Generic action row: a label paired with a button that runs an arbitrary callback.
 * The callback receives a [ConfigScreenContext] so it can call back into screen lifecycle
 * methods (e.g. `rebuildContent`) without holding a hard reference to the screen.
 */
internal data class ActionRowSpec(
    val label: String,
    val buttonText: String,
    val action: (ConfigScreenContext) -> Unit,
)

/**
 * Surface area exposed *from* the screen *to* extracted helpers (row builders, link rows,
 * keybind capture, etc.). Keeps helpers decoupled from the concrete `SoulConfigScreen` class.
 */
internal interface ConfigScreenContext {
    /** Persist the owo-config wrapper to disk. */
    fun save()

    /** Rebuild only the scrolling content body (cheap; preserves header + sidebar state). */
    fun rebuildContentBody()

    /** Rebuild breadcrumb + content body together. */
    fun rebuildContent()

    /** Switch active sub, expanding the parent category and refreshing both sidebar and content. */
    fun navigateTo(
        catId: String,
        subId: String
    )

    /** Reset the wrapper to disk state and rebuild content. Used by the Reload action row. */
    fun reloadConfig()

    /**
     * Toggle keybind-capture for [opt]: passing the same option twice cancels capture.
     * The next key/mouse press the screen sees will bind this option. Screen owns the
     * capture state internally — helpers just trigger it.
     */
    fun requestKeybindCapture(opt: Option<String>)

    /** True while [opt] is the option currently waiting for a key press. */
    fun isCapturing(opt: Option<String>): Boolean
}
