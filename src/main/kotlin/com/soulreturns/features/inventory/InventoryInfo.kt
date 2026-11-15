package com.soulreturns.features.inventory

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.core.events.Events
import com.soulreturns.data.hypixel.SkillCaps
import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.data.skyblock.SelfProfileLoaded
import com.soulreturns.data.skyblock.SelfProfileSnapshot
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.TabSkyblockLevel
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.features.darkui.DarkUi
import com.soulreturns.platform.concurrent.SoulScope
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import org.joml.Matrix3x2f
import com.soulreturns.features.profileviewer.ProfileViewerService

/**
 * "Show SkyBlock info in inventory": the stats the player picked, drawn in
 * [InventoryCleanup.FREE_AREA] with Soul's MSDF font or, if the player prefers, the vanilla font.
 *
 * Two layouts: **tiles** (default; the profile entry becomes a header line and up to four other
 * stats become cards with their bar along the bottom edge) and the label / value **list**. Hovering
 * a stat shows its [InfoLine.details] as a vanilla tooltip.
 *
 * Only active while the crafting grid, the recipe book and the off-hand slot are all hidden: the
 * area is their space (the recipe book button sits inside it, the off-hand slot beside it).
 *
 * Data is [SkyblockSelfProfile]'s snapshot, refreshed through the response cache when the
 * inventory opens and the snapshot is older than [STALE_MS]. Skill caps come from the backend so
 * Farming, Taming and Skill Average match the Profile Viewer.
 */
object InventoryInfo {
    private const val STALE_MS = 5 * 60_000L
    private const val PAD = 2
    private const val BAR_HEIGHT = 2

    // List layout.
    private const val LINE_GAP = 2
    private const val LINE_HEIGHT = 9
    /** Vanilla 1×: Soul text then shares the vanilla baseline, cap height and line height. */
    private const val SOUL_SIZE = 8f
    /**
     * Where a progress bar starts below the text top, the same for both fonts so switching font
     * never moves a row: clear of vanilla's `g` (bottom 8) and of the smooth fonts' deeper
     * descenders (bottom ~9.4).
     */
    private const val BAR_OFFSET = 10

    // Tiles layout: two columns of 44 px with a 3 px gutter fill the 91 px content width.
    private const val TILE_W = 44
    private const val TILE_H = 29
    private const val TILE_GAP_X = 3
    private const val TILE_GAP_Y = 2
    private const val TILE_COLUMNS = 2
    private const val TILE_ROWS = 2
    private const val HEADER_H = 12
    private const val CAPTION_SIZE = 6f
    private const val TILE_VALUE_SIZE = 10.5f

    private val LABEL = 0xFF555555.toInt()
    private val VALUE = 0xFF202020.toInt()
    private val MUTED = 0xFF707070.toInt()
    private val BAR_BG = 0xFF8B8B8B.toInt()
    private val TILE_BG = 0xFFB9B9B9.toInt()
    private val TILE_BORDER = 0xFFA5A5A5.toInt()
    private val DARK_LABEL = 0xFFA0A0A0.toInt()
    private val DARK_VALUE = 0xFFE6E6E6.toInt()
    private val DARK_MUTED = 0xFF808080.toInt()
    private val DARK_BAR_BG = 0xFF3A3A3A.toInt()
    private val DARK_TILE_BG = 0xFF242424.toInt()
    private val DARK_TILE_BORDER = 0xFF303030.toInt()
    private val BAR_FILL = 0xFF3FA34D.toInt()
    private val BAR_MAXED = 0xFFE0A526.toInt()
    private val LINK = 0xFF1F4FA8.toInt()
    private val LINK_HOVER = 0xFF0E3478.toInt()
    private val LINK_HOVER_BG = 0xFFAFB9C9.toInt()
    private val DARK_LINK = 0xFF7AA7FF.toInt()
    private val DARK_LINK_HOVER = 0xFFB3CDFF.toInt()
    private val DARK_LINK_HOVER_BG = 0xFF2B3442.toInt()

