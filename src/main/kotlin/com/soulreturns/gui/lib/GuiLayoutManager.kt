package com.soulreturns.gui.lib

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.soulreturns.util.DebugLogger
import java.io.File

/**
 * Top-level layout definition for the GUI library.
 *
 * [schemaVersion] is stamped on every save. Old files (written before alignment metadata
 * existed) deserialize as version `0` because Gson bypasses Kotlin constructor defaults via
 * `Unsafe`. Loading code checks the version and wipes the file when it predates the current
 * schema — see [GuiLayoutManager.CURRENT_SCHEMA_VERSION] and the wipe path in
 * [GuiLayoutManager.loadOrInitialize].
 */
data class GuiLayout(
    val elements: List<GuiElement> = emptyList(),
    val schemaVersion: Int = GuiLayoutManager.CURRENT_SCHEMA_VERSION,
)

/**
 * Manages the current GUI layout and JSON persistence.
 *
 * This class is library-level and does not know about Minecraft/Fabric. Hosts
 * are responsible for configuring the layout file path and for calling
 * [load] and [save] at appropriate times (e.g. on mod init and when closing
 * an "Edit GUI" screen).
 */
object GuiLayoutManager {
    /**
     * Bump this when the layout schema gains a field that older files won't carry. On the
     * next load after the bump, any file with a lower `schemaVersion` is wiped — the in-
     * memory layout is replaced with an empty one, and the next per-frame
     * `SoulHud.ensureLayoutElement` pass rebuilds entries from the new registration
     * defaults. Users with a tweaked layout will see it reset to defaults exactly once.
     *
     * **History:**
     *  - `0`: pre-versioning. Predates alignment fields (`horizontalAnchor` /
     *    `verticalAnchor`) on `SoulHudElement`. Wiped to fix a deserialization NPE — Gson
     *    bypasses Kotlin constructor defaults via Unsafe, so the new enum fields landed as
     *    null at runtime and `SoulHud.resolveBaseX` exploded on `null.ordinal()`.
     *  - `2`: alignment-aware schema.
     */
    const val CURRENT_SCHEMA_VERSION: Int = 2

    private val gson: Gson =
        GsonBuilder()
            .registerTypeAdapterFactory(GuiRuntimeTypeAdapterFactory())
            .setPrettyPrinting()
            .create()

    /**
     * Set of element ids that are known to the mod. Currently used only for
     * debugging / future extension; layout loading no longer filters by this
     * set so that saved elements always restore correctly.
     */
    private val knownElementIds: MutableSet<GuiElementId> = mutableSetOf()

    /**
     * File where the layout is persisted. Hosts should call [configure] to
     * set this to an appropriate config path.
     */
    private var layoutFile: File? = null

    @Volatile
    private var currentLayout: GuiLayout = GuiLayout()

    /**
     * Configure the path where the layout JSON should be stored.
     */
    fun configure(file: File) {
        layoutFile = file
        DebugLogger.logGuiLayout("Configured GUI layout file at: ${file.absolutePath}")
    }

    /**
     * Register a GUI element id that is provided by mod code. Only elements
     * with registered ids are kept when loading/saving layouts.
     */
    @Synchronized
    fun registerElementId(id: GuiElementId) {
        knownElementIds += id
    }

    /**
     * Returns the current layout snapshot.
     */
    fun getLayout(): GuiLayout =
        currentLayout.copy(
            elements = currentLayout.elements.filterNotNull(),
        )

    /**
     * Replace the entire layout in memory (no implicit save).
     */
    @Synchronized
    fun setLayout(layout: GuiLayout) {
        currentLayout = layout.copy(elements = layout.elements.filterNotNull())
    }

    /**
     * Convenience: return the current elements.
     */
    fun getElements(): List<GuiElement> = currentLayout.elements.filterNotNull()

