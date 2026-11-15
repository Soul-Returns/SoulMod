package com.soulreturns.features.profit.dragon.gui

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceMode
import com.soulreturns.features.profit.dragon.DragonProfitAnnouncer
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.features.profit.dragon.DragonType
import com.soulreturns.features.profit.dragon.KillSource
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.roundToInt

/**
 * `/soul dragons` — the full breakdown, per dragon × kill source, on a screen rather than squeezed
 * into the HUD.
 *
 * ### Why this exists beside the panel
 * The tracker panel answers "how am I doing", in a 280 px column, over drops aggregated across every
 * dragon in view. It cannot answer "which dragon is actually paying" without becoming a spreadsheet
 * bolted to the side of the game. This screen is that spreadsheet, opened deliberately, closed again
 * when you are done: one section per dragon that has anything, split by whether you summoned it, with
 * its drops and what each is worth.
 *
 * `/soul dragons summary` still prints the short version to chat for a glance mid-grind.
 *
 * ### It reads the same numbers as everything else
 * [DragonProfitTracker] for the counts, [DropResolver] for names and rarity, [PriceCache] for value.
 * There is no second source and no cached copy: the screen is a view, so a drop landing while it is
 * open shows up on the next frame.
 *
 * Built on [SoulScreenBase], so it gets the click regions, the SDF/MSDF draw helpers, the clip pair
 * and the dropdown for free — and `DevKeybindHandler` suppresses the global dev keybinds while it is
 * open, because it is a `SoulScreenBase`.
 */