    /** The "Profile" link's hit rect from the last real (non-preview) draw; clicks test against it. */
    private val linkRect = IntArray(4)
    private var linkShown = false
    private var linkHovered = false

    @Volatile
    private var caps: SkillCaps? = null

    @Volatile
    private var capsProfileId: String? = null

    // Computed lines, rebuilt only when what they depend on changes (identity compare).
    private var cachedSnapshot: SelfProfileSnapshot? = null
    private var cachedCaps: SkillCaps? = null
    private var cachedEntries: List<String>? = null
    private var cachedTabLevel: TabSkyblockLevel? = null
    private var cachedProfileName: String? = null
    private var cachedLines: List<Pair<String, InfoLine>> = emptyList()

    fun register() {
        Events.subscribe<SelfProfileLoaded> { event -> if (active()) fetchCaps(event.snapshot) }
    }

    /** The three hides this feature needs, all in effect (Clean up inventory on, each part on). */
    fun requirementsMet(): Boolean {
        val general = cfg.general
        return general.craftingGridHidden && general.recipeBookHidden && general.offhandSlotHidden
    }

    fun active(): Boolean = cfg.inventoryInfo.enabled && requirementsMet()

    /** Called when the player inventory opens: keep the snapshot and caps reasonably fresh. */
    fun onInventoryOpened() {
        if (!active()) return
        SkyblockSelfProfile.refreshIfStale(STALE_MS)
        SkyblockSelfProfile.snapshot?.let { if (capsProfileId != it.profileId) fetchCaps(it) }
    }

    private fun fetchCaps(snapshot: SelfProfileSnapshot) {
        capsProfileId = snapshot.profileId
        SoulScope.io.launch {
            val fetched = try {
                SkillCapsClient.caps(snapshot.uuidUndashed)?.forProfile(snapshot.profileId, isSelected = true)
            } catch (_: Throwable) {
                null
            }
            Minecraft.getInstance().execute { if (capsProfileId == snapshot.profileId) caps = fetched }
        }
    }

    private fun tiles(): Boolean = cfg.inventoryInfo.layout == 0

    /**
     * Draw into the free area of an inventory frame at ([left], [top]); [preview] falls back to
     * sample data. ([mouseX], [mouseY]) in the same coordinates drives the hover tooltip; pass
     * nothing where hovering makes no sense.
     */
    fun draw(g: GuiGraphicsExtractor, left: Int, top: Int, preview: Boolean = false, mouseX: Int = -1, mouseY: Int = -1) {
        if (!preview) linkShown = false
        val dark = DarkUi.active
        val area = InventoryCleanup.FREE_AREA
        val x0 = left + area[0] + PAD
        val x1 = left + area[2] - PAD
        val y0 = top + area[1] + PAD
        val lines = lines(preview)
        if (lines == null) {
            var y = y0
            val message = if (SkyblockProfileState.current == null) "Join SkyBlock to see your stats" else "Loading profile..."
            for (part in wrap(message, x1 - x0)) {
                text(g, part, x0, y, if (dark) DARK_MUTED else MUTED, bold = false)
                y += LINE_HEIGHT + 1
            }
            return
        }
        val interactive = !preview
        linkHovered = false
        val hovered = if (tiles()) {
            drawTiles(g, lines, x0, y0, x1, dark, mouseX, mouseY, interactive)
        } else {
            drawList(g, lines, x0, y0, x1, top + area[3] - PAD, dark, mouseX, mouseY, interactive)
        }
        val font = Minecraft.getInstance().font
        if (linkHovered) {
            InventoryCleanup.ownTooltip {
                g.setComponentTooltipForNextFrame(font, listOf(net.minecraft.network.chat.Component.literal("Open your profile viewer (/spv)")), mouseX, mouseY)
            }
        } else if (hovered != null && cfg.inventoryInfo.hoverDetails && hovered.details.isNotEmpty()) {
            InventoryCleanup.ownTooltip { g.setComponentTooltipForNextFrame(font, hovered.details, mouseX, mouseY) }
        }
    }

