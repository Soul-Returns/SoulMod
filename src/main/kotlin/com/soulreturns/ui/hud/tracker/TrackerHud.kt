package com.soulreturns.ui.hud.tracker

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.hud.movable.MovableHud
import com.soulreturns.config.cfg
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import org.joml.Matrix3x2f
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The tracker panel — header, tab strip, scrollable row list, summary chips, and a footer of
 * dropdowns and buttons that only appears while your own inventory is open.
 *
 * This is a faithful rebuild of the legacy mod's `ui/hud/tracker/TrackerHud` composable on the 26.2
 * SDF/MSDF render layer. Every metric, colour and interaction rule below is the legacy one: the
 * 12 px panel padding and 6 px column gap, the 4 px header gap, the 27 px control height
 * (11 px body text plus 8 px of padding either side), the 4 px control radius, the 140 px list
 * viewport, the 32 px numeric cells with their 1×9 px dividers, the caret triangle, the popup
 * geometry and its dismiss scrim. What changed is only how the pixels get on screen — NanoVG and a
 * Compose-style layout engine became direct arithmetic against `SoulText` and the SDF rounded-rect
 * pipeline, because that layout engine does not exist in this baseline and rebuilding it to render
 * one panel would be the tail wagging the dog.
 *
 * ### It is generic on purpose
 * A tracker supplies a [TrackerSpec] and a [TrackerSettings]; this class owns all of the layout,
 * hit-testing, popups and the at-least-one-visible-column invariant. Fishing is the first consumer
 * and the dragon-profit tracker is the second — a "general" tracker declares count columns, a
 * "profit" tracker adds an amount and a value column, and both render through this.
 *
 * ### Shown on the HUD and in the player's own inventory only
 * Over `InventoryScreen` the panel is redrawn by [TrackerHudHost] so it stays crisp instead of
 * sitting behind the inventory's dim, with the tab strip and footer for configuration. Any other
 * container screen (chests, Hypixel menus, storage overlays) hides it entirely.
 *
 * ### Two passes, and why the spec must be side-effect free
 * [contentSize] lays the panel out with drawing suppressed to measure it; [drawContent] lays it out
 * again for real. So [TrackerSpec.rowsProvider] and every format lambda run more than once per
 * frame and must be pure — the same rule the Profile Viewer's sections
 * ([com.soulreturns.features.profileviewer.gui.ProfileSection.build]) carry, for the same reason.
 */
