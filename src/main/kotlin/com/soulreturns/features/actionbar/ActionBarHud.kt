package com.soulreturns.features.actionbar

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.SoulRichText
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.movable.HudAnchor
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.VanillaHudLayout
import com.soulreturns.ui.hud.movable.MovableHud
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import kotlin.math.abs

/**
 * The action bar (the line above the hotbar; health / defense / mana on SkyBlock) drawn with Soul's
 * HUD font through [SoulRichText], replacing vanilla's `OVERLAY_MESSAGE` HUD element while on.
 *
 * The text, its fade and the record "now playing" rainbow are read from vanilla's own state on
 * `Hud` (access-widened), so every other mod that edits or cancels the action bar still works: we
 * draw whatever ended up there. Glyphs no MSDF family has (Hypixel's stat icons, resource-pack
 * glyphs) are drawn with the Minecraft font in place.
 *
 * Position: a [MovableHud] centered at the bottom with no link, at vanilla's own action bar spot (its
 * offset above the bottom at the current GUI and UI scale, [VanillaHudLayout.actionBarOffsetY]) until
 * the player moves it; a bigger scale grows it upward.
 */
object ActionBarHud : MovableHud("action_bar", "Action Bar", "general") {
    private const val SIZE = 8f
    private const val LINE_HEIGHT = 9
    private const val PAD = 1

    private val SAMPLE: Component =
        Component.literal("§c6,267/5,892❤     §a582❈ Defense     §b1,498/1,498✎ Mana")

    // Spans are rebuilt only when the message object changes (vanilla replaces it per packet).
    private var cachedMessage: Component? = null
    private var cachedSpans: List<SoulRichText.Span> = emptyList()

    fun register() {
        HudElementRegistry.replaceElement(VanillaHudElements.OVERLAY_MESSAGE) { vanilla ->
            HudElement { g, tickCounter ->
                if (!cfg.general.soulActionBar) vanilla.extractRenderState(g, tickCounter)
            }
        }
    }

    override fun isEnabled(): Boolean = cfg.general.soulActionBar && message() != null && alpha() > 0

    override fun configuredOn(): Boolean = cfg.general.soulActionBar

    override fun settingsScreen(parent: Screen?): Screen =
        SoulConfigScreen.forFeature(parent, "actionbar")

    override fun readTransform(): HudTransform {
        val g = cfg.general
        return if (g.actionBarCustomPosition) g.actionBarTransform else atVanilla(g.actionBarTransform)
    }

    /** Moving it to any other place keeps the position; style edits (scale, font) leave it on vanilla's spot. */
    override fun writeTransform(transform: HudTransform) {
        val custom = !samePlace(transform, atVanilla(transform))
        ConfigManager.update { copy(general = general.copy(actionBarTransform = transform, actionBarCustomPosition = custom)) }
    }

    override fun defaultTransform(): HudTransform = atVanilla(SoulConfig().general.actionBarTransform)

    /**
     * [style] placed where vanilla draws its action bar at the current GUI and UI scale: centered, bottom
     * edge on the bottom of vanilla's line (our text sits [PAD] inside the box), so a larger scale grows it up.
     */
    private fun atVanilla(style: HudTransform): HudTransform =
        style.copy(
            hAnchor = HudAnchor.CENTER,
            vAnchor = HudAnchor.END,
            offsetX = 0f,
            offsetY = VanillaHudLayout.actionBarOffsetY(SoulUi.factor()) + PAD * style.scale,
            link = null,
        )

    // Stored offsets are rounded to half px by the Move GUI.
    private fun samePlace(a: HudTransform, b: HudTransform): Boolean =
        a.link == null && a.hAnchor == b.hAnchor && a.vAnchor == b.vAnchor &&
            abs(a.offsetX - b.offsetX) <= 0.25f && abs(a.offsetY - b.offsetY) <= 0.25f


    private fun message(): Component? {
        val hud = Minecraft.getInstance().gui.hud
        return hud.overlayMessageString?.takeIf { hud.overlayMessageTime > 0 }
    }

    /** Vanilla's fade: full for most of the message's life, out over its last 20 ticks. */
    private fun alpha(): Int {
        val mc = Minecraft.getInstance()
        val time = mc.gui.hud.overlayMessageTime - mc.deltaTracker.getGameTimeDeltaPartialTick(false)
        return (time * 255f / 20f).toInt().coerceAtMost(255)
    }

    private fun spans(preview: Boolean): List<SoulRichText.Span> {
        val message = message() ?: if (preview) SAMPLE else return emptyList()
        if (message !== cachedMessage) {
            cachedSpans = SoulRichText.spans(message)
            cachedMessage = message
        }
        return cachedSpans
    }

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val width = SoulRichText.width(spans(preview), SIZE, fontChoiceIndex(), boldFont())
        return (width + PAD * 2).coerceAtLeast(40) to LINE_HEIGHT + PAD * 2
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val spans = spans(preview)
        if (spans.isEmpty()) return
        val live = message() != null
        val alpha = if (live) alpha().coerceAtLeast(0) else 255
        if (alpha <= 0) return
        val (w, h) = contentSize(preview)
        val textW = w - PAD * 2
        val x = (w - textW) / 2

        // Vanilla's accessibility "text background opacity" backdrop.
        val backdrop = Minecraft.getInstance().options.getBackgroundColor(0f)
        val backdropAlpha = (backdrop ushr 24) * alpha / 255
        if (backdropAlpha > 0) g.fill(x - 2, PAD - 2, x + textW + 2, h - PAD + 2, (backdropAlpha shl 24) or (backdrop and 0xFFFFFF))

        val hud = Minecraft.getInstance().gui.hud
        val color =
            if (live && hud.animateOverlayMessageColor) {
                val time = hud.overlayMessageTime - Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
                Mth.hsvToArgb(time / 50f, 0.7f, 0.6f, 255)
            } else {
                0xFFFFFFFF.toInt()
            }
        SoulRichText.draw(g, spans, x, PAD, SIZE, color, alpha, fontChoiceIndex(), textShadow(), boldFont())
    }
}