    @Synchronized
    fun updateElementPosition(
        id: GuiElementId,
        anchorX: Double,
        anchorY: Double,
        offsetX: Int,
        offsetY: Int,
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        when (element) {
                            is TextBlockElement ->
                                element.copy(
                                    anchorX = anchorX,
                                    anchorY = anchorY,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                )
                            is ItemTrackerElement ->
                                element.copy(
                                    anchorX = anchorX,
                                    anchorY = anchorY,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                )
                            is SoulHudElement ->
                                element.copy(
                                    anchorX = anchorX,
                                    anchorY = anchorY,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                )
                        }
                    },
            )
    }

    /**
     * Apply a corner-preset anchor to a [SoulHudElement]: the element snaps to one of the
     * five screen-edge presets exposed by the `/soul gui` right-click menu (Top Left, Top
     * Right, Bottom Left, Bottom Right, Center). Offset resets to `(0, 0)` so the element
     * sits flush with the chosen edge.
     *
     * Other element types are no-ops — only `SoulHudElement` carries the alignment metadata
     * that lets the renderer pivot on its own size.
     */
    @Synchronized
    fun updateSoulHudAnchor(
        id: GuiElementId,
        anchorX: Double,
        anchorY: Double,
        horizontalAnchor: HudHorizontalAnchor,
        verticalAnchor: HudVerticalAnchor,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        if (element !is SoulHudElement) return@map element
                        element.copy(
                            anchorX = anchorX,
                            anchorY = anchorY,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            horizontalAnchor = horizontalAnchor,
                            verticalAnchor = verticalAnchor,
                        )
                    },
            )
    }

    /**
     * Flip the per-HUD `showBackground` override on a [SoulHudElement]. Set [value] to
     * `true` / `false` for an explicit override, or `null` to clear and fall back to the
     * global `cfg.general.ui.hudBackground`.
     */
    @Synchronized
    fun updateSoulHudShowBackground(
        id: GuiElementId,
        value: Boolean?,
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        if (element !is SoulHudElement) return@map element
                        element.copy(showBackground = value)
                    },
            )
    }

    /** Per-HUD override for `cfg.general.ui.useMinecraftFont`. See [updateSoulHudShowBackground]. */
    @Synchronized
    fun updateSoulHudUseMinecraftFont(
        id: GuiElementId,
        value: Boolean?,
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        if (element !is SoulHudElement) return@map element
                        element.copy(useMinecraftFont = value)
                    },
            )
    }

    /** Per-HUD override for `cfg.general.ui.hudTextShadow`. See [updateSoulHudShowBackground]. */
    @Synchronized
    fun updateSoulHudUseTextShadow(
        id: GuiElementId,
        value: Boolean?,
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        if (element !is SoulHudElement) return@map element
                        element.copy(useTextShadow = value)
                    },
            )
    }

    /** Per-HUD override for `cfg.general.ui.hudBoldFont`. See [updateSoulHudShowBackground]. */
    @Synchronized
    fun updateSoulHudUseBoldFont(
        id: GuiElementId,
        value: Boolean?,
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        if (element !is SoulHudElement) return@map element
                        element.copy(useBoldFont = value)
                    },
            )
    }

    /**
     * Clear `showBackground` on every [SoulHudElement] — restores every HUD's per-HUD
     * Background override to "follow global". Backs the `Reset per HUD settings (Background)`
     * button under `General → UI` in the config screen.
     */
    @Synchronized
    fun resetAllSoulHudShowBackground() {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element !is SoulHudElement) return@map element
                        if (element.showBackground == null) return@map element
                        element.copy(showBackground = null)
                    },
            )
    }

    /** Bulk-reset analog of [resetAllSoulHudShowBackground] for the Minecraft-font override. */
    @Synchronized
    fun resetAllSoulHudUseMinecraftFont() {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element !is SoulHudElement) return@map element
                        if (element.useMinecraftFont == null) return@map element
                        element.copy(useMinecraftFont = null)
                    },
            )
    }

    @Synchronized
    fun updateElementScale(
        id: GuiElementId,
        scale: Float
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id) return@map element
                        val clamped = scale.coerceIn(0.25f, 4.0f)
                        when (element) {
                            is TextBlockElement -> element.copy(scale = clamped)
                            is ItemTrackerElement -> element.copy(scale = clamped)
                            is SoulHudElement -> element.copy(scale = clamped)
                        }
                    },
            )
    }

    @Synchronized
    fun updateTrackerCounts(
        id: GuiElementId,
        entryId: String,
        delta: Int
    ) {
        currentLayout =
            currentLayout.copy(
                elements =
                    currentLayout.elements.map { element ->
                        if (element.id != id || element !is ItemTrackerElement) return@map element

                        val updatedEntries =
                            element.entries.map { entry ->
                                if (entry.entryId != entryId) return@map entry
                                val newCount = (entry.currentCount + delta).coerceAtLeast(0)
                                entry.copy(currentCount = newCount)
                            }

                        element.copy(entries = updatedEntries)
                    },
            )
    }

    /**
     * Reset the layout back to its default state.
     *
     * This clears the in-memory layout and deletes the persisted layout file.
     * On subsequent ticks, feature modules that use GuiLayoutApi will reseed
     * their default elements with their original anchor/scale values.
     */
    @Synchronized
    fun resetToDefaults() {
        // Clear in-memory layout so the next feature tick recreates elements
        // using their default layout parameters.
        currentLayout = GuiLayout()

        // Delete the persisted layout file so future runs also start from
        // defaults. Ignore failures silently; they'll be logged via save().
        val file = layoutFile
        if (file != null && file.exists()) {
            try {
                if (!file.delete()) {
                    DebugLogger.logGuiLayout(
                        "Failed to delete GUI layout file at ${file.absolutePath} during reset; will be overwritten on next save"
                    )
                } else {
                    DebugLogger.logGuiLayout("Deleted GUI layout file at ${file.absolutePath}; layout will be reseeded from defaults")
                }
            } catch (e: Exception) {
                DebugLogger.logGuiLayout(
                    "Exception while deleting GUI layout file at ${file.absolutePath}: ${e::class.java.name}: ${e.message}"
                )
            }
        }
    }

    /**
     * The configured layout file. Returns null until [configure] is called.
     * Used by cloud sync to register the artifact.
     */
    fun layoutFile(): File? = layoutFile

    /**
     * Read the raw schema version from the JSON. Returns `null` if the file doesn't have a
     * `schemaVersion` key at all — necessary because Gson applies Kotlin constructor defaults
     * for [GuiLayout] (all primary-ctor fields have defaults → no-arg constructor exists), so
     * a deserialized value of `CURRENT_SCHEMA_VERSION` doesn't actually prove the field was
     * present in the JSON. Element-level Kotlin defaults are *not* applied (`SoulHudElement`
     * has a no-default `id` field) — that's why old files crash on null enum fields and why
     * we need this raw probe to catch them.
     */
    private fun readRawSchemaVersion(json: String): Int? =
        try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) {
                null
            } else {
                val obj = root.asJsonObject
                if (!obj.has("schemaVersion")) null else obj.get("schemaVersion").asInt
            }
        } catch (e: Exception) {
            null
        }

    /**
     * Re-read the layout from disk into the in-memory state. Used by cloud sync
     * after a remote pull writes a new gui_layout.json. Skips initialization
     * (does not write defaults) — call [loadOrInitialize] for that.
     */
    @Synchronized
    fun reload() {
        val file = layoutFile ?: return
        if (!file.exists()) return
        try {
            val json = file.readText()
            val rawVersion = readRawSchemaVersion(json)
            if (rawVersion == null || rawVersion < CURRENT_SCHEMA_VERSION) {
                DebugLogger.logGuiLayout(
                    "Reload: GUI layout schemaVersion=$rawVersion < $CURRENT_SCHEMA_VERSION; " +
                        "wiping to defaults"
                )
                currentLayout = GuiLayout()
                save()
                return
            }
            val type = object : TypeToken<GuiLayout>() {}.type
            val loaded = gson.fromJson<GuiLayout>(json, type) ?: GuiLayout()
            currentLayout = loaded.copy(elements = loaded.elements.filterNotNull())
            DebugLogger.logGuiLayout(
                "Reloaded GUI layout from ${file.absolutePath} (${currentLayout.elements.size} elements)"
            )
        } catch (e: Exception) {
            DebugLogger.logGuiLayout(
                "Failed to reload GUI layout from ${file.absolutePath}: ${e::class.java.name}: ${e.message}"
            )
        }
    }

    /**
     * Load layout from the configured file if it exists; otherwise, persist the
     * current in-memory layout as the initial default.
     */
    @Synchronized
    fun loadOrInitialize() {
        val file = layoutFile ?: return
        if (!file.exists()) {
            save()
            DebugLogger.logGuiLayout(
                "No existing GUI layout, wrote default layout with ${currentLayout.elements.size} elements to ${file.absolutePath}"
            )
            return
        }

        try {
            val json = file.readText()
            // Schema gate: a saved file from before the alignment field rolled out has no
            // `schemaVersion` key. Wipe it so per-frame `ensureLayoutElement` rebuilds from
            // the new registration defaults — the user pays a one-time HUD-position reset
            // to clear a deserialization landmine that would otherwise NPE on the null
            // enum fields Gson leaves on each `SoulHudElement`.
            val rawVersion = readRawSchemaVersion(json)
            DebugLogger.logGuiLayout(
                "GUI layout file at ${file.absolutePath} reports schemaVersion=$rawVersion " +
                    "(current=$CURRENT_SCHEMA_VERSION)"
            )
            if (rawVersion == null || rawVersion < CURRENT_SCHEMA_VERSION) {
                DebugLogger.logGuiLayout(
                    "GUI layout schemaVersion=$rawVersion < $CURRENT_SCHEMA_VERSION; wiping to defaults"
                )
                currentLayout = GuiLayout()
                save()
                return
            }
            val type = object : TypeToken<GuiLayout>() {}.type
            val loaded = gson.fromJson(json, type) ?: GuiLayout()
            DebugLogger.logGuiLayout(
                "Raw loaded GUI layout from ${file.absolutePath} contains ${loaded.elements.size} elements"
            )
            loaded.elements.filterNotNull().forEach { e ->
                DebugLogger.logGuiLayout("Loaded element: ${e::class.java.simpleName} id='${e.id}'")
            }
            // Keep all non-null elements as-is; we no longer filter by
            // knownElementIds to avoid dropping valid saved elements.
            currentLayout =
                loaded.copy(
                    elements = loaded.elements.filterNotNull(),
                )
            DebugLogger.logGuiLayout(
                "Loaded GUI layout from ${file.absolutePath} with ${currentLayout.elements.size} elements after filtering"
            )
        } catch (e: Exception) {
            // On any error (e.g., schema change), keep the current in-memory
            // layout (which should contain seeded defaults from features) and
            // overwrite the bad file so subsequent runs succeed.
            DebugLogger.logGuiLayout(
                "Failed to load GUI layout from ${file.absolutePath}: ${e::class.java.name}: ${e.message}; rewriting with current layout"
            )
            save()
        }
    }

    /**
     * Save the current layout to the configured file, if set.
     */
    @Synchronized
    fun save() {
        val file = layoutFile ?: return
        val saved =
            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val json = gson.toJson(currentLayout)
                file.writeText(json)
                DebugLogger.logGuiLayout("Saved GUI layout to ${file.absolutePath} with ${currentLayout.elements.size} elements")
                true
            } catch (e: Exception) {
                DebugLogger.logGuiLayout(
                    "Failed to save GUI layout to ${file.absolutePath}: ${e::class.java.name}: ${e.message}"
                )
                false
            }
        if (saved) {
            // Push the new layout to the backend (debounced ~2 s, so multiple saves coalesce).
            try {
                com.soulreturns.platform.sync.SyncEngine.notifyChanged(
                    com.soulreturns.platform.sync.SyncKind.GUI_LAYOUT
                )
            } catch (_: Throwable) {
                // SyncEngine not initialised yet (early bootstrap) — periodic scan will catch up.
            }
        }
    }
}