abstract class TrackerHud<T : Any>(
    id: String,
    displayName: String,
    categoryId: String,
) : MovableHud(id, displayName, categoryId) {
    abstract val spec: TrackerSpec<T>

    abstract val settings: TrackerSettings

    /** Whether the panel draws its background card. Trackers wire this to their own config toggle. */
    abstract fun drawBackground(): Boolean

    /** Background fill, when [drawBackground]. */
    abstract fun backgroundColor(): Int

    companion object {
        /** Height of the row viewport. The panel's own height follows its content. */
        const val LIST_HEIGHT: Float = 140f

        // ---- legacy SoulTheme.dimens / typography, transcribed ----
        private const val PAD = 12 // paddingMedium — the Surface inset
        private const val GAP = 6 // root Column gap
        private const val HEADER_GAP = 4
        private const val ROW_GAP = 2
        private const val CHIP_GAP = 8

        // The legacy 13/11/10 em sizes in SoulText's vanilla-relative scale, so the default family
        // keeps its size inside the transcribed metrics (same conversion as SoulScreenBase).
        private const val SIZE_HEADING = 13f * MsdfFont.LEGACY_EM
        private const val SIZE_BODY = 11f * MsdfFont.LEGACY_EM
        private const val SIZE_CAPTION = 10f * MsdfFont.LEGACY_EM

        /** Line advances, the legacy 11 / 13 px the transcribed metrics were laid out on. */
        private const val LINE_BODY = 11
        private const val LINE_HEADING = 13

        /** Grace before collapsing, so brushing past the edge on the way to a control keeps it open. */
        private const val COLLAPSE_DELAY_MS = 300L

        /** 11 px of text plus 8 px of padding either side — Button, Tabs and a trigger all match. */
        private const val CONTROL_H = 27
        private const val CONTROL_PAD_H = 10
        private const val TAB_GAP = 4

        private const val CARET_W = 7
        private const val CARET_H = 4
        private const val CARET_GAP = 6

        private const val POPUP_GAP = 4
        private const val POPUP_PAD_H = 6
        private const val POPUP_PAD_V = 6
        private const val OPTION_ROW_H = 18
        private const val OPTION_LABEL_GAP = 6
        private const val CHECKBOX = 12
        private const val INDICATOR_W = 3
        private const val FOOTER_POPUP_MAX_H = 156

        private const val COL_DIVIDER_H = 9
        private const val COL_DIVIDER_TOP = 2

        private const val SCROLL_STEP = 10f

        /** Row icons: a 16 px item scaled to sit inside the 11 px body line. */
        private const val ICON_SIZE = 10
        private const val ICON_GAP = 3

        /** Depth boosts, so an open popup wins the hit test and its scrim absorbs everything else. */
        private const val DEPTH_SCRIM = 100
        private const val DEPTH_POPUP = 200
    }

    // ------------------------------------------------------------------ per-frame state

    private class Region(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val depth: Int,
        val onClick: (() -> Unit)?,
        val onScroll: ((Double) -> Unit)?,
    ) {
        fun contains(
            x: Float,
            y: Float,
        ): Boolean = x >= x0 && x < x1 && y >= y0 && y < y1
    }

    /** Regions from the last drawn frame, in panel-local coordinates. Clicks hit-test against these. */
    @Volatile private var liveRegions: List<Region> = emptyList()

    /** Where the panel was drawn last frame, so a screen-space click can be mapped back into it. */
    @Volatile private var lastBaseX = 0f

    @Volatile private var lastBaseY = 0f

    @Volatile private var lastScale = 1f

    /** Last drawn size in panel-local px; the hover test for expanding uses last frame's bounds. */
    @Volatile private var lastW = 0

    @Volatile private var lastH = 0

    /** Whether the controls are shown in the inventory (always true unless `general.trackerControlsOnHover`). */
    @Volatile private var expanded = false

    /** When the cursor left the expanded panel, 0 while it is inside; collapsing waits [COLLAPSE_DELAY_MS]. */
    private var leftAt = 0L

    /** Per-popup scroll offsets, keyed by which dropdown owns the popup. */
    private val popupScroll = HashMap<String, Float>()

    // ------------------------------------------------------------------ MovableHud contract

    /**
     * Measure by laying the panel out with drawing suppressed.
     *
     * Deliberately **not** memoized. A cache here would have to be invalidated by every input the
     * layout reads — the row count, the tab, the column set, whether the inventory is open — and the
     * Move GUI calls this several times per frame for hit-testing, interleaved with the live HUD's
     * own calls. A stale answer there moves the panel out from under the cursor mid-drag. The legacy
     * panel measured every frame as well; if this ever shows up in a profile, the fix is a real frame
     * counter, not a sticky field.
     */
    final override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val ctx = Ctx(null, Float.NaN, Float.NaN, inventory = interactive(preview), preview = preview)
        layout(ctx)
        return spec.width to ctx.y
    }

    final override fun drawContent(
        g: GuiGraphicsExtractor,
        preview: Boolean,
    ) {
        val transform = readTransform()
        val (cw, ch) = contentSize(preview)
        lastBaseX = drawnX
        lastBaseY = drawnY
        lastScale = transform.scale.coerceAtLeast(0.0001f)
        if (!preview) {
            lastW = cw
            lastH = ch
        }

        val mx = (TrackerHudHost.mouseX - lastBaseX) / lastScale
        val my = (TrackerHudHost.mouseY - lastBaseY) / lastScale
        val ctx = Ctx(g, mx, my, inventory = interactive(preview), preview = preview)

        if (drawBackground()) {
            roundRect(g, 0, 0, cw, ch, backgroundColor(), SoulRenderPipelines.ROUND_MEDIUM)
        }
        layout(ctx)
        // Popups paint last: submission order is draw order, so anything queued here lands on top of
        // the footer rows it opened from.
        ctx.overlay?.invoke()
        liveRegions = ctx.regions
    }

    /**
     * True while the tab strip and footer should be shown. Never in the Move GUI preview: the
     * footer's dropdowns would be laid out but unclickable there, which reads as broken. With
     * `general.trackerControlsOnHover` only while the cursor is over the panel (see [updateHover]).
     */
    private fun interactive(preview: Boolean): Boolean = !preview && TrackerHudHost.inventoryOpen && expanded

    /**
     * Once per overlay frame, before drawing: expand while the cursor is inside last frame's bounds
     * or a popup is open; collapse after it has been outside for [COLLAPSE_DELAY_MS]. The expanded
     * panel is larger, so leaving is tested against the expanded bounds: no flicker at the edge.
     */
    internal fun updateHover(
        screenX: Float,
        screenY: Float,
    ) {
        if (!cfg.general.trackerControlsOnHover) {
            expanded = true
            leftAt = 0L
            return
        }
        val popupOpen =
            settings.filterDropdownOpen || settings.sortDropdownOpen || settings.columnDropdownOpen || settings.openSelectId != null
        val inside =
            !screenX.isNaN() && lastW > 0 &&
                screenX >= lastBaseX && screenX < lastBaseX + lastW * lastScale &&
                screenY >= lastBaseY && screenY < lastBaseY + lastH * lastScale
        if (inside || popupOpen) {
            expanded = true
            leftAt = 0L
            return
        }
        if (!expanded) return
        val now = System.currentTimeMillis()
        if (leftAt == 0L) {
            leftAt = now
        } else if (now - leftAt >= COLLAPSE_DELAY_MS) {
            expanded = false
            leftAt = 0L
        }
    }

    /** The inventory closed: start collapsed next time (hover mode). */
    internal fun collapse() {
        expanded = !cfg.general.trackerControlsOnHover
        leftAt = 0L
    }

    /**
     * Whether the panel draws at all this frame.
     *
     * Beyond the spec's own gate, this suppresses the **in-game HUD layer** while a container screen
     * is open, because [TrackerHudHost] redraws the panel over that screen's background. Drawing both
     * would cost a second full layout for a copy nobody can read through the inventory's dim — and
     * would leave `liveRegions` describing whichever pass happened to run last.
     */
    final override fun isEnabled(): Boolean {
        if (!spec.isVisible()) return false
        if (!TrackerHudHost.drawingOverlay && Minecraft.getInstance().gui.screen() is AbstractContainerScreen<*>) return false
        return true
    }

    /** Drawn on the in-game HUD, or over [InventoryScreen] by [TrackerHudHost]; hidden under every other container screen. */
    final override fun isDrawn(): Boolean {
        if (!spec.isVisible()) return false
        val screen = Minecraft.getInstance().gui.screen()
        return screen !is AbstractContainerScreen<*> || screen is InventoryScreen
    }

    // ------------------------------------------------------------------ input, routed by TrackerHudHost

    /**
     * Whether this panel should be offered mouse input.
     *
     * Deliberately **not** [isEnabled]: that one returns false while a container screen is open and
     * [TrackerHudHost] is not mid-draw, which is exactly the state every click arrives in. Gating
     * dispatch on it meant the panel drew perfectly and ignored every click.
     */
    internal fun acceptsInput(): Boolean = spec.isVisible()

    /** @return true when the click landed on this panel and was consumed. */
    internal fun handleClick(
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val hit = pick(screenX, screenY) { it.onClick != null } ?: return false
        hit.onClick?.invoke()
        return true
    }

    /** @return true when the wheel was consumed by this panel. */
    internal fun handleScroll(
        screenX: Float,
        screenY: Float,
        delta: Double,
    ): Boolean {
        val hit = pick(screenX, screenY) { it.onScroll != null } ?: return false
        hit.onScroll?.invoke(delta)
        return true
    }

    /** Close every popup — the panel stopped being interactive, so a stuck-open popup is a ghost. */
    internal fun closePopups() = openOnly(Popup.NONE)

    private object Popup {
        const val NONE = 0
        const val FILTER = 1
        const val SORT = 2
        const val COLUMNS = 3
        const val SELECT = 4
    }

    /**
     * At most one popup open at a time. The panel keeps a single deferred overlay slot, and two open
     * popups would mean one of them silently never paints — so opening one closes the others rather
     * than leaving a trigger that looks expanded above nothing.
     *
     * That includes two *selectors* closing each other, which is why [selectId] is threaded through
     * rather than a boolean: a spec may declare several and they share the one slot.
     */
    private fun openOnly(
        which: Int,
        selectId: String? = null,
    ) {
        settings.filterDropdownOpen = which == Popup.FILTER
        settings.sortDropdownOpen = which == Popup.SORT
        settings.columnDropdownOpen = which == Popup.COLUMNS
        settings.openSelectId = if (which == Popup.SELECT) selectId else null
    }

    /**
     * Deepest region under the cursor, preferring the last recorded on a tie — later draw means on
     * top, and a popup's boosted depth means it beats whatever it covers.
     */
    private fun pick(
        screenX: Float,
        screenY: Float,
        accept: (Region) -> Boolean,
    ): Region? {
        val x = (screenX - lastBaseX) / lastScale
        val y = (screenY - lastBaseY) / lastScale
        var best: Region? = null
        for (region in liveRegions) {
            if (!accept(region) || !region.contains(x, y)) continue
            if (best == null || region.depth >= best.depth) best = region
        }
        return best
    }

    // ------------------------------------------------------------------ layout

    private inner class Ctx(
        val g: GuiGraphicsExtractor?,
        val mx: Float,
        val my: Float,
        val inventory: Boolean,
        val preview: Boolean,
    ) {
        /** The cursor. Starts and ends as the panel's total height. */
        var y = PAD

        val regions = ArrayList<Region>()
        var overlay: (() -> Unit)? = null

        /**
         * Active while a popup is painting. Our SDF/MSDF render states do **not** capture the GL
         * scissor the way `g.fill` does, so a row half-out of the popup would draw its label right
         * across the panel without this.
         */
        var scissor: ScreenRectangle? = null

        val contentW: Int get() = spec.width - PAD * 2
        val left: Int get() = PAD
        val right: Int get() = spec.width - PAD

        val dry: Boolean get() = g == null

        fun hovered(
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
        ): Boolean = !mx.isNaN() && mx >= x0 && mx < x1 && my >= y0 && my < y1

        fun region(
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
            depth: Int = 0,
            onClick: (() -> Unit)? = null,
            onScroll: ((Double) -> Unit)? = null,
        ) {
            if (dry) return
            regions.add(Region(x0, y0, x1, y1, depth, onClick, onScroll))
        }
    }

    private fun layout(ctx: Ctx) {
        header(ctx)
        if (!spec.hideList()) {
            divider(ctx)
            val overrideList = spec.shouldOverrideList() && spec.listOverride != null
            if (overrideList) {
                segmentBlock(ctx, spec.listOverride!!.invoke(), spec.listHeight.roundToInt())
            } else {
                rowList(ctx)
                val chips = spec.chipOverride?.invoke()
                if (chips != null) {
                    ctx.y += GAP
                    segments(ctx, chips, ctx.left, SIZE_BODY, monoFont())
                    ctx.y += LINE_BODY
                } else if (anyChipVisible()) {
                    ctx.y += GAP
                    chipsLine(ctx)
                }
            }
        }
        if (ctx.inventory) {
            divider(ctx)
            footer(ctx)
        }
        ctx.y += PAD
    }

    // ------------------------------------------------------------------ header

    private fun header(ctx: Ctx) {
        val g = ctx.g
        val title = spec.title
        if (g != null) {
            SoulText.drawCentered(
                g, title, spec.width / 2, ctx.y, SIZE_HEADING, Theme.TEXT,
                font = MsdfFonts.semiboldFor(fontChoiceIndex()), shadow = textShadow(), fontChoice = fontChoiceIndex(),
            )
        }
        ctx.y += LINE_HEADING + HEADER_GAP

        for (line in spec.headerExtra?.invoke().orEmpty()) {
            if (line.isEmpty()) continue
            val width = segmentsWidth(line, SIZE_BODY, bodyFont())
            segments(ctx, line, (spec.width - width) / 2, SIZE_BODY, bodyFont())
            ctx.y += LINE_BODY + HEADER_GAP
        }

        // Timer flush left, tabs flush right — the legacy SpaceBetween row. Its height is the tab
        // strip's whenever the strip is shown, so the two halves stay vertically centred on each other.
        val rowH = if (ctx.inventory) CONTROL_H else LINE_BODY
        val cy = ctx.y + rowH / 2

        val timer = spec.timerLine?.invoke()
        if (timer != null && ctx.g != null) {
            var x = ctx.left
            SoulText.drawVCentered(
                ctx.g, timer.text, x, cy, SIZE_BODY, Theme.TEXT,
                font = monoFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
            )
            if (timer.paused) {
                x += SoulText.width(timer.text, SIZE_BODY, monoFont(), fontChoice = fontChoiceIndex()) + 6
                SoulText.drawVCentered(
                    ctx.g, "(Paused)", x, cy, SIZE_BODY, COLOR_PAUSED,
                    font = bodyFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
                )
            }
        }

        if (ctx.inventory) {
            tabs(ctx, cy)
        } else if (ctx.g != null) {
            val label = settings.tab.name
            val w = SoulText.width(label, SIZE_BODY, bodyFont(), fontChoice = fontChoiceIndex())
            SoulText.drawVCentered(
                ctx.g, label, ctx.right - w, cy, SIZE_BODY, Theme.TEXT_DIM,
                font = bodyFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
            )
        }
        ctx.y += rowH
    }

    /**
     * The segmented scope strip. Unselected tabs draw **no fill** — that is what makes the strip read
     * as navigation rather than a row of buttons, and it is the one thing that visually separates
     * Tabs from Button despite their identical geometry.
     */
    private fun tabs(
        ctx: Ctx,
        cy: Int,
    ) {
        val available = spec.tabs
        if (available.isEmpty()) return
        // Snap to a valid tab, so a tracker that shrinks its tab set cannot strand the user on one
        // that is no longer drawn. Guarded by the setter, because layout runs twice per frame and an
        // unguarded write here would be a config save on every one of them.
        if (settings.tab !in available) {
            settings.tab = available.first()
            settings.markDirty()
        }
        val widths = available.map {
            SoulText.width(it.name, SIZE_BODY, bodyFont(), fontChoice = fontChoiceIndex()) + CONTROL_PAD_H * 2
        }
        val total = widths.sum() + TAB_GAP * (available.size - 1).coerceAtLeast(0)
        var x = ctx.right - total
        val y0 = cy - CONTROL_H / 2
        val y1 = y0 + CONTROL_H

        for ((index, tab) in available.withIndex()) {
            val w = widths[index]
            val selected = tab == settings.tab
            val hovered = ctx.hovered(x, y0, x + w, y1)
            val bg = if (selected && hovered) {
                Theme.ACCENT_DIM
            } else if (selected) {
                Theme.ACCENT
            } else if (hovered) {
                Theme.PANEL_HOVER
            } else {
                0
            }
            if (ctx.g != null) {
                if (bg != 0) roundRect(ctx.g, x, y0, x + w, y1, bg, SoulRenderPipelines.ROUND_SMALL)
                val color = if (selected) 0xFFFFFFFF.toInt() else if (hovered) Theme.TEXT else Theme.TEXT_DIM
                SoulText.drawCenteredOn(
                    ctx.g, tab.name, x + w / 2, cy, SIZE_BODY, color,
                    font = bodyFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
                )
            }
            val target = tab
            ctx.region(x, y0, x + w, y1, onClick = {
                if (settings.tab != target) {
                    settings.tab = target
                    settings.scrollOffset = 0f
                    settings.markDirty()
                }
            })
            x += w + TAB_GAP
        }
    }

    // ------------------------------------------------------------------ rows

    private fun rowList(ctx: Ctx) {
        val rows = buildRows()
        val rowH = LINE_BODY
        val contentH = if (rows.isEmpty()) rowH else rows.size * rowH + (rows.size - 1) * ROW_GAP
        val scrollable = spec.scrollableList()
        val viewportH = if (scrollable) spec.listHeight.roundToInt() else contentH
        val maxScroll = (contentH - viewportH).coerceAtLeast(0)

        val offset = settings.scrollOffset.coerceIn(0f, maxScroll.toFloat())
        val top = ctx.y

        val scissor = if (ctx.g != null && scrollable) beginClip(ctx.g, ctx.left, top, ctx.right, top + viewportH) else null
        // Scrollable but nothing of the viewport is on screen: there is no rect to clip to, so there
        // is nothing worth drawing either. Drawing it unclipped is what crashed the render pass.
        val skipDraw = scrollable && scissor == null
        if (ctx.g != null && !skipDraw) {
            if (rows.isEmpty()) {
                SoulText.draw(
                    ctx.g, spec.emptyText(), ctx.left, top, SIZE_BODY, Theme.TEXT_FAINT,
                    font = bodyFont(), shadow = textShadow(), scissor = scissor, fontChoice = fontChoiceIndex(),
                )
            } else {
                for ((index, row) in rows.withIndex()) {
                    val ry = top + index * (rowH + ROW_GAP) - offset.roundToInt()
                    // Cull rows outside the viewport. Purely an optimization — the scissor is what
                    // makes the edges clean.
                    if (ry + rowH < top || ry > top + viewportH) continue
                    // A row cut off by the viewport edge keeps no click target: its hidden half
                    // would still catch clicks meant for the chips or footer below.
                    val clickable = ry >= top && ry + rowH <= top + viewportH
                    drawRow(ctx, row, ry, scissor, clickable)
                }
            }
            if (scissor != null) ctx.g.disableScissor()
        }

        if (scrollable && maxScroll > 0) {
            ctx.region(ctx.left, top, ctx.right, top + viewportH, onScroll = { delta ->
                settings.scrollOffset = (settings.scrollOffset - delta.toFloat() * SCROLL_STEP)
                    .coerceIn(0f, maxScroll.toFloat())
                settings.markDirty()
            })
        }
        ctx.y = top + viewportH
    }

    private fun drawRow(
        ctx: Ctx,
        row: T,
        ry: Int,
        scissor: ScreenRectangle?,
        clickable: Boolean,
    ) {
        val g = ctx.g ?: return
        var labelX = ctx.left
        val remove = spec.onRowRemove
        if (remove != null && ctx.inventory) {
            removeControl(ctx, g, row, remove, labelX, ry, clickable)
            labelX += LINE_BODY + ICON_GAP
        }
        val icon = spec.rowIcon
        if (icon != null) {
            icon(row)?.takeIf { !it.isEmpty }?.let { stack ->
                g.pose().pushMatrix()
                g.pose().translate(labelX.toFloat(), ry - 1f)
                g.pose().scale(ICON_SIZE / 16f, ICON_SIZE / 16f)
                g.fakeItem(stack, 0, 0)
                g.pose().popMatrix()
            }
            labelX += ICON_SIZE + ICON_GAP
        }
        // The label stops short of the numeric cells instead of running under them.
        val visible = visibleColumns()
        val cellsW = visible.sumOf { it.cellWidth.roundToInt() } + (visible.size - 1).coerceAtLeast(0) * ROW_GAP * 2
        SoulText.draw(
            g, truncate(spec.rowLabel(row), ctx.right - cellsW - GAP - labelX), labelX, ry, SIZE_BODY, spec.rowLabelColor(row),
            font = bodyFont(), shadow = textShadow(), scissor = scissor, fontChoice = fontChoiceIndex(),
        )
        // Numeric cells are laid out right-to-left from the panel edge so the columns line up down
        // the list regardless of how wide each row's label is.
        var x = ctx.right
        for ((index, column) in visible.withIndex().reversed()) {
            val w = column.cellWidth.roundToInt()
            val x0 = x - w
            val text = column.formatCell(column.cellValue(row, settings.tab))
            val size = if (column.isCaption) SIZE_CAPTION else SIZE_BODY
            SoulText.drawCentered(
                g, text, x0 + w / 2, ry, size, column.resolvedCellColor(),
                font = monoFont(), shadow = textShadow(), scissor = scissor, fontChoice = fontChoiceIndex(),
            )
            x = x0
            if (index > 0) {
                x -= ROW_GAP
                g.fill(x, ry + COL_DIVIDER_TOP, x + 1, ry + COL_DIVIDER_TOP + COL_DIVIDER_H, Theme.SEPARATOR)
                x -= ROW_GAP
            }
        }
    }

    /** The per-row `×`: dim, red on hover; drawn with fills so it survives the Minecraft-font setting. */
    private fun removeControl(
        ctx: Ctx,
        g: GuiGraphicsExtractor,
        row: T,
        remove: (T) -> Unit,
        x: Int,
        ry: Int,
        clickable: Boolean,
    ) {
        val size = LINE_BODY
        val hovered = clickable && ctx.hovered(x, ry, x + size, ry + size)
        if (hovered) roundRect(g, x, ry, x + size, ry + size, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        val color = if (hovered) COLOR_PAUSED else Theme.TEXT_DIM
        val span = size - 7
        for (i in 0..span) {
            g.fill(x + 3 + i, ry + 3 + i, x + 4 + i, ry + 4 + i, color)
            g.fill(x + 3 + i, ry + 3 + span - i, x + 4 + i, ry + 4 + span - i, color)
        }
        if (clickable) ctx.region(x, ry, x + size, ry + size, onClick = { remove(row) })
    }

    // ------------------------------------------------------------------ chips

    private fun anyChipVisible(): Boolean =
        spec.columns.any { settings.isColumnVisible(it.id, it.defaultVisible) && it.chipShouldRender() }

    private fun chipsLine(ctx: Ctx) {
        val rows = buildRows()
        val visible = spec.columns.filter {
            settings.isColumnVisible(it.id, it.defaultVisible) && it.chipShouldRender()
        }
        if (visible.isEmpty()) return
        val totals = visible.associate { it.id to it.totalValue(rows, settings.tab) }
        val texts = visible.map { column ->
            val total = totals[column.id] ?: 0L
            val denominator = column.chipPercentageOfColumn?.let { totals[it] } ?: 0L
            formatChip(column.chipLabel, total, denominator, column.chipPercentageOfColumn, column.formatChipTotal)
        }

        if (ctx.g != null) {
            val widths = texts.map { SoulText.width(it, SIZE_BODY, monoFont(), fontChoice = fontChoiceIndex()) }
            // One chip sits flush right; two or more spread across the row — the legacy arrangement.
            if (texts.size == 1) {
                SoulText.draw(
                    ctx.g, texts[0], ctx.right - widths[0], ctx.y, SIZE_BODY, Theme.ACCENT,
                    font = monoFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
                )
            } else {
                val slack = (ctx.contentW - widths.sum()).coerceAtLeast(CHIP_GAP * (texts.size - 1))
                val step = slack.toFloat() / (texts.size - 1)
                var x = ctx.left.toFloat()
                for ((index, text) in texts.withIndex()) {
                    SoulText.draw(
                        ctx.g, text, x.roundToInt(), ctx.y, SIZE_BODY, Theme.ACCENT,
                        font = monoFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
                    )
                    x += widths[index] + step
                }
            }
        }
        ctx.y += LINE_BODY
    }

    private fun formatChip(
        label: String,
        count: Long,
        denominator: Long,
        percentageOf: String?,
        formatTotal: (Long) -> String,
    ): String = if (percentageOf != null && denominator > 0L) {
        String.format(Locale.ROOT, "%s: %s (%.1f%%)", label, formatTotal(count), count.toDouble() / denominator * 100.0)
    } else {
        String.format(Locale.ROOT, "%s: %s", label, formatTotal(count))
    }

    // ------------------------------------------------------------------ footer

    private fun footer(ctx: Ctx) {
        // The selectors sit above everything else in the footer: they decide which numbers the rest
        // of the panel is showing, so the controls that narrow *those* numbers read as subordinate to
        // them. They stack in declaration order.
        for (select in spec.footerSelects) {
            if (select.options.isEmpty()) continue
            val index = select.selectedIndex().coerceIn(0, select.options.size - 1)
            singleSelect(
                ctx, ctx.left, ctx.y, ctx.contentW, select.id,
                label = "${select.labelPrefix}: ${select.options[index]}",
                options = select.options,
                selectedIndex = index,
                onSelect = { chosen ->
                    select.onSelect(chosen)
                    settings.scrollOffset = 0f
                    settings.markDirty()
                },
                expanded = settings.openSelectId == select.id,
                onExpandedChange = { openOnly(if (it) Popup.SELECT else Popup.NONE, select.id) },
            )
            ctx.y += CONTROL_H + GAP
        }

        for (line in spec.footerExtra?.invoke().orEmpty()) {
            if (line.isEmpty()) continue
            segments(ctx, line, ctx.left, SIZE_BODY, bodyFont())
            ctx.y += LINE_BODY + GAP
        }

        val variants = spec.filterVariants()
        if (variants.isNotEmpty()) {
            // Weights 2 : 1 with a 6 px gap, exactly as the legacy Row declared them.
            val unit = (ctx.contentW - GAP) / 3
            val filterW = ctx.contentW - GAP - unit
            multiSelect(
                ctx, ctx.left, ctx.y, filterW, "filter",
                label = spec.filterLabel(settings.filter),
                options = variants.map { variant ->
                    Option(variant, variant in settings.filter) {
                        settings.filter =
                            if (variant in settings.filter) settings.filter - variant else settings.filter + variant
                        settings.scrollOffset = 0f
                        settings.markDirty()
                    }
                },
                expanded = settings.filterDropdownOpen,
                onExpandedChange = { openOnly(if (it) Popup.FILTER else Popup.NONE) },
            )
            button(ctx, ctx.left + filterW + GAP, ctx.y, unit, "Show All") {
                if (settings.filter.isNotEmpty()) {
                    settings.filter = emptySet()
                    settings.scrollOffset = 0f
                    settings.markDirty()
                }
            }
            ctx.y += CONTROL_H + GAP
        }

        val half = (ctx.contentW - GAP) / 2
        val sorts = visibleSorts()
        // A tracker with no sorts at all is legal — it just gets no Sort dropdown, rather than a
        // crash on `first()`.
        val active = sorts.firstOrNull { it.id == settings.sortId } ?: sorts.firstOrNull()
        if (active == null) {
            multiSelect(
                ctx, ctx.left, ctx.y, ctx.contentW, "columns",
                label = "Columns",
                options = spec.columns.map { column ->
                    Option(column.toggleLabel, settings.isColumnVisible(column.id, column.defaultVisible)) {
                        toggleColumn(column.id)
                    }
                },
                expanded = settings.columnDropdownOpen,
                onExpandedChange = { openOnly(if (it) Popup.COLUMNS else Popup.NONE) },
            )
            ctx.y += CONTROL_H
            resetButton(ctx)
            return
        }
        if (settings.sortId != active.id) {
            settings.sortId = active.id
            settings.markDirty()
        }
        singleSelect(
            ctx, ctx.left, ctx.y, half, "sort",
            label = "Sort: ${active.label}",
            options = sorts.map { it.label },
            selectedIndex = sorts.indexOf(active).coerceAtLeast(0),
            onSelect = { index ->
                settings.sortId = sorts[index].id
                settings.scrollOffset = 0f
                settings.markDirty()
            },
            expanded = settings.sortDropdownOpen,
            onExpandedChange = { openOnly(if (it) Popup.SORT else Popup.NONE) },
        )
        multiSelect(
            ctx, ctx.left + half + GAP, ctx.y, ctx.contentW - half - GAP, "columns",
            label = "Columns",
            options = spec.columns.map { column ->
                Option(column.toggleLabel, settings.isColumnVisible(column.id, column.defaultVisible)) {
                    toggleColumn(column.id)
                }
            },
            expanded = settings.columnDropdownOpen,
            onExpandedChange = { openOnly(if (it) Popup.COLUMNS else Popup.NONE) },
        )
        ctx.y += CONTROL_H
        resetButton(ctx)
    }

    private fun resetButton(ctx: Ctx) {
        val reset = spec.onResetSession ?: return
        ctx.y += GAP
        button(ctx, ctx.left, ctx.y, ctx.contentW, spec.resetLabel, reset)
        ctx.y += CONTROL_H
    }

    // ------------------------------------------------------------------ widgets

    private fun button(
        ctx: Ctx,
        x: Int,
        y: Int,
        width: Int,
        label: String,
        onClick: () -> Unit,
    ) {
        val hovered = ctx.hovered(x, y, x + width, y + CONTROL_H)
        if (ctx.g != null) {
            roundRect(
                ctx.g, x, y, x + width, y + CONTROL_H,
                if (hovered) Theme.CONTROL_HOVER else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL,
            )
            SoulText.drawCenteredOn(
                ctx.g, label, x + width / 2, y + CONTROL_H / 2, SIZE_BODY, Theme.TEXT,
                font = bodyFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
            )
        }
        ctx.region(x, y, x + width, y + CONTROL_H, onClick = onClick)
    }

    private class Option(val label: String, val selected: Boolean, val onToggle: () -> Unit)

    private fun trigger(
        ctx: Ctx,
        x: Int,
        y: Int,
        width: Int,
        label: String,
        expanded: Boolean,
        onToggle: () -> Unit,
    ) {
        val hovered = ctx.hovered(x, y, x + width, y + CONTROL_H)
        if (ctx.g != null) {
            val bg = if (expanded || hovered) Theme.PANEL_HOVER else Theme.SECTION
            roundRect(ctx.g, x, y, x + width, y + CONTROL_H, bg, SoulRenderPipelines.ROUND_SMALL)
            // Clip the label so a long "Sort: Alphabetical" cannot run under the caret.
            val labelMax = width - CONTROL_PAD_H * 2 - CARET_W - CARET_GAP
            SoulText.drawVCentered(
                ctx.g, truncate(label, labelMax), x + CONTROL_PAD_H, y + CONTROL_H / 2, SIZE_BODY, Theme.TEXT,
                font = bodyFont(), shadow = textShadow(), fontChoice = fontChoiceIndex(),
            )
            caret(ctx.g, x + width - CONTROL_PAD_H - CARET_W, y + CONTROL_H / 2, expanded)
        }
        ctx.region(x, y, x + width, y + CONTROL_H, depth = DEPTH_POPUP, onClick = onToggle)
    }

    private fun singleSelect(
        ctx: Ctx,
        x: Int,
        y: Int,
        width: Int,
        key: String,
        label: String,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
    ) {
        trigger(ctx, x, y, width, label, expanded) { onExpandedChange(!expanded) }
        if (!expanded) return
        queuePopup(ctx, x, y, width, key, options.size, onExpandedChange) { px, py, popupW, offset, popupH ->
            var ry = py + POPUP_PAD_V - offset
            for ((index, option) in options.withIndex()) {
                val rowX = px + POPUP_PAD_H
                val rowW = popupW - POPUP_PAD_H * 2
                val rowY = ry
                val selected = index == selectedIndex
                optionRow(
                    ctx, rowX, rowY, rowW, py, popupH,
                    draw = {
                        val g = ctx.g ?: return@optionRow
                        // Selected rows get a thin accent bar on the left rather than a fill — the
                        // fill is what hover means here, and the two must stay distinguishable.
                        if (selected) {
                            val barH = (OPTION_ROW_H * 0.6f).roundToInt()
                            g.fill(rowX, rowY + (OPTION_ROW_H - barH) / 2, rowX + INDICATOR_W, rowY + (OPTION_ROW_H + barH) / 2, Theme.ACCENT)
                        }
                        SoulText.drawVCentered(
                            g, option, rowX + INDICATOR_W + OPTION_LABEL_GAP, rowY + OPTION_ROW_H / 2, SIZE_BODY,
                            if (selected) Theme.ACCENT else Theme.TEXT,
                            font = bodyFont(), shadow = textShadow(), scissor = ctx.scissor, fontChoice = fontChoiceIndex(),
                        )
                    },
                    onClick = {
                        onSelect(index)
                        onExpandedChange(false)
                    },
                )
                ry += OPTION_ROW_H
            }
        }
    }

    private fun multiSelect(
        ctx: Ctx,
        x: Int,
        y: Int,
        width: Int,
        key: String,
        label: String,
        options: List<Option>,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
    ) {
        trigger(ctx, x, y, width, label, expanded) { onExpandedChange(!expanded) }
        if (!expanded) return
        queuePopup(ctx, x, y, width, key, options.size, onExpandedChange) { px, py, popupW, offset, popupH ->
            var ry = py + POPUP_PAD_V - offset
            for (option in options) {
                val rowX = px + POPUP_PAD_H
                val rowW = popupW - POPUP_PAD_H * 2
                val rowY = ry
                optionRow(
                    ctx, rowX, rowY, rowW, py, popupH,
                    draw = {
                        val g = ctx.g ?: return@optionRow
                        checkbox(g, rowX, rowY + (OPTION_ROW_H - CHECKBOX) / 2, option.selected)
                        SoulText.drawVCentered(
                            g, option.label, rowX + CHECKBOX + OPTION_LABEL_GAP, rowY + OPTION_ROW_H / 2,
                            SIZE_BODY, Theme.TEXT,
                            font = bodyFont(), shadow = textShadow(), scissor = ctx.scissor, fontChoice = fontChoiceIndex(),
                        )
                    },
                    onClick = option.onToggle,
                )
                ry += OPTION_ROW_H
            }
        }
    }

    /**
     * One popup row: the hover highlight, then [draw] for its indicator and label, then its click
     * region. A row scrolled out of the popup's window is skipped entirely rather than merely
     * clipped — the scissor would hide it, but its click region would still be live and would steal
     * clicks meant for whatever is underneath.
     */
    private fun optionRow(
        ctx: Ctx,
        x: Int,
        y: Int,
        width: Int,
        clipTop: Int,
        clipHeight: Int,
        draw: () -> Unit,
        onClick: () -> Unit,
    ) {
        if (y + OPTION_ROW_H <= clipTop || y >= clipTop + clipHeight) return
        if (ctx.g != null) {
            if (ctx.hovered(x, y, x + width, y + OPTION_ROW_H)) {
                roundRect(ctx.g, x, y, x + width, y + OPTION_ROW_H, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            }
            draw()
        }
        ctx.region(x, y, x + width, y + OPTION_ROW_H, depth = DEPTH_POPUP, onClick = onClick)
    }

    /**
     * Queue a popup to paint after the rest of the panel, with a full-panel dismiss scrim beneath it.
     *
     * The scrim sits above every ordinary widget but below the popup's own rows, so a click anywhere
     * else in the panel closes the popup instead of activating what it landed on — and a click on a
     * row still reaches the row.
     */
    private fun queuePopup(
        ctx: Ctx,
        triggerX: Int,
        triggerY: Int,
        triggerW: Int,
        key: String,
        optionCount: Int,
        onExpandedChange: (Boolean) -> Unit,
        drawRows: (px: Int, py: Int, popupW: Int, offset: Int, popupH: Int) -> Unit,
    ) {
        if (ctx.dry) return
        val g = ctx.g ?: return
        val fullH = optionCount * OPTION_ROW_H + POPUP_PAD_V * 2
        val popupH = min(fullH, FOOTER_POPUP_MAX_H)
        val maxScroll = (fullH - popupH).coerceAtLeast(0)
        val offset = popupScroll.getOrDefault(key, 0f).coerceIn(0f, maxScroll.toFloat()).roundToInt()
        val py = popupY(triggerY, popupH)
        val popupW = triggerW

        ctx.overlay = {
            // Dismiss scrim. Deliberately the whole panel and not the whole screen: a click outside
            // the panel is not ours to swallow.
            ctx.region(0, 0, spec.width, ctx.y, depth = DEPTH_SCRIM, onClick = { onExpandedChange(false) })
            roundRect(g, triggerX, py, triggerX + popupW, py + popupH, Theme.SECTION, SoulRenderPipelines.ROUND_SMALL)
            val clip = beginClip(g, triggerX, py, triggerX + popupW, py + popupH)
            if (clip != null) {
                ctx.scissor = clip
                drawRows(triggerX, py, popupW, offset, popupH)
                ctx.scissor = null
                g.disableScissor()
            }
            if (maxScroll > 0) {
                ctx.region(
                    triggerX, py, triggerX + popupW, py + popupH,
                    depth = DEPTH_SCRIM + 10,
                    onScroll = { delta ->
                        popupScroll[key] = (offset - delta.toFloat() * OPTION_ROW_H).coerceIn(0f, maxScroll.toFloat())
                    },
                )
            }
        }
    }

    /**
     * Above or below the trigger, whichever side of it has the room — measured against the panel, not
     * the screen. A footer trigger near the panel's bottom opens upward even though the screen below
     * it is empty, which is the behaviour the legacy panel had and the one that keeps a popup next to
     * the control it belongs to.
     */
    private fun popupY(
        triggerY: Int,
        popupH: Int,
    ): Int {
        val roomAbove = triggerY - POPUP_GAP
        val below = triggerY + CONTROL_H + POPUP_GAP
        return if (roomAbove >= popupH) triggerY - popupH - POPUP_GAP else below
    }

    // ------------------------------------------------------------------ primitives

    /**
     * The 7×4 caret. `▾` and `▴` are in **no** MSDF family — a codepoint missing everywhere advances
     * the pen and draws nothing at all — so it is built from fill strips, the same way the config
     * menu and the Move GUI build theirs. Points down when collapsed, up when expanded.
     */
    private fun caret(
        g: GuiGraphicsExtractor,
        x: Int,
        cy: Int,
        up: Boolean,
    ) {
        val top = cy - CARET_H / 2
        val cx = x + CARET_W / 2
        for (i in 0 until CARET_H) {
            val half = if (up) i else CARET_H - 1 - i
            g.fill(cx - half, top + i, cx + half + 1, top + i + 1, Theme.TEXT_DIM)
        }
    }

    /** A 12 px box: accent fill plus a two-segment tick when selected, outlined when not. */
    private fun checkbox(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        selected: Boolean,
    ) {
        if (!selected) {
            roundRect(g, x, y, x + CHECKBOX, y + CHECKBOX, Theme.PANEL, SoulRenderPipelines.ROUND_SMALL)
            outline(g, x, y, x + CHECKBOX, y + CHECKBOX, Theme.TEXT_DIM)
            return
        }
        roundRect(g, x, y, x + CHECKBOX, y + CHECKBOX, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
        // Drawn as two stepped strokes rather than a `✓` glyph: the tick has to survive the
        // Minecraft-font setting, and that font has no check mark at all — an invisible failure.
        val white = 0xFFFFFFFF.toInt()
        stroke(g, x + 2, y + 6, x + 5, y + 9, white)
        stroke(g, x + 5, y + 9, x + 10, y + 3, white)
    }

    /** A 1 px line between two points, stepped along its longer axis. */
    private fun stroke(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
    ) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        for (i in 0..steps) {
            val px = x0 + (x1 - x0) * i / steps
            val py = y0 + (y1 - y0) * i / steps
            g.fill(px, py, px + 1, py + 2, color)
        }
    }

    private fun outline(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
    ) {
        g.fill(x0, y0, x1, y0 + 1, color)
        g.fill(x0, y1 - 1, x1, y1, color)
        g.fill(x0, y0, x0 + 1, y1, color)
        g.fill(x1 - 1, y0, x1, y1, color)
    }

    /**
     * Enable a GL scissor for a pose-local rect, **clamped to the screen**.
     *
     * A HUD can be dragged so that part of it hangs off an edge, and 26.2's render pass *throws* on a
     * scissor outside the render area rather than clamping it — deferred, from
     * `GuiRenderer.executeDraw`, so no try/catch around our own drawing can catch it. That crashed the
     * client on the first frame the panel drew while positioned near an edge. Intersecting the rect
     * with the visible area first is the fix; the transform is axis-aligned, so it is arithmetic on
     * the pose's scale and translation.
     *
     * @return the rect to thread into `SoulText` draws (our MSDF states do not capture the GL scissor
     *   by themselves), or **null when nothing of it is on screen** — in which case no scissor was
     *   enabled, [endClip] must not be called, and the caller should skip drawing entirely.
     */
    private fun beginClip(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): ScreenRectangle? {
        val m = Matrix3x2f(g.pose())
        val scaleX = m.m00
        val scaleY = m.m11
        if (scaleX <= 0f || scaleY <= 0f) return null
        // Round inward on both ends, so the rect can never land a pixel outside after the transform.
        val cx0 = ceil(max(x0.toFloat(), -m.m20 / scaleX)).toInt()
        val cy0 = ceil(max(y0.toFloat(), -m.m21 / scaleY)).toInt()
        val cx1 = floor(min(x1.toFloat(), (g.guiWidth() - m.m20) / scaleX)).toInt()
        val cy1 = floor(min(y1.toFloat(), (g.guiHeight() - m.m21) / scaleY)).toInt()
        if (cx1 <= cx0 || cy1 <= cy0) return null
        g.enableScissor(cx0, cy0, cx1, cy1)
        return ScreenRectangle(cx0, cy0, cx1 - cx0, cy1 - cy0).transformMaxBounds(m)
    }

    /**
     * A separator with [GAP] on **both** sides. The legacy panel was one `Column(gap = 6)`, so every
     * sibling — header, divider, list, chips, divider, footer — sat 6 px from the next. Owning the
     * leading gap here is what reproduces that: without it the chip line ran straight into the line
     * above the footer.
     */
    private fun divider(ctx: Ctx) {
        ctx.y += GAP
        ctx.g?.fill(ctx.left, ctx.y, ctx.right, ctx.y + 1, Theme.SEPARATOR)
        ctx.y += 1 + GAP
    }

    private fun roundRect(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
        pipeline: RenderPipeline,
    ) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null),
        )
    }

    // ------------------------------------------------------------------ text helpers

    private fun segments(
        ctx: Ctx,
        line: TrackerSegments,
        startX: Int,
        size: Float,
        font: MsdfFont,
    ) {
        val g = ctx.g ?: return
        var x = startX
        for ((text, color) in line) {
            SoulText.draw(g, text, x, ctx.y, size, color, font = font, shadow = textShadow(), fontChoice = fontChoiceIndex())
            x += SoulText.width(text, size, font, fontChoice = fontChoiceIndex())
        }
    }

    /** A spec-supplied block that stands in for the row list — same height, so nothing jumps. */
    private fun segmentBlock(
        ctx: Ctx,
        lines: List<TrackerSegments>,
        height: Int,
    ) {
        val top = ctx.y
        val lineH = LINE_BODY + ROW_GAP
        val blockH = (lines.size * lineH - ROW_GAP).coerceAtLeast(0)
        ctx.y = top + (height - blockH) / 2
        for (line in lines) {
            val width = segmentsWidth(line, SIZE_BODY, bodyFont())
            segments(ctx, line, (spec.width - width) / 2, SIZE_BODY, bodyFont())
            ctx.y += lineH
        }
        ctx.y = top + height
    }

    private fun segmentsWidth(
        line: TrackerSegments,
        size: Float,
        font: MsdfFont,
    ): Int = line.sumOf { SoulText.width(it.first, size, font, fontChoice = fontChoiceIndex()) }

    private fun truncate(
        text: String,
        maxWidth: Int,
    ): String {
        if (maxWidth <= 0) return ""
        if (SoulText.width(text, SIZE_BODY, bodyFont(), fontChoice = fontChoiceIndex()) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() &&
            SoulText.width("$cut…", SIZE_BODY, bodyFont(), fontChoice = fontChoiceIndex()) > maxWidth
        ) {
            cut = cut.dropLast(1)
        }
        return "$cut…"
    }

    private fun bodyFont(): MsdfFont = MsdfFonts.regularFor(fontChoiceIndex())

    /**
     * The numeric face. The legacy theme used Inter **Medium** here — "not monospace, but a bit more
     * weight for numeric columns than Regular". We ship Regular and SemiBold, so SemiBold is the
     * closer of the two.
     */
    private fun monoFont(): MsdfFont = MsdfFonts.semiboldFor(fontChoiceIndex())

    // ------------------------------------------------------------------ data

    private fun visibleColumns(): List<TrackerColumn<T>> =
        spec.columns.filter { settings.isColumnVisible(it.id, it.defaultVisible) }

    private fun visibleSorts(): List<TrackerSort<T>> =
        spec.sorts.filter { sort ->
            val required = sort.requiresColumnId ?: return@filter true
            val column = spec.columns.firstOrNull { it.id == required }
            column == null || settings.isColumnVisible(column.id, column.defaultVisible)
        }.ifEmpty { spec.sorts }

    /**
     * Flip a column, refusing to leave the panel with none — a label-only row is useless — and
     * snapping the sort to a still-visible one when the current sort's column went away.
     */
    private fun toggleColumn(columnId: String) {
        val column = spec.columns.firstOrNull { it.id == columnId } ?: return
        val nowVisible = !settings.isColumnVisible(column.id, column.defaultVisible)
        if (!nowVisible && visibleColumns().count { it.id != columnId } == 0) return
        settings.setColumnVisible(columnId, nowVisible)
        val sorts = visibleSorts()
        if (sorts.none { it.id == settings.sortId } && sorts.isNotEmpty()) settings.sortId = sorts.first().id
        settings.markDirty()
    }

    private fun buildRows(): List<T> {
        val raw = spec.rowsProvider(settings.tab)
        val filtered = if (settings.filter.isEmpty()) raw else raw.filter { spec.filterPredicate(it, settings.filter) }
        val sort = spec.sorts.firstOrNull { it.id == settings.sortId } ?: spec.sorts.firstOrNull()
        // Alphabetical tie-break, so rows holding the same number keep a stable order between frames
        // instead of shuffling.
        val comparator = sort?.comparator?.then(compareBy(spec.rowLabel)) ?: compareBy(spec.rowLabel)
        return filtered.sortedWith(comparator)
    }
}

private val COLOR_PAUSED = 0xFFFF5555.toInt()