    /**
     * A left click at ([mouseX], [mouseY]) in the inventory: opens the local player's profile viewer
     * when it lands on the "Profile" link. Returns whether it did, so the caller can consume it.
     */
    fun click(mouseX: Double, mouseY: Double): Boolean {
        if (!linkShown) return false
        if (mouseX < linkRect[0] || mouseX >= linkRect[2] || mouseY < linkRect[1] || mouseY >= linkRect[3]) return false
        val self = Minecraft.getInstance().user.name
        if (self.isNullOrBlank()) return false
        ProfileViewerService.open(self)
        return true
    }

    /**
     * [s] as an inline link with its text top at ([x], [y]): link-colored, with a rounded highlight
     * on hover. When [interactive] its padded rect becomes the click target. Returns whether hovered.
     */
    private fun link(
        g: GuiGraphicsExtractor,
        s: String,
        x: Int,
        y: Int,
        size: Float,
        dark: Boolean,
        mouseX: Int,
        mouseY: Int,
        interactive: Boolean,
    ): Boolean {
        val height = if (soulFont()) kotlin.math.ceil(size * 0.875f).toInt() else 7
        val rx0 = x - 2
        val ry0 = y - 2
        val rx1 = x + width(s, size, bold = false) + 2
        val ry1 = y + height + 2
        val hovered = interactive && mouseX in rx0 until rx1 && mouseY in ry0 until ry1
        if (hovered) roundRect(g, rx0, ry0, rx1, ry1, if (dark) DARK_LINK_HOVER_BG else LINK_HOVER_BG)
        val color = if (hovered) (if (dark) DARK_LINK_HOVER else LINK_HOVER) else (if (dark) DARK_LINK else LINK)
        text(g, s, x, y, color, bold = false, size = size)
        if (interactive) {
            linkRect[0] = rx0
            linkRect[1] = ry0
            linkRect[2] = rx1
            linkRect[3] = ry1
            linkShown = true
        }
        return hovered
    }

    /** The list layout; returns the line under the mouse. */
    private fun drawList(
        g: GuiGraphicsExtractor,
        lines: List<Pair<String, InfoLine>>,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        dark: Boolean,
        mouseX: Int,
        mouseY: Int,
        interactive: Boolean,
    ): InfoLine? {
        val bars = cfg.inventoryInfo.progressBars
        var hovered: InfoLine? = null
        var y = y0
        for ((id, line) in lines) {
            val height = lineHeight(line, bars)
            if (y + height - LINE_GAP > y1) break
            val valueWidth = width(line.value, SOUL_SIZE, bold = true)
            val label = fit(line.label, (x1 - x0 - valueWidth - 4).coerceAtLeast(0))
            if (id == InventoryInfoEntries.PROFILE) {
                if (link(g, label, x0, y, SOUL_SIZE, dark, mouseX, mouseY, interactive)) linkHovered = true
            } else {
                text(g, label, x0, y, if (dark) DARK_LABEL else LABEL, bold = false)
            }
            text(g, line.value, x1 - valueWidth, y, valueColor(line, dark), bold = true)
            val progress = line.progress
            if (bars && progress != null) bar(g, x0, y + BAR_OFFSET, x1, progress, line.maxed, dark)
            if (mouseX in x0 until x1 && mouseY >= y && mouseY < y + height - LINE_GAP) hovered = line
            y += height
        }
        return hovered
    }