/**
 * Runtime type adapter factory to preserve the concrete GuiElement subtype
 * information in JSON. Implemented minimally here so the layout file can
 * contain mixed TextBlockElement and ItemTrackerElement instances.
 */
class GuiRuntimeTypeAdapterFactory : com.google.gson.TypeAdapterFactory {
    override fun <T> create(
        gson: Gson,
        type: com.google.gson.reflect.TypeToken<T>
    ): com.google.gson.TypeAdapter<T>? {
        // Only wrap the abstract base type GuiElement; concrete subclasses
        // like TextBlockElement should be (de)serialized normally.
        if (type.rawType != GuiElement::class.java) return null

        val elementAdapter = gson.getDelegateAdapter(this, type)
        return object : com.google.gson.TypeAdapter<T>() {
            override fun write(
                out: com.google.gson.stream.JsonWriter,
                value: T
            ) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                out.beginObject()
                val element = value as GuiElement
                val kind =
                    when (element) {
                        is TextBlockElement -> "text_block"
                        is ItemTrackerElement -> "item_tracker"
                        is SoulHudElement -> "soul_hud"
                    }
                out.name("type").value(kind)
                out.name("data")
                when (element) {
                    is TextBlockElement -> gson.toJson(element, TextBlockElement::class.java, out)
                    is ItemTrackerElement -> gson.toJson(element, ItemTrackerElement::class.java, out)
                    is SoulHudElement -> gson.toJson(element, SoulHudElement::class.java, out)
                }
                out.endObject()
            }

            @Suppress("UNCHECKED_CAST")
            override fun read(`in`: com.google.gson.stream.JsonReader): T? {
                val json = com.google.gson.JsonParser.parseReader(`in`).asJsonObject
                val typeName = json.get("type")?.asString ?: return null
                val data = json.get("data") ?: return null
                val targetType =
                    when (typeName) {
                        "text_block" -> TextBlockElement::class.java
                        "item_tracker" -> ItemTrackerElement::class.java
                        // Legacy "tracker_overlay" entries from before P3 simply get dropped
                        // on load — the new FishingHud uses "soul_hud" and self-heals into
                        // the layout via SoulHud.dispatchAll.
                        "soul_hud" -> SoulHudElement::class.java
                        else -> return null
                    }
                // The Gson call returns a concrete GuiElement subtype; we
                // explicitly trust this mapping and suppress the generic cast.
                return gson.fromJson(data, targetType) as T
            }
        }
    }
}
