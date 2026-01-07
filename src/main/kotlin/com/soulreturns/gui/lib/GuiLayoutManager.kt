package com.soulreturns.gui.lib

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.soulreturns.util.DebugLogger
import java.io.File

/**
 * Top-level layout definition for the GUI library.
 */
data class GuiLayout(
    val elements: List<GuiElement> = emptyList(),
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
    private val gson: Gson = GsonBuilder()
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
    fun getLayout(): GuiLayout = currentLayout.copy(
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
        currentLayout = currentLayout.copy(
            elements = currentLayout.elements.map { element ->
                if (element.id != id) return@map element
                when (element) {
                    is TextBlockElement -> element.copy(
                        anchorX = anchorX,
                        anchorY = anchorY,
                        offsetX = offsetX,
                        offsetY = offsetY,
                    )
                    is ItemTrackerElement -> element.copy(
                        anchorX = anchorX,
                        anchorY = anchorY,
                        offsetX = offsetX,
                        offsetY = offsetY,
                    )
                }
            },
        )
    }

    @Synchronized
    fun updateElementScale(id: GuiElementId, scale: Float) {
        currentLayout = currentLayout.copy(
            elements = currentLayout.elements.map { element ->
                if (element.id != id) return@map element
                val clamped = scale.coerceIn(0.25f, 4.0f)
                when (element) {
                    is TextBlockElement -> element.copy(scale = clamped)
                    is ItemTrackerElement -> element.copy(scale = clamped)
                }
            },
        )
    }

    @Synchronized
    fun updateTrackerCounts(id: GuiElementId, entryId: String, delta: Int) {
        currentLayout = currentLayout.copy(
            elements = currentLayout.elements.map { element ->
                if (element.id != id || element !is ItemTrackerElement) return@map element

                val updatedEntries = element.entries.map { entry ->
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
                    DebugLogger.logGuiLayout("Failed to delete GUI layout file at ${file.absolutePath} during reset; will be overwritten on next save")
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
     * Load layout from the configured file if it exists; otherwise, persist the
     * current in-memory layout as the initial default.
     */
    @Synchronized
    fun loadOrInitialize() {
        val file = layoutFile ?: return
        if (!file.exists()) {
            save()
            DebugLogger.logGuiLayout("No existing GUI layout, wrote default layout with ${currentLayout.elements.size} elements to ${file.absolutePath}")
            return
        }

        try {
            val json = file.readText()
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
            currentLayout = loaded.copy(
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
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val json = gson.toJson(currentLayout)
            file.writeText(json)
            DebugLogger.logGuiLayout("Saved GUI layout to ${file.absolutePath} with ${currentLayout.elements.size} elements")
        } catch (e: Exception) {
            // Log failure reason for debugging
            DebugLogger.logGuiLayout(
                "Failed to save GUI layout to ${file.absolutePath}: ${e::class.java.name}: ${e.message}"
            )
        }
    }
}

/**
 * Runtime type adapter factory to preserve the concrete GuiElement subtype
 * information in JSON. Implemented minimally here so the layout file can
 * contain mixed TextBlockElement and ItemTrackerElement instances.
 */
class GuiRuntimeTypeAdapterFactory : com.google.gson.TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: com.google.gson.reflect.TypeToken<T>): com.google.gson.TypeAdapter<T>? {
        // Only wrap the abstract base type GuiElement; concrete subclasses
        // like TextBlockElement should be (de)serialized normally.
        if (type.rawType != GuiElement::class.java) return null

        val elementAdapter = gson.getDelegateAdapter(this, type)
        return object : com.google.gson.TypeAdapter<T>() {
            override fun write(out: com.google.gson.stream.JsonWriter, value: T) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                out.beginObject()
                val element = value as GuiElement
                val kind = when (element) {
                    is TextBlockElement -> "text_block"
                    is ItemTrackerElement -> "item_tracker"
                }
                out.name("type").value(kind)
                out.name("data")
                when (element) {
                    is TextBlockElement -> gson.toJson(element, TextBlockElement::class.java, out)
                    is ItemTrackerElement -> gson.toJson(element, ItemTrackerElement::class.java, out)
                }
                out.endObject()
            }

            @Suppress("UNCHECKED_CAST")
            override fun read(`in`: com.google.gson.stream.JsonReader): T? {
                val json = com.google.gson.JsonParser.parseReader(`in`).asJsonObject
                val typeName = json.get("type")?.asString ?: return null
                val data = json.get("data") ?: return null
                val targetType = when (typeName) {
                    "text_block" -> TextBlockElement::class.java
                    "item_tracker" -> ItemTrackerElement::class.java
                    else -> return null
                }
                // The Gson call returns a concrete GuiElement subtype; we
                // explicitly trust this mapping and suppress the generic cast.
                return gson.fromJson(data, targetType) as T
            }
        }
    }
}