    /** The tiles layout; returns the line under the mouse. */
    private fun drawTiles(
        g: GuiGraphicsExtractor,
        lines: List<Pair<String, InfoLine>>,
        x0: Int,
        y0: Int,
        x1: Int,
        dark: Boolean,
        mouseX: Int,
        mouseY: Int,
        interactive: Boolean,
    ): InfoLine? {
        val soul = soulFont()
        var hovered: InfoLine? = null
        val header = lines.firstOrNull { it.first == InventoryInfoEntries.PROFILE }?.second
        var tilesTop = y0
        if (header != null) {
            text(g, header.value, x0, y0, if (dark) DARK_VALUE else VALUE, bold = true)
            val caption = header.label
            val captionSize = if (soul) CAPTION_SIZE else SOUL_SIZE
            val cx = x1 - width(caption, captionSize, bold = false)
            if (link(g, caption, cx, y0 + if (soul) 1 else 0, captionSize, dark, mouseX, mouseY, interactive)) linkHovered = true
            tilesTop += HEADER_H
        }
        val bars = cfg.inventoryInfo.progressBars
        val stats = lines.filter { it.first != InventoryInfoEntries.PROFILE }
        for (i in 0 until minOf(stats.size, TILE_COLUMNS * TILE_ROWS)) {
            val line = stats[i].second
            val tx = x0 + (i % TILE_COLUMNS) * (TILE_W + TILE_GAP_X)
            val ty = tilesTop + (i / TILE_COLUMNS) * (TILE_H + TILE_GAP_Y)
            roundRect(g, tx, ty, tx + TILE_W, ty + TILE_H, if (dark) DARK_TILE_BORDER else TILE_BORDER)
            roundRect(g, tx + 1, ty + 1, tx + TILE_W - 1, ty + TILE_H - 1, if (dark) DARK_TILE_BG else TILE_BG)
            val progress = line.progress
            if (bars && progress != null) bar(g, tx + 1, ty + TILE_H - 1 - BAR_HEIGHT, tx + TILE_W - 1, progress, line.maxed, dark)
            // The vanilla font only looks right at whole scales, so it keeps 1× for both lines.
            val captionSize = if (soul) CAPTION_SIZE else SOUL_SIZE
            val valueSize = if (soul) TILE_VALUE_SIZE else SOUL_SIZE
            val label = fit(line.label, TILE_W - 8, captionSize)
            text(g, label, tx + 4, ty + if (soul) 4 else 3, if (dark) DARK_LABEL else LABEL, bold = false, size = captionSize)
            text(g, line.value, tx + 4, ty + 14, valueColor(line, dark), bold = true, size = valueSize)
            if (mouseX in tx until tx + TILE_W && mouseY in ty until ty + TILE_H) hovered = line
        }
        return hovered
    }

    private fun valueColor(line: InfoLine, dark: Boolean): Int =
        line.valueColor?.takeIf { cfg.inventoryInfo.levelColor } ?: if (dark) DARK_VALUE else VALUE

    private fun bar(g: GuiGraphicsExtractor, x0: Int, y: Int, x1: Int, progress: Double, maxed: Boolean, dark: Boolean) {
        g.fill(x0, y, x1, y + BAR_HEIGHT, if (dark) DARK_BAR_BG else BAR_BG)
        val fill = x0 + ((x1 - x0) * progress.coerceIn(0.0, 1.0)).toInt()
        if (fill > x0) g.fill(x0, y, fill, y + BAR_HEIGHT, if (maxed) BAR_MAXED else BAR_FILL)
    }

