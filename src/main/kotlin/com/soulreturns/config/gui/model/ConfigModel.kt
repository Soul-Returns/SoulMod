package com.soulreturns.config.gui.model

import com.soulreturns.config.ChromaColor
import com.soulreturns.sound.SoundRef

/**
 * Declarative model for the config screen: Category → Subcategory → Section → Option.
 *
 * Replaces the legacy owo-config reflection (`CategoriesCollector` / `ConfigSections`). Because the
 * new config is plain `@Serializable` data classes, the UI tree is hand-declared in
 * [com.soulreturns.config.gui.ConfigRegistry]. Each option binds get/set lambdas to `cfg` +
 * `ConfigManager.update` and carries its default for the per-option reset chip.
 */
sealed interface ConfigOption {
    val label: String
    val description: String?
    val isModified: Boolean

    /** Rows for which this returns false are hidden (dependency-gated visibility). Evaluated live. */
    val visible: () -> Boolean

    /**
     * Rows for which this returns true are shown but greyed out and non-interactive (dependency-gated
     * *enablement*, distinct from [visible] which hides). Evaluated live. E.g. a manual-scale slider
     * disabled while an auto-scale toggle is on.
     */
    val disabled: () -> Boolean

    fun reset()
}

class ToggleOption(
    override val label: String,
    override val description: String? = null,
    val get: () -> Boolean,
    val set: (Boolean) -> Unit,
    val default: Boolean,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)

    fun toggle() = set(!get())
}

class TextOption(
    override val label: String,
    override val description: String? = null,
    val get: () -> String,
    val set: (String) -> Unit,
    val default: String,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)
}

class SliderOption(
    override val label: String,
    override val description: String? = null,
    val get: () -> Double,
    val set: (Double) -> Unit,
    val default: Double,
    val min: Double,
    val max: Double,
    val step: Double = 0.1,
    /**
     * When false, edits go to a pending value and only take effect when the user clicks "Apply".
     * Use for sliders whose live change would disrupt interaction (e.g. UI scale rescales the menu).
     */
    val applyImmediately: Boolean = true,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)
}

/**
 * A multiple-choice option cycled by clicking (shows the current label). Backed by an index into
 * [labels]; the reset chip restores [default].
 */
class CycleOption(
    override val label: String,
    override val description: String? = null,
    val get: () -> Int,
    val set: (Int) -> Unit,
    val labels: List<String>,
    val default: Int,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)

    fun cycle() = set((get() + 1) % labels.size.coerceAtLeast(1))

    val display: String get() = labels.getOrElse(get()) { labels.firstOrNull() ?: "" }
}

/**
 * A row that acts as a button (e.g. "opens the dungeon-map settings screen"). Carries no value, so
 * it never shows a reset chip. [actionId] is matched by the hosting screen to decide what to do.
 */
class ActionOption(
    override val label: String,
    override val description: String? = null,
    val buttonLabel: String,
    val actionId: String = "",
    /** If set, clicking opens this URL in the browser instead of routing through `onAction`. */
    val url: String? = null,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = false

    override fun reset() {}
}

/**
 * A keybind-valued option: the stored String is an `InputConstants` key translation id
 * (`key.keyboard.f6`, `key.mouse.4`, …), blank = unbound. Rendered as a button that enters capture
 * mode — the next key press (or mouse click) becomes the binding; Escape clears it.
 */
class KeybindOption(
    override val label: String,
    override val description: String? = null,
    val get: () -> String,
    val set: (String) -> Unit,
    val default: String = "",
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)
}

/**
 * A sound-valued option ([SoundRef], null = no sound). Rendered as a field with a play button, the
 * sound's name and a caret; clicking it opens the sound picker window over the settings. [purpose]
 * names the feature in the picker ("Bobbin Time"); [allowNone] offers "None".
 */
class SoundOption(
    override val label: String,
    override val description: String? = null,
    val purpose: String,
    val get: () -> SoundRef?,
    val set: (SoundRef?) -> Unit,
    val default: SoundRef? = null,
    val allowNone: Boolean = true,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)
}

/**
 * A color-valued option ([ChromaColor] = base ARGB + optional chroma). Rendered as a swatch that
 * opens the color picker. [hasAlpha] enables the picker's opacity bar (off = fully opaque).
 */
class ColorOption(
    override val label: String,
    override val description: String? = null,
    val get: () -> ChromaColor,
    val set: (ChromaColor) -> Unit,
    val default: ChromaColor,
    val hasAlpha: Boolean = false,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = get() != default

    override fun reset() = set(default)
}

/** One checkbox inside a [CheckboxGroupOption]: a short label bound to a boolean get/set + default. */
class CheckboxItem(
    val label: String,
    val get: () -> Boolean,
    val set: (Boolean) -> Unit,
    val default: Boolean,
) {
    val isModified: Boolean get() = get() != default

    fun toggle() = set(!get())

    fun reset() = set(default)
}

/**
 * A single row holding a set of independent checkboxes ([items]) that flow horizontally and wrap.
 * Use to toggle several related flags together (e.g. which elements of a HUD show). The row's reset
 * chip restores every checkbox to its default; the row is "modified" if any checkbox is. Its label
 * is not drawn inline — the section title names the group.
 */
class CheckboxGroupOption(
    override val label: String,
    override val description: String? = null,
    val items: List<CheckboxItem>,
    override val visible: () -> Boolean = { true },
    override val disabled: () -> Boolean = { false },
) : ConfigOption {
    override val isModified: Boolean get() = items.any { it.isModified }

    override fun reset() = items.forEach { it.reset() }
}

/** A titled group of options. [subtitle] is the optional grey line under the title. */
class ConfigSection(
    val title: String,
    val subtitle: String? = null,
    val options: List<ConfigOption>,
)

class ConfigSubcategory(
    val id: String,
    val title: String,
    val sections: List<ConfigSection>,
)

class ConfigCategory(
    val id: String,
    val title: String,
    val subcategories: List<ConfigSubcategory>,
)
