package com.soulreturns.ui.hud.alert

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.dungeons.boss.CrosshairHud
import com.soulreturns.ui.hud.movable.HudBox
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max

/**
 * Draws [Alerts]: a big centered title in the alert's color, the subtitle in a lighter tint below,
 * and a small `+N` after the title when more alerts are waiting. One spot for every feature's alert
 * (`general.alertTransform`, General tab of the Move GUI); default centered a bit above the crosshair.
 */
object AlertHud : MovableHud("alert", "Alerts", "general") {
    private const val TITLE_SIZE = 24f
    private const val SUBTITLE_SIZE = 14f
    private const val MORE_SIZE = 12f
    private const val GAP = 4
    private const val MORE_GAP = 6
    private const val MORE_COLOR = 0xFFC0C0C0.toInt()

    private val sample = Alerts.Alert("sample", "Alert!", "Feature warnings show here", Alerts.RED, 0, 0L, Long.MAX_VALUE)

    override fun isEnabled(): Boolean = Alerts.active().isNotEmpty()

    override fun configuredOn(): Boolean = Alerts.anyConfiguredOn()

    override fun readTransform(): HudTransform = cfg.general.alertTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(general = general.copy(alertTransform = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().general.alertTransform

    private const val TIMERS_CLEARANCE = 8f

    private fun moved(): Boolean {
        val t = readTransform()
        val d = defaultTransform()
        return t.hAnchor != d.hAnchor || t.vAnchor != d.vAnchor || t.offsetX != d.offsetX || t.offsetY != d.offsetY || t.link != d.link
    }

    /**
     * A persistent boss alert that would cover the boss timers sits above their top element, or below their block when
     * there is no room above. A position the player set in the Move GUI always wins.
     */
    override fun liveBox(box: HudBox, screenH: Float): HudBox {
        val alert = Alerts.active().firstOrNull() ?: return box
        if (!alert.avoidBossTimers || moved()) return box
        val extent = CrosshairHud.extent() ?: return box
        val (top, bottom) = extent
        if (box.y1 <= top - TIMERS_CLEARANCE || box.y0 >= bottom + TIMERS_CLEARANCE) return box
        val above = top - TIMERS_CLEARANCE - box.h
        return box.at(box.x0, if (above >= 0f) above else minOf(bottom + TIMERS_CLEARANCE, max(0f, screenH - box.h)))
    }

    /** The alert on top and how many more wait behind it. */
    private fun top(preview: Boolean): Pair<Alerts.Alert, Int>? {
        if (preview) return sample to 0
        val active = Alerts.active()
        return if (active.isEmpty()) null else active[0] to active.size - 1
    }

    private fun width(text: String, size: Float): Int {
        val c = fontChoiceIndex()
        return SoulText.width(text, size, MsdfFonts.semiboldFor(c), fontChoice = c)
    }

    private fun titleLineW(alert: Alerts.Alert, more: Int): Int =
        width(alert.title, TITLE_SIZE) + if (more > 0) MORE_GAP + width("+$more", MORE_SIZE) else 0

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val (alert, more) = top(preview) ?: return 0 to 0
        val sub = alert.subtitle
        val w = max(titleLineW(alert, more), if (sub == null) 0 else width(sub, SUBTITLE_SIZE))
        val h = (TITLE_SIZE * 1.3f).toInt() + if (sub == null) 0 else GAP + (SUBTITLE_SIZE * 1.3f).toInt()
        return w to h
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val (alert, more) = top(preview) ?: return
        val left = if (alert.until == Long.MAX_VALUE) Alerts.FADE_MS else alert.until - System.currentTimeMillis()
        val alpha = (left.toFloat() / Alerts.FADE_MS).coerceIn(0f, 1f)
        if (alpha < 0.05f) return
        val c = fontChoiceIndex()
        val font = MsdfFonts.semiboldFor(c)
        val shadow = textShadow()
        val (w, _) = contentSize(preview)
        val x = (w - titleLineW(alert, more)) / 2
        SoulText.draw(g, alert.title, x, 0, TITLE_SIZE, fade(alert.color, alpha), font = font, shadow = shadow, fontChoice = c)
        if (more > 0) {
            val mx = x + width(alert.title, TITLE_SIZE) + MORE_GAP
            SoulText.draw(g, "+$more", mx, 0, MORE_SIZE, fade(MORE_COLOR, alpha), font = font, shadow = shadow, fontChoice = c)
        }
        val sub = alert.subtitle ?: return
        val y = (TITLE_SIZE * 1.3f).toInt() + GAP
        SoulText.draw(g, sub, (w - width(sub, SUBTITLE_SIZE)) / 2, y, SUBTITLE_SIZE, fade(tint(alert.color), alpha), font = font, shadow = shadow, fontChoice = c)
    }

    /** Halfway to white: the subtitle of a red `FF5555` title is `FFAAAA`. */
    private fun tint(color: Int): Int {
        val r = ((color shr 16 and 0xFF) + 0xFF) / 2
        val gr = ((color shr 8 and 0xFF) + 0xFF) / 2
        val b = ((color and 0xFF) + 0xFF) / 2
        return (color and 0xFF000000.toInt()) or (r shl 16) or (gr shl 8) or b
    }

    private fun fade(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }
}