    /**
     * A rounded card in GUI pixels. The SDF radius is in composable units, so drawing at half scale
     * turns the smallest pipeline radius (4) into 2 GUI px, which suits a 29 px tile.
     */
    private fun roundRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.pose().pushMatrix()
        g.pose().scale(0.5f, 0.5f)
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(
                SoulRenderPipelines.ROUND_SMALL, TextureSetup.noTexture(), Matrix3x2f(g.pose()),
                x0 * 2, y0 * 2, x1 * 2, y1 * 2, color, null,
            ),
        )
        g.pose().popMatrix()
    }

    /**
     * Which of [entries] the current layout can show; the settings screen flags the rest. Tiles
     * show the profile as the header and the first four other stats; the list stacks rows until the
     * area is full (heights depend only on whether an entry has a bar, so samples suffice).
     */
    fun fitting(entries: List<String>, bars: Boolean): Set<String> {
        if (tiles()) {
            val out = HashSet<String>()
            var tiles = 0
            for (id in entries) {
                if (id == InventoryInfoEntries.PROFILE) {
                    out += id
                } else if (tiles < TILE_COLUMNS * TILE_ROWS) {
                    out += id
                    tiles++
                }
            }
            return out
        }
        val area = InventoryCleanup.FREE_AREA
        val available = area[3] - area[1] - 2 * PAD + LINE_GAP
        val out = HashSet<String>()
        var used = 0
        for (id in entries) {
            val entry = InventoryInfoEntries.byId(id) ?: continue
            used += lineHeight(entry.sample, bars)
            if (used > available) break
            out += id
        }
        return out
    }

    private fun lineHeight(line: InfoLine, bars: Boolean): Int =
        if (bars && line.progress != null) BAR_OFFSET + BAR_HEIGHT + 1 + LINE_GAP else LINE_HEIGHT + LINE_GAP

    // Text goes through these so the font option is one branch, not one per call.
    private fun soulFont(): Boolean = cfg.inventoryInfo.soulFont

    private fun width(s: String, size: Float, bold: Boolean): Int =
        if (soulFont()) SoulText.width(s, size, if (bold) MsdfFonts.semibold else MsdfFonts.regular, forceCustom = true)
        else Minecraft.getInstance().font.width(s)

    private fun text(g: GuiGraphicsExtractor, s: String, x: Int, y: Int, color: Int, bold: Boolean, size: Float = SOUL_SIZE) {
        if (soulFont()) {
            SoulText.draw(
                g, s, x, y, size, color,
                if (bold) MsdfFonts.semibold else MsdfFonts.regular, forceCustom = true, shadow = false,
            )
        } else {
            g.text(Minecraft.getInstance().font, s, x, y, color, false)
        }
    }

    /** The longest prefix of [s] that fits in [maxWidth]. */
    private fun fit(s: String, maxWidth: Int, size: Float = SOUL_SIZE): String {
        if (width(s, size, bold = false) <= maxWidth) return s
        var end = s.length
        while (end > 0 && width(s.substring(0, end), size, bold = false) > maxWidth) end--
        return s.substring(0, end)
    }

    private fun wrap(s: String, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        var current = ""
        for (word in s.split(' ')) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && width(next, SOUL_SIZE, bold = false) > maxWidth) {
                out.add(current)
                current = word
            } else {
                current = next
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /** (entry id, line) pairs to draw, or null when there is no data yet (and this is not a preview). */
    private fun lines(preview: Boolean): List<Pair<String, InfoLine>>? {
        val ids = cfg.inventoryInfo.entries
        val snapshot = SkyblockSelfProfile.snapshot
        val tabLevel = TabWidgets.snapshot.skyblockLevel
        val profileName = SkyblockProfileState.current
        if (snapshot == null && tabLevel == null && profileName == null) {
            return if (preview) ids.mapNotNull { id -> InventoryInfoEntries.byId(id)?.let { id to it.sample } } else null
        }
        val currentCaps = caps.takeIf { snapshot != null && capsProfileId == snapshot.profileId }
        if (snapshot !== cachedSnapshot || currentCaps !== cachedCaps || ids !== cachedEntries ||
            tabLevel != cachedTabLevel || profileName != cachedProfileName
        ) {
            val context = snapshot?.let { InfoContext(it, currentCaps) }
            cachedLines = ids.mapNotNull { id ->
                val entry = InventoryInfoEntries.byId(id) ?: return@mapNotNull null
                val live = if (id == "skyblock_level" && tabLevel != null) InventoryInfoEntries.tabLevel(tabLevel)
                    else if (id == InventoryInfoEntries.PROFILE && profileName != null) InfoLine("Profile", profileName)
                    else null
                id to (live ?: context?.let(entry.compute) ?: InfoLine(entry.sample.label, "-"))
            }
            cachedSnapshot = snapshot
            cachedCaps = currentCaps
            cachedEntries = ids
            cachedTabLevel = tabLevel
            cachedProfileName = profileName
        }
        return cachedLines
    }
}
