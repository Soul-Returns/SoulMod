package com.soulreturns.ui.hud.movable

import com.soulreturns.config.cfg
import com.soulreturns.ui.hud.HudElement
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen

/**
 * A [HudElement] the user can drag/scale/attach in the [HudMoveScreen] ("Move GUI").
 *
 * The subclass owns nothing but its content: it reports an intrinsic size ([contentSize]) and draws
 * at the origin ([drawContent]). Where it goes comes from [HudLayout], the one resolver the live HUD
 * ([extract]) and the editor share, so their boxes never drift.
 *
 * Everything is in Soul-logical px (see [com.soulreturns.ui.SoulUi]).
 */
abstract class MovableHud(id: String, val displayName: String, val categoryId: String) : HudElement(id) {
    /** Read the current transform from config (never cache `cfg`). */
    abstract fun readTransform(): HudTransform

    /** Persist a new transform (via `ConfigManager.update`). */
    abstract fun writeTransform(transform: HudTransform)

    /** The transform the Move GUI's Reset (per HUD and per category) restores. */
    abstract fun defaultTransform(): HudTransform

    /**
     * Whether the HUD is on screen right now by any pass; what [HudLayouts.live] answers for a link
     * target. Differs from [isEnabled] (the in-game layer's gate) only for a HUD drawn by another pass,
     * like a tracker panel redrawn over the inventory.
     */
    open fun isDrawn(): Boolean = isEnabled()

    /**
     * Intrinsic content size in Soul-logical px at scale 1 (before [HudTransform.scale]). [preview] is
     * true when drawn in the move screen (draw representative sample content even with no live data).
     */
    abstract fun contentSize(preview: Boolean): Pair<Int, Int>

    /** Draw the content with its top-left at (0, 0); the base has already applied position + scale. */
    abstract fun drawContent(g: GuiGraphicsExtractor, preview: Boolean)

    /**
     * Effective bold state for this HUD's text: the per-HUD [HudTransform.fontMode] override, or the
     * global `general.hudBoldFont` when the override is [HudFontMode.DEFAULT]. Read it in [drawContent].
     */
    fun boldFont(): Boolean =
        when (readTransform().fontMode) {
            HudFontMode.NORMAL -> false
            HudFontMode.BOLD -> true
            else -> cfg.general.hudBoldFont
        }

    /**
     * Effective MSDF family index (0..3, per [com.soulreturns.config.gui.render.MsdfFonts]) for this
     * HUD: the per-HUD [HudTransform.fontChoice] override, or the global `general.fontChoice` when the
     * override is [HudFontChoice.DEFAULT]. Pass it to `SoulText` as both the resolved font weight
     * ([com.soulreturns.config.gui.render.MsdfFonts.regularFor]/`semiboldFor`) and the `fontChoice` arg.
     */
    fun fontChoiceIndex(): Int {
        val c = readTransform().fontChoice
        return if (c <= HudFontChoice.DEFAULT) cfg.general.fontChoice else (c - 1).coerceIn(0, 3)
    }

    /**
     * Effective text-shadow state for this HUD: the per-HUD [HudTransform.textShadowMode] override, or
     * the global `general.hudTextShadow` when the override is [HudTextShadowMode.DEFAULT]. Pass it as
     * the `shadow` arg to `SoulText.draw` in [drawContent].
     */
    fun textShadow(): Boolean =
        when (readTransform().textShadowMode) {
            HudTextShadowMode.ON -> true
            HudTextShadowMode.OFF -> false
            else -> cfg.general.hudTextShadow
        }

    /**
     * Whether this HUD's feature is toggled ON in config — independent of live-data/location gating
     * ([isEnabled] may add "and there's something to show right now"). The [HudMoveScreen] lists only
     * HUDs whose feature is on, so you can position an enabled overlay even with no live data, but a
     * disabled one never appears.
     */
    abstract fun configuredOn(): Boolean

    /** Extra ON/OFF display options shown in the Move GUI inspector (e.g. background, session timer). */
    open fun menuToggles(): List<HudMenuToggle> = emptyList()

    /**
     * Whether the Move GUI inspector shows the per-HUD Font (family) + Weight controls. HUDs that own
     * their font settings elsewhere (e.g. the dungeon map's settings screen) return false.
     */
    open fun supportsFontOverride(): Boolean = true

    /**
     * Whether the Move GUI inspector shows the per-HUD Text Shadow (Default/On/Off) control.
     * HUDs that render text through their own path (e.g. the dungeon map) return false.
     */
    open fun supportsTextShadow(): Boolean = true

    /** The HUD's own settings screen, opened from the inspector's "Settings…" button (null = none). */
    open fun settingsScreen(parent: Screen?): Screen? = null

    /** Top-left of the last draw in Soul px (panels hit-test against it). */
    @Volatile
    protected var drawnX: Float = 0f
        private set

    @Volatile
    protected var drawnY: Float = 0f
        private set

    final override fun extract(g: GuiGraphicsExtractor) {
        val layout = HudLayouts.live()
        val box = layout.box(id) ?: return
        renderAt(g, liveBox(box, layout.screenH), preview = false)
    }

    /** The live HUD's box adjusted for what is on screen right now; the Move GUI always shows [box] as laid out. */
    protected open fun liveBox(box: HudBox, screenH: Float): HudBox = box

    /** Draw the content at [box] (resolved by [HudLayout]) with the HUD's scale. Shared by the live HUD and the editor. */
    fun renderAt(g: GuiGraphicsExtractor, box: HudBox, preview: Boolean) {
        val scale = readTransform().scale
        drawnX = box.x0
        drawnY = box.y0
        g.pose().pushMatrix()
        g.pose().translate(box.x0, box.y0)
        g.pose().scale(scale, scale)
        drawContent(g, preview)
        g.pose().popMatrix()
    }
}

/** A display-option switch in the Move GUI inspector (e.g. "Background"). */
class HudMenuToggle(
    val label: String,
    val get: () -> Boolean,
    val toggle: () -> Unit,
)