class DragonSessionScreen(private val parent: Screen?) : SoulScreenBase(Component.literal("Soul Dragons")) {
    companion object {
        private const val HEADER_H = 44
        private const val TABS_H = 30
        private const val BODY_PAD = 16
        private const val CLOSE_SIZE = 18
        private const val SCROLL_STEP = 20
        private const val ROW_H = 15
        private const val SECTION_GAP = 12
        private const val CONTROL_H = 18

        /** Opened deferred: a `/soul dragons` typed into chat is dispatched before chat tears down. */
        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(DragonSessionScreen(parent)) }
        }
    }

    /** Marker identifying this screen's one dropdown to the base's token-keyed overlay. */
    private val sourceDropdownToken = Any()

    private var tab: TrackerTab = TrackerTab.Session

    /** null = both partitions. Local to the screen — the panel keeps its own, deliberately. */
    private var sourceFilter: KillSource? = null

    private var bodyScroll = 0f
    private var bodyContentH = 0
    private var bodyRect = intArrayOf(0, 0, 0, 0)

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        regions.clear()

        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()

        g.pose().pushMatrix()
        g.pose().scale(s, s)

        // Viewport-relative with a cap and no minimum — a fixed minimum overflows the composable
        // viewport at high SoulUi.scale.
        val cardW = (uiW * 0.9f).toInt().coerceAtMost(760).coerceAtMost(uiW - 8)
        val cardH = (uiH * 0.88f).toInt().coerceAtMost(640).coerceAtMost(uiH - 8)
        val x0 = (uiW - cardW) / 2
        val y0 = (uiH - cardH) / 2
        val x1 = x0 + cardW
        val y1 = y0 + cardH

        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)

        drawHeader(g, mx, my, x0, y0, x1)
        drawControls(g, mx, my, x0, y0 + HEADER_H, x1)
        drawBody(g, x0, y0 + HEADER_H + TABS_H, x1, y1)

        // Last, so its regions win the reverse hit test — see SoulScreenBase's overlay gotcha.
        drawOpenDropdown(g, uiW, uiH, mx, my)

        g.pose().popMatrix()
    }

    private fun drawHeader(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
    ) {
        roundRect(g, x0, y0, x1, y0 + HEADER_H, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fillGradient(x0, y0 + HEADER_H, x1, y0 + HEADER_H + 6, 0x50000000, 0x00000000)

        drawTextV(g, "Dragons", x0 + 16, y0, y0 + HEADER_H, Theme.TEXT, fTitle, MsdfFonts.menuSemibold)

        val net = DragonProfitTracker.profitFor(
            tab = tab,
            sourceFilter = sourceFilter,
            mode = priceMode(),
            useNpcFloor = cfg.combat.dragons.useNpcPriceIfHigher,
        )
        val kills = DragonType.entries.sumOf { DragonProfitTracker.killsFor(it, tab, sourceFilter) }
        val headline = String.format(
            Locale.ROOT,
            "%s coins over %,d kill%s",
            DragonProfitAnnouncer.formatCoins(net),
            kills,
            if (kills == 1L) "" else "s",
        )
        val closeX0 = x1 - 8 - CLOSE_SIZE
        drawTextV(g, headline, closeX0 - 12 - textWidth(headline, fBody), y0, y0 + HEADER_H, Theme.TEXT_DIM, fBody)

        // `×` is U+00D7, which every MSDF family carries — unlike `✕`, which none of them do.
        val closeY0 = y0 + (HEADER_H - CLOSE_SIZE) / 2
        val hovered = mx in closeX0..(closeX0 + CLOSE_SIZE) && my in closeY0..(closeY0 + CLOSE_SIZE)
        roundRect(
            g, closeX0, closeY0, closeX0 + CLOSE_SIZE, closeY0 + CLOSE_SIZE,
            if (hovered) Theme.CONTROL_HOVER else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL,
        )
        drawTextCentered(g, "×", closeX0 + CLOSE_SIZE / 2, closeY0 + 4, Theme.TEXT, fBody)
        region(closeX0, closeY0, closeX0 + CLOSE_SIZE, closeY0 + CLOSE_SIZE) { onClose() }
    }

    /** The tab pair on the left, the source dropdown on the right, and the price source spelled out. */
    private fun drawControls(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
    ) {
        val top = y0 + (TABS_H - CONTROL_H) / 2
        var x = x0 + BODY_PAD
        for (candidate in listOf(TrackerTab.Session, TrackerTab.Total)) {
            val label = candidate.name
            val w = textWidth(label, fBody) + 20
            val active = candidate == tab
            val hovered = mx in x..(x + w) && my in top..(top + CONTROL_H)
            roundRect(
                g, x, top, x + w, top + CONTROL_H,
                if (active) Theme.ACCENT_DIM else if (hovered) Theme.CONTROL_HOVER else Theme.PANEL_HOVER,
                SoulRenderPipelines.ROUND_SMALL,
            )
            drawTextV(g, label, x + 10, top, top + CONTROL_H, if (active) Theme.TEXT else Theme.TEXT_DIM, fBody)
            val regionX = x
            region(regionX, top, regionX + w, top + CONTROL_H) {
                tab = candidate
                bodyScroll = 0f
            }
            x += w + 6
        }

        // "Instant" alone is cryptic where the old venue name ("Bazaar instant buy") described
        // itself; the noun is what makes it read as a pricing basis rather than a stray adjective.
        val priceLabel = "${priceMode().displayName} prices"
        drawTextV(g, priceLabel, x + 8, top, top + CONTROL_H, Theme.TEXT_FAINT, fCaption)

        val ddW = 110
        val ddX0 = x1 - BODY_PAD - ddW
        val labels = listOf("All kills", KillSource.SUMMONED.displayName, KillSource.LOOTSHARE.displayName)
        val selected = if (sourceFilter == null) 0 else if (sourceFilter == KillSource.SUMMONED) 1 else 2
        drawDropdownButton(g, ddX0, top, ddX0 + ddW, top + CONTROL_H, labels[selected], isDropdownOpen(sourceDropdownToken))
        region(ddX0, top, ddX0 + ddW, top + CONTROL_H) {
            if (isDropdownOpen(sourceDropdownToken)) {
                closeDropdown()
            } else {
                openDropdownAt(sourceDropdownToken, ddX0, top, ddX0 + ddW, top + CONTROL_H, labels, selected) { picked ->
                    sourceFilter = if (picked == 0) null else if (picked == 1) KillSource.SUMMONED else KillSource.LOOTSHARE
                    bodyScroll = 0f
                }
            }
        }
    }

    private fun drawBody(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        bodyRect = intArrayOf(x0, y0, x1, y1)
        beginClip(g, x0, y0, x1, y1)
        var y = y0 + BODY_PAD - bodyScroll.roundToInt()
        val left = x0 + BODY_PAD
        val right = x1 - BODY_PAD
        val startY = y

        val mode = priceMode()
        val floor = cfg.combat.dragons.useNpcPriceIfHigher
        var drewAnything = false

        for (type in DragonType.entries) {
            for (partitionSource in KillSource.entries) {
                if (sourceFilter != null && sourceFilter != partitionSource) continue
                val stats = DragonProfitTracker.scope(tab).partition(partitionSource)
                val kills = stats.killsOf(type)
                val drops = stats.dropsOf(type)
                val eyes = if (partitionSource == KillSource.SUMMONED) stats.eyesOf(type) else 0L
                if (kills == 0L && drops.isEmpty() && eyes == 0L) continue
                drewAnything = true
                y = drawSection(g, left, right, y, type, partitionSource, kills, eyes, drops, mode, floor)
                y += SECTION_GAP
            }
        }

        if (!drewAnything) {
            // Two different answers, and which one it is matters: an empty catalog means nothing can
            // ever resolve, which is not the same as having killed nothing.
            val message = if (DropCatalogClient.isEmpty()) {
                "The drop catalog has not loaded, so nothing can be identified yet."
            } else if (tab == TrackerTab.Session) {
                "No dragons this session."
            } else {
                "No dragons recorded on this profile."
            }
            drawTextCentered(g, message, (x0 + x1) / 2, y0 + (y1 - y0) / 2 - 4, Theme.TEXT_DIM, fBody)
        }

        endClip(g)
        bodyContentH = y - startY + BODY_PAD
        bodyScroll = clampScroll(bodyScroll, bodyContentH, y1 - y0)
    }

    /** One dragon × source block: a heading with kills and net, then a row per drop. Returns the new y. */
    private fun drawSection(
        g: GuiGraphicsExtractor,
        left: Int,
        right: Int,
        top: Int,
        type: DragonType,
        source: KillSource,
        kills: Long,
        eyes: Long,
        drops: Map<String, Long>,
        mode: PriceMode,
        useNpcFloor: Boolean,
    ): Int {
        var y = top
        val gross = drops.entries.sumOf { (itemId, amount) ->
            PriceCache.sellValue(DropResolver.priceLookupId(itemId), mode, useNpcFloor) * amount
        }
        val eyeCost = eyes * PriceCache.buyCost(DragonProfitTracker.SUMMONING_EYE_ID, mode)
        val net = gross - eyeCost

        drawText(g, "${type.displayName} Dragon", left, y, type.color, fHeading, MsdfFonts.menuSemibold)
        drawText(g, " · ${source.displayName}", left + textWidth("${type.displayName} Dragon", fHeading, MsdfFonts.menuSemibold), y + 2, Theme.TEXT_DIM, fBody)
        val netText = DragonProfitAnnouncer.formatCoins(net)
        drawText(g, netText, right - textWidth(netText, fHeading, MsdfFonts.menuSemibold), y, Theme.ACCENT, fHeading, MsdfFonts.menuSemibold)
        y += 18

        val subtitle = if (eyes > 0L && eyeCost > 0L) {
            String.format(
                Locale.ROOT,
                "%,d kill%s · %,d eye%s (%s) · gross %s",
                kills, if (kills == 1L) "" else "s",
                eyes, if (eyes == 1L) "" else "s",
                DragonProfitAnnouncer.formatCoins(eyeCost),
                DragonProfitAnnouncer.formatCoins(gross),
            )
        } else {
            String.format(Locale.ROOT, "%,d kill%s", kills, if (kills == 1L) "" else "s")
        }
        drawText(g, subtitle, left, y, Theme.TEXT_DIM, fCaption)
        y += 14

        if (drops.isEmpty()) {
            drawText(g, "no loot recorded", left + 8, y, Theme.TEXT_FAINT, fCaption)
            return y + ROW_H
        }

        // Most valuable first — the question this screen answers is "what is paying".
        val rows = drops.entries.map { (itemId, amount) ->
            val unit = PriceCache.sellValue(DropResolver.priceLookupId(itemId), mode, useNpcFloor)
            Triple(itemId, amount, unit * amount)
        }.sortedByDescending { it.third }

        for ((itemId, amount, value) in rows) {
            val name = DropResolver.displayName(type.sourceId, itemId)
            val rarity = DropResolver.rarity(type.sourceId, itemId)
            drawText(g, name, left + 8, y, rarity.color, fBody)
            val countText = String.format(Locale.ROOT, "%,d", amount)
            // A price of 0 means "not cached", not "worthless" — say so rather than printing 0 coins.
            val valueText = if (value > 0L) DragonProfitAnnouncer.formatCoins(value) else "-"
            drawText(g, valueText, right - textWidth(valueText, fBody), y, Theme.ACCENT, fBody)
            drawText(g, countText, right - 72 - textWidth(countText, fBody), y, Theme.TEXT_DIM, fBody)
            y += ROW_H
        }
        return y
    }

    private fun priceMode(): PriceMode = DragonProfitHudSettings.priceMode

    override fun keyPressed(event: KeyEvent): Boolean {
        // One Escape per thing to leave: the dropdown first, then the screen.
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && dropdownOpen) {
            closeDropdown()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val s = SoulUi.factor()
        val mx = (mouseX / s).roundToInt()
        val my = (mouseY / s).roundToInt()
        if (within(bodyRect, mx, my)) {
            bodyScroll = clampScroll(bodyScroll - scrollY.toFloat() * SCROLL_STEP, bodyContentH, bodyRect[3] - bodyRect[1])
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        val target = parent
        if (target != null) minecraft?.setScreenAndShow(target) else super.onClose()
    }
}
