package com.soulreturns.config.gui

import com.soulreturns.Soul
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.menu.MenuRules
import com.soulreturns.config.gui.menu.MenuSearch
import com.soulreturns.config.gui.menu.MenuState
import com.soulreturns.config.gui.menu.NowContext
import com.soulreturns.config.gui.menu.NowGroup
import com.soulreturns.config.gui.menu.Releases
import com.soulreturns.config.gui.menu.RightNow
import com.soulreturns.config.gui.menu.SearchGroup
import com.soulreturns.config.gui.menu.SearchItem
import com.soulreturns.config.gui.menu.Detector
import com.soulreturns.config.gui.model.ConfigOption
import com.soulreturns.config.gui.model.FeatureCard
import com.soulreturns.config.gui.model.SettingsSection
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.dungeons.map.DungeonMapConfigScreen
import com.soulreturns.features.dungeons.sync.SoulBackendChannel
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.features.inventory.InventoryInfoScreen
import com.soulreturns.features.inventorybuttons.InventoryButtonsScreen
import com.soulreturns.features.keybinds.CommandKeybindScreen
import com.soulreturns.features.profit.dragon.DragonProfitTracker
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.features.updater.UpdateNotices
import com.soulreturns.features.updater.UpdateScreen
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.platform.http.BackendAuth
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.platform.updater.UpdateError
import com.soulreturns.platform.updater.UpdateNotice
import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateService
import com.soulreturns.platform.updater.UpdateSnapshot
import com.soulreturns.sound.gui.SoundLibraryScreen
import com.soulreturns.sound.gui.SoundReplacerScreen
import com.soulreturns.ui.AutoUiScale
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.movable.HudMoveScreen
import com.soulreturns.ui.screen.SoulTextField
import com.soulreturns.ui.screen.RichText
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * The Soul config menu. Spec: `mocks/config-menu/` (mock.js holds every constant, placement and
 * behavior ported here; BACKLOG "Config menu UX rework" holds the decisions), on the render layer
 * of rule://render-layer.
 *
 * A centered card: the sidebar (activities, Start, Settings; collapsible with a hover peek), a top
 * bar (tabs, the global Advanced switch, search), the body (Start page, an activity's feature cards
 * with the side panel, or the Settings screen) and a footer. Ctrl+K opens the search palette over
 * the whole menu with Minecraft's blur behind it. The first launch asks "What do you play?".
 *
 * [target] opens on a card (`f:<card>`) or a Settings tab (`s:<tab>[:<section>]`), see [forFeature].
 */
class SoulConfigScreen(
    private val parent: Screen? = null,
    private val target: String? = null,
) : ConfigScreenBase(Component.literal("Soul Config")) {
    companion object {
        // Layout (Soul px), from the mock's constants.
        private const val TOP_NAV_H = 44
        private const val SIDEBAR_W = 200
        private const val SIDEBAR_COLLAPSED_W = 44
        private const val FOOTER_H = 40
        private const val CARD_FW = 0.9f
        private const val CARD_FH = 0.88f
        private const val CARD_MAX_W = 1100
        private const val CARD_MAX_H = 760
        private const val CARD_MARGIN = 8
        private const val PEEK_DELAY_MS = 620L
        private const val PANEL_FRACTION = 0.46f
        private const val PANEL_MIN_W = 250
        private const val PANEL_MAX_W = 360
        private const val LIST_MIN_W = 260
        private const val START_TWO_COL_W = 560
        private const val PALETTE_W = 470
        private const val PALETTE_TOP = 0.12f
        private const val PALETTE_MAX_H = 0.72f
        private const val FLASH_MS = 1800L
        private const val TOAST_MS = 2200L
        private const val ROW_H = 32
        private const val SCROLL_STEP = 18
        private const val TAB_H = 24

        // The hidden developer-unlock gesture: this many clicks on the version text in About, each
        // within the gap of the previous one.
        private const val VERSION_CLICKS = 7
        private const val VERSION_CLICK_GAP_MS = 1500L

        private class Tip(val kind: String, val text: String, val button: String, val action: String)

        /** Start's tip card: a new one each time the menu opens, ↻ cycles. Only true facts. */
        private val TIPS =
            listOf(
                Tip("Did you know", "Ctrl+K searches every feature and setting.", "Try it", "pal"),
                Tip("Tip", "Right-click a HUD in the Move GUI for its settings.", "Open Move GUI", "movegui"),
                Tip("Tip", "Hold Alt while dragging in the Move GUI to ignore snapping.", "Open Move GUI", "movegui"),
                Tip("Tip", "Scroll over a HUD in the Move GUI to scale it.", "Open Move GUI", "movegui"),
                Tip("Did you know", "The Move GUI has undo and redo (Ctrl+Z, Ctrl+Y).", "Open Move GUI", "movegui"),
                Tip("Tip", "Arrow keys nudge the selected HUD in the Move GUI.", "Open Move GUI", "movegui"),
                Tip("Tip", "Middle-click an item in a sack menu to track it, again to untrack.", "Go to", "f:sacks"),
                Tip("Tip", "Right-click an inventory button to open the buttons editor.", "Open editor", "invbuttons"),
                Tip("Did you know", "/sp <name> opens the Profile Viewer for any player.", "Go to", "s:profile:spv"),
                Tip("Tip", "Click \"Profile\" in the SkyBlock info beside your inventory to open your own profile.", "Go to", "f:sbinfo"),
                Tip("Tip", "With price tooltips on, hold Shift on an item for its value and stack total.", "Go to", "f:prices"),
                Tip("Did you know", "Esc while binding a key unbinds it.", "Go to", "s:shortcuts:cmdkeys"),
            )

        /** Counts menu opens, so each open shows the next tip. */
        private var opens = 0

        /** Opens the menu on [cardId]'s panel (a HUD's "settings" entry). */
        fun forFeature(parent: Screen?, cardId: String): SoulConfigScreen = SoulConfigScreen(parent, "f:$cardId")

        /** Opens the menu on a Settings tab, optionally highlighting one of its sections. */
        fun forSettings(parent: Screen?, tab: String, section: String? = null): SoulConfigScreen =
            SoulConfigScreen(parent, if (section == null) "s:$tab" else "s:$tab:$section")

        private fun f(px: Float) = px * MsdfFont.LEGACY_EM

        private val F_CHIP_SMALL = f(9f)
        private val F_10_5 = f(10.5f)
        private val F_9_5 = f(9.5f)
        private val F_12 = f(12f)
        private val F_11_5 = f(11.5f)

        // Updater states (spec mocks/updater/mock.css: done, error, warning).
        private val UPD_GREEN = 0xFF4ADE80.toInt()
        private val UPD_RED = 0xFFE05252.toInt()
        private val UPD_ORANGE = 0xFFF0A640.toInt()
    }

    override val rowDescriptions = true

    override fun rowTag(opt: ConfigOption): String? = if (opt in ConfigRegistry.hudTagged) "HUD" else null

    // ---------------------------------------------------------------- state

    private var page = "start"
    private val tabOf = HashMap<String, String>()
    private var selected: String? = null
    private var settingsTab = "interface"
    private val advOpen = HashSet<String>()
    private val scrolls = HashMap<String, ScrollState>()
    private var revealTab = true
    private var tip = 0
    private var initialized = false

    private var peekSince = 0L
    private var peeking = false

    private var paletteOpen = false
    private val paletteField = SoulTextField().also { it.focused = true }
    private var paletteSel = 0
    private var paletteReveal = false
    private var paletteIndex: List<SearchItem> = emptyList()
    private var paletteGroups: List<SearchGroup> = emptyList()

    private var onboardingOpen = false
    private val picks = LinkedHashSet<String>()

    private var toast: String? = null
    private var toastUntil = 0L

    /** Jump highlight: a [FeatureCard], a [ConfigOption] or a Settings section id ("sec:<id>"). */
    private var flash: Any? = null
    private var flashAt = 0L
    private var flashReveal = false

    private var pressPin: String? = null
    private var pressY = 0
    private var pinDragging = false
    private val pinRows = ArrayList<Triple<String, Int, Int>>()

    private var versionClicks = 0
    private var lastVersionClickAt = 0L

    private var now = NowContext(null)
    private var nowGroups: List<NowGroup> = emptyList()

    private fun scroll(key: String) = scrolls.getOrPut(key) { ScrollState() }

    private val alwaysAdvanced: Boolean get() = cfg.menu.alwaysAdvanced
    /** Icons only: the player's choice, or forced while the card is too narrow for the full sidebar plus a list. */
    private var collapsed = false
    /** Icons only because the card is too narrow; the Collapse button hides then (it could not expand). */
    private var collapseForced = false

    override fun init() {
        if (initialized) return
        initialized = true
        tip = opens++ % TIPS.size
        if (!cfg.menu.onboardingDone) onboardingOpen = true
        target?.let { jump(it) }
    }

    // ---------------------------------------------------------------- navigation

    private fun openActivity(id: String, tab: String? = null, sel: String? = null) {
        page = id
        tabOf[id] = tab ?: RightNow.tabFor(id, ConfigRegistry.cards, now) ?: tabOf[id] ?: ConfigRegistry.tabsOf(id).firstOrNull()?.id ?: ""
        selected = sel
        revealTab = true
    }

    private fun openSettings(tab: String) {
        page = "settings"
        settingsTab = tab
        selected = null
        revealTab = true
    }

    /** `f:<card>` opens and highlights a card; `s:<tab>[:<section>]` a Settings tab (and section). */
    private fun jump(target: String) {
        val parts = target.split(":")
        if (parts[0] == "f") {
            val card = ConfigRegistry.card(parts.getOrNull(1) ?: return) ?: return
            openActivity(card.activity, card.tab, card.id)
            setFlash(card)
        } else if (parts[0] == "s") {
            val tab = parts.getOrNull(1) ?: return
            if (ConfigRegistry.visibleSettingsTabs().none { it.id == tab }) return
            openSettings(tab)
            parts.getOrNull(2)?.let { setFlash("sec:$it") }
        }
    }

    private fun setFlash(target: Any) {
        flash = target
        flashAt = System.currentTimeMillis()
        flashReveal = true
    }

    private fun showToast(text: String) {
        toast = text
        toastUntil = System.currentTimeMillis() + TOAST_MS
    }

    private fun open(screen: Screen) {
        commit()
        minecraft?.setScreenAndShow(screen)
    }

    // ---------------------------------------------------------------- frame

    override fun extractBlurredBackground(g: GuiGraphicsExtractor) {
        // Minecraft blurs once per frame; with the palette open that blur goes over the menu instead.
        if (!paletteOpen) super.extractBlurredBackground(g)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        scrollTargets.clear()
        pinRows.clear()

        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val realMx = (mouseX / s).toInt()
        val realMy = (mouseY / s).toInt()
        // Under the palette or the first-launch question nothing behind reacts to hover.
        val covered = paletteOpen || onboardingOpen
        val mx = if (covered) -10000 else realMx
        val my = if (covered) -10000 else realMy

        now = RightNow.live(parent)
        nowGroups = RightNow.groups(ConfigRegistry.cards, now)

        g.pose().pushMatrix()
        g.pose().scale(s, s)

        val cardW = minOf((uiW * CARD_FW).toInt(), CARD_MAX_W, uiW - CARD_MARGIN)
        val cardH = minOf((uiH * CARD_FH).toInt(), CARD_MAX_H, uiH - CARD_MARGIN)
        val x0 = (uiW - cardW) / 2
        val y0 = (uiH - cardH) / 2
        val x1 = x0 + cardW
        val y1 = y0 + cardH
        collapseForced = cardW < SIDEBAR_W + LIST_MIN_W
        collapsed = cfg.menu.sidebarCollapsed || collapseForced
        val sideW = if (collapsed) SIDEBAR_COLLAPSED_W else SIDEBAR_W
        val mainX0 = x0 + sideW
        val topY1 = y0 + TOP_NAV_H
        val footY0 = y1 - FOOTER_H
        val contentW = cardW - sideW

        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, x0, y0, x1, topY1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)

        // Body (clipped, under the update banner when there is one), then the chrome over its edges.
        val banner = updateBannerText(UpdateService.snapshot)
        val bannerH = if (banner == null) 0 else drawUpdateBanner(null, banner, mainX0, topY1, x1) + 10
        val bodyY0 = topY1 + bannerH
        if (page == "start") {
            drawStart(g, mx, my, mainX0, bodyY0, x1, footY0, contentW)
        } else if (page == "settings") {
            drawSettings(g, mx, my, mainX0, bodyY0, x1, footY0)
        } else {
            drawActivity(g, mx, my, mainX0, bodyY0, x1, footY0, contentW)
        }
        if (banner != null) drawUpdateBanner(g, banner, mainX0, topY1, x1)
        g.fillGradient(mainX0, topY1, x1, topY1 + 6, 0x50000000, 0x00000000)
        for (i in 0 until 6) g.fill(mainX0 + i, topY1, mainX0 + i + 1, footY0, ((0x40 * (6 - i) / 6) shl 24))
        drawTopBar(g, mx, my, mainX0, y0, x1, topY1)
        drawFooter(g, mx, my, mainX0, footY0, x1, y1)
        drawSidebar(g, mx, my, x0, y0, y1, topY1, realMx, realMy, covered)

        if (onboardingOpen) drawOnboarding(g, realMx, realMy, x0, y0, x1, y1)
        drawToast(g, x0, x1, y1)
        drawTooltip(g, uiW, uiH)

        if (paletteOpen) {
            g.pose().popMatrix()
            g.nextStratum()
            g.blurBeforeThisStratum()
            g.pose().pushMatrix()
            g.pose().scale(s, s)
            drawPalette(g, realMx, realMy, uiW, uiH, x0, y0, cardW, cardH)
        }

        drawColorPicker(g, uiW, uiH, realMx, realMy)
        drawDropdown(g, uiW, uiH, realMx, realMy)
        drawAlertModal(g, uiW, uiH, realMx, realMy)
        g.pose().popMatrix()
    }

    // ---------------------------------------------------------------- sidebar

    private fun drawSidebar(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, y1: Int, topY1: Int, realMx: Int, realMy: Int, covered: Boolean) {
        val nowMs = System.currentTimeMillis()
        // Collapsed: hovering floats it open over the content after PEEK_DELAY_MS (no flicker on pass-through).
        val hoverW = if (peeking) SIDEBAR_W else SIDEBAR_COLLAPSED_W
        val over = collapsed && !covered && realMx in x0..(x0 + hoverW) && realMy in y0..y1
        if (!over) {
            peekSince = 0L
            peeking = false
        } else if (!peeking) {
            if (peekSince == 0L) peekSince = nowMs
            if (nowMs - peekSince >= PEEK_DELAY_MS) peeking = true
        }
        val narrow = collapsed && !peeking
        val w = if (narrow) SIDEBAR_COLLAPSED_W else SIDEBAR_W
        val x1 = x0 + w

        if (collapsed && peeking) {
            // Floating over the content: a soft shadow to its right.
            for (i in 0 until 10) g.fill(x1 + i, y0 + 2, x1 + i + 1, y1 - 2, ((0x60 * (10 - i) / 10) shl 24))
            roundRect(g, x0, y0, x1, topY1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        }
        roundRect(g, x0, y0, x1, topY1, Theme.TOP_NAV, if (collapsed && peeking) SoulRenderPipelines.ROUND_TOP else SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x0, topY1, x1, y1, Theme.SIDEBAR, SoulRenderPipelines.ROUND_BL)
        if (collapsed && peeking) g.fill(x1 - 1, y0, x1, y1, Theme.SEPARATOR)

        // Brand: the blue flame, "Soul", version.
        drawIcon(g, "flame", x0 + 13, y0 + (TOP_NAV_H - 18) / 2, 18, -1)
        val dot = updateDot(UpdateService.snapshot)
        var dx = x0 + 13 + 18 - 3
        var dy = y0 + (TOP_NAV_H - 18) / 2 - 2
        if (!narrow) {
            drawTextV(g, "Soul", x0 + 13 + 18 + 7, y0, topY1, Theme.ACCENT, fTitle, MsdfFonts.menuSemibold)
            val ver = "v${Soul.version}"
            val vx = x1 - 12 - textWidth(ver, fCaption) - if (dot != null) 10 else 0
            drawTextV(g, ver, vx, y0 + 2, topY1 + 2, Theme.TEXT_DIM, fCaption)
            dx = x1 - 12 - 6
            dy = (y0 + topY1) / 2 - 2
        }
        // Restart pending (accent) or withdrawn (red); collapsed it sits on the flame.
        if (dot != null) {
            roundRect(g, dx, dy, dx + 6, dy + 6, dot.first, SoulRenderPipelines.ROUND_PILL)
            if (realMx in dx - 3..dx + 9 && realMy in dy - 3..dy + 9) tooltip(dx - 3, dy - 3, dx + 9, dy + 9, dot.second)
        }

        val rows = ArrayList<Triple<String, String, String>>() // id, name, icon; "-" = separator
        rows += Triple("start", "Start", "home")
        // Not a card tab: opens the Friends & Contests screen.
        rows += Triple("contests", "Friends & Contests", "trophy")
        rows += Triple("-", "", "")
        for (a in ConfigRegistry.activities) rows += Triple(a.id, a.name, a.icon)
        rows += Triple("-", "", "")
        rows += Triple("settings", "Settings", "flame_line")
        val navTop = topY1 + 10
        val collapseH = 28
        val navBottom = y1 - 6 - collapseH - 2
        val st = scroll("nav")
        val contentH = rows.sumOf { if (it.first == "-") 9 else 30 }
        st.content = contentH
        st.view = navBottom - navTop
        st.clamp()
        val here = nowGroups.flatMap { it.cards }.map { it.activity }.toSet()
        beginClip(g, x0, navTop, x1, navBottom)
        var y = navTop - st.pos.roundToInt()
        for ((id, name, icon) in rows) {
            if (id == "-") {
                g.fill(x0 + 10, y + 4, x1 - 10, y + 5, Theme.SEPARATOR)
                y += 9
                continue
            }
            val active = page == id
            val hasNew = if (id == "settings") settingsHasNew() else ConfigRegistry.cards.any { it.activity == id && MenuState.isNew("f:${it.id}") }
            val dot = id in here
            navItem(g, mx, my, x0 + 6, y, x1 - 6, y + 28, name, icon, active, narrow, hasNew, if (dot) RightNow.why(id, null, nowGroups) else null, navTop, navBottom, soft = false) {
                if (id == "contests") {
                    onAction("openContests")
                } else if (id == "start" || id == "settings") {
                    page = id
                    selected = null
                    revealTab = true
                } else {
                    openActivity(id)
                }
            }
            y += 30
        }
        endClip(g)
        drawScrollbar(g, mx, my, st, x0, navTop, x1, navBottom)
        val cy0 = y1 - 6 - collapseH
        if (!collapseForced) navItem(g, mx, my, x0 + 6, cy0, x1 - 6, cy0 + collapseH, if (collapsed) "Expand sidebar" else "Collapse", if (collapsed) "expand" else "collapse", false, narrow, false, null, y0, y1, soft = true) {
            MenuState.set { copy(sidebarCollapsed = !sidebarCollapsed) }
            peeking = false
            peekSince = 0L
        }
    }

    private fun navItem(
        g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, name: String, icon: String,
        active: Boolean, narrow: Boolean, hasNew: Boolean, hereWhy: String?, clipY0: Int, clipY1: Int, soft: Boolean, onClick: () -> Unit,
    ) {
        val hovered = mx in x0..x1 && my in y0..y1 && my in clipY0..clipY1
        if (active) roundRect(g, x0, y0, x1, y1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
        else if (hovered) roundRect(g, x0, y0, x1, y1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        val color = if (active) 0xFFFFFFFF.toInt() else if (hovered) Theme.TEXT else if (soft) Theme.TEXT_FAINT else Theme.TEXT_DIM
        val ix = x0 + 8
        drawIcon(g, icon, ix, (y0 + y1) / 2 - 8, 16, color)
        if (narrow) {
            // Collapsed: the NEW dot on the icon's top right, the pin at its bottom left.
            if (hasNew) {
                roundRect(g, ix + 12 - 2, y0 + 4 - 2, ix + 12 + 8, y0 + 4 + 8, if (active) Theme.ACCENT else Theme.SIDEBAR, SoulRenderPipelines.ROUND_PILL)
                roundRect(g, ix + 12 - 1, y0 + 4 - 1, ix + 12 + 7, y0 + 4 + 7, Theme.NEW_DOT, SoulRenderPipelines.ROUND_PILL)
            }
            if (hereWhy != null) herePin(g, mx, my, x0 + 3, y0 + 15, active, hereWhy)
        } else {
            drawTextV(g, name, ix + 16 + 9, y0, y1, color, fBody, if (soft) MsdfFonts.menuRegular else MsdfFonts.menuSemibold)
            var right = x1 - 8
            if (hereWhy != null) {
                herePin(g, mx, my, right - 9, (y0 + y1) / 2 - 4, active, hereWhy)
                right -= 9 + 12
            }
            if (hasNew) newChip(g, right - chipWidth("NEW"), (y0 + y1) / 2, active)
        }
        if (my in clipY0..clipY1) clippedRegion(x0, y0, x1, y1, intArrayOf(x0, clipY0, x1, clipY1), onClick)
    }

    /** The one "right now" marker (sidebar row, tab): a location pin; its tooltip names the contexts. */
    private fun herePin(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, y: Int, onAccent: Boolean, why: String) {
        drawIcon(g, "pin", x, y, 9, if (onAccent) 0xFFFFFFFF.toInt() else Theme.ACCENT_LIGHT)
        if (mx in x - 2..x + 11 && my in y - 2..y + 11) tooltip(x - 2, y - 2, x + 11, y + 11, "Right now: $why")
    }

    private fun newChip(g: GuiGraphicsExtractor, x: Int, cy: Int, onAccent: Boolean = false): Int =
        if (onAccent) drawChip(g, x, cy, "NEW", 0xFFFFFFFF.toInt(), 0x38FFFFFF) else drawChip(g, x, cy, "NEW", Theme.ACCENT_LIGHT, Theme.NEW_BG)

    private fun settingsHasNew() = ConfigRegistry.visibleSettingsTabs().any { MenuState.isNew("s:${it.id}") }

    // ---------------------------------------------------------------- top bar

    private fun drawTopBar(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        // Right: global Advanced switch, then search.
        val kbd = "Ctrl K"
        val searchW = 7 + 12 + 6 + kbdWidth(kbd) + 5
        val sx1 = x1 - 12
        val sx0 = sx1 - searchW
        val cy = (y0 + y1) / 2
        val sHover = mx in sx0..sx1 && my in cy - 12..cy + 12
        roundRect(g, sx0, cy - 12, sx1, cy + 12, if (sHover) Theme.ACCENT else 0xFF333333.toInt(), SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, sx0 + 1, cy - 11, sx1 - 1, cy + 11, 0xFF222222.toInt(), SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, "search", sx0 + 7, cy - 6, 12, if (sHover) 0xFFFFFFFF.toInt() else Theme.CHIP_TEXT)
        drawKbd(g, sx0 + 7 + 12 + 6, cy, kbd)
        region(sx0, cy - 12, sx1, cy + 12) { openPalette() }

        val advLabel = "Advanced"
        val tw = 26
        val ax1 = sx0 - 10
        val ax0 = ax1 - tw - 6 - textWidth(advLabel, fBody)
        val aHover = mx in ax0..ax1 && my in cy - 10..cy + 10
        drawTextV(g, advLabel, ax0, y0, y1, if (alwaysAdvanced || aHover) Theme.TEXT else Theme.TEXT_DIM, fBody)
        drawToggle(g, ax1 - tw, cy - 7, alwaysAdvanced, tw, 14) { MenuState.set { copy(alwaysAdvanced = !alwaysAdvanced) } }
        region(ax0, cy - 10, ax1 - tw, cy + 10) { MenuState.set { copy(alwaysAdvanced = !alwaysAdvanced) } }

        val left = x0 + 14
        val right = ax0 - 10
        if (page == "start") {
            drawTextV(g, "Start", left, y0, y1, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
        } else if (page == "settings") {
            val tabs = ConfigRegistry.visibleSettingsTabs()
            if (tabs.none { it.id == settingsTab }) settingsTab = tabs.first().id
            MenuState.markSeen("s:$settingsTab")
            drawTabs(g, mx, my, left, right, y0, y1, tabs.map { TabSpec(it.id, it.name, MenuState.isNew("s:${it.id}"), null) }, settingsTab, "tabs:settings") {
                settingsTab = it
                revealTab = true
            }
        } else {
            val tabs = ConfigRegistry.tabsOf(page)
            val here = nowGroups.flatMap { it.cards }.filter { it.activity == page }.map { it.tab }.toSet()
            drawTabs(
                g, mx, my, left, right, y0, y1,
                tabs.map { t -> TabSpec(t.id, t.name, ConfigRegistry.cardsIn(page, t.id).any { MenuState.isNew("f:${it.id}") }, if (t.id in here) RightNow.why(page, t.id, nowGroups) else null) },
                tabOf[page] ?: "", "tabs:$page",
            ) {
                tabOf[page] = it
                selected = null
                revealTab = true
            }
        }
    }

    private class TabSpec(val id: String, val name: String, val isNew: Boolean, val hereWhy: String?)

    /** Tabs on top: hidden when there are none, scroll sideways with fades, arrows and a Soul scrollbar when long. */
    private fun drawTabs(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, x1: Int, y0: Int, y1: Int, tabs: List<TabSpec>, current: String, key: String, onPick: (String) -> Unit) {
        if (tabs.isEmpty() || x1 - x0 < 40) return
        val semi = MsdfFonts.menuSemibold
        val widths = tabs.map { t -> 20 + textWidth(t.name, fBody, semi) + (if (t.isNew) 5 + chipWidth("NEW") else 0) + (if (t.hereWhy != null) 5 + 9 else 0) }
        val total = widths.sum() + 4 * (tabs.size - 1)
        val st = scroll(key)
        st.content = total
        st.view = x1 - x0
        val ty0 = (y0 + y1) / 2 - TAB_H / 2
        val ty1 = ty0 + TAB_H
        if (revealTab) {
            revealTab = false
            var x = 0
            for ((i, t) in tabs.withIndex()) {
                if (t.id == current) st.reveal(x, x + widths[i], 44)
                x += widths[i] + 4
            }
        }
        st.clamp()
        // MSDF text draws above later fills, so the fades cannot hide labels: clip the tabs clear of the arrows instead.
        val leftOpen = st.max > 1 && st.pos > 1
        val rightOpen = st.max > 1 && st.pos < st.max - 1
        beginClip(g, if (leftOpen) x0 + 22 else x0, y0, if (rightOpen) x1 - 22 else x1, y1)
        var x = x0 - st.pos.roundToInt()
        val viewport = intArrayOf(x0, ty0, x1, ty1)
        for ((i, t) in tabs.withIndex()) {
            val tx0 = x
            val tx1 = x + widths[i]
            val on = t.id == current
            val hovered = mx in maxOf(tx0, x0)..minOf(tx1, x1) && my in ty0..ty1
            if (on) roundRect(g, tx0, ty0, tx1, ty1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            else if (hovered) roundRect(g, tx0, ty0, tx1, ty1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            val color = if (on) 0xFFFFFFFF.toInt() else if (hovered) Theme.TEXT else Theme.TEXT_DIM
            var cx = tx0 + 10
            drawTextV(g, t.name, cx, ty0, ty1, color, fBody, semi)
            cx += textWidth(t.name, fBody, semi) + 5
            if (t.isNew) cx += newChip(g, cx, (ty0 + ty1) / 2, on) + 5
            if (t.hereWhy != null) herePin(g, mx, my, cx, (ty0 + ty1) / 2 - 4, on, t.hereWhy)
            clippedRegion(tx0, ty0, tx1, ty1, viewport) { onPick(t.id) }
            x = tx1 + 4
        }
        endClip(g)
        // Fades + arrows only while there is more that way.
        if (leftOpen) {
            fadeH(g, x0 + 18, ty0 - 2, 22, ty1 + 2, toRight = false)
            tabArrow(g, mx, my, x0, ty0, "left") { st.scrollBy(-st.view * 0.6f) }
        }
        if (rightOpen) {
            fadeH(g, x1 - 44, ty0 - 2, 22, ty1 + 2, toRight = true)
            tabArrow(g, mx, my, x1 - 18, ty0, "right") { st.scrollBy(st.view * 0.6f) }
        }
        drawScrollbar(g, mx, my, st, x0, ty0, x1, ty1, horizontal = true)
    }

    /** A 40 px fade from the top bar's color into transparent ([toRight] = opaque on the right). */
    private fun fadeH(g: GuiGraphicsExtractor, x: Int, y0: Int, w: Int, y1: Int, toRight: Boolean) {
        for (i in 0 until w) {
            val t = i.toFloat() / w
            val k = if (toRight) t else 1f - t
            val a = if (k >= 0.55f) 255 else (k / 0.55f * 255).toInt()
            g.fill(x + i, y0, x + i + 1, y1, (a shl 24) or (Theme.TOP_NAV and 0xFFFFFF))
        }
    }

    private fun tabArrow(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, y: Int, icon: String, onClick: () -> Unit) {
        val hovered = mx in x..x + 18 && my in y..y + TAB_H
        // Idle: the fade is the backdrop (an idle box left a dark 1 px edge on the fade).
        if (hovered) roundRect(g, x, y, x + 18, y + TAB_H, 0xFF242424.toInt(), SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, icon, x + 4, y + TAB_H / 2 - 5, 10, if (hovered) 0xFFFFFFFF.toInt() else Theme.CHIP_TEXT)
        region(x, y, x + 18, y + TAB_H, onClick)
    }

    private fun kbdWidth(text: String) = textWidth(text, F_CHIP_SMALL) + 6

    private fun drawKbd(g: GuiGraphicsExtractor, x: Int, cy: Int, text: String): Int {
        val w = kbdWidth(text)
        roundRect(g, x, cy - 6, x + w, cy + 6, Theme.OUTLINE, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x + 1, cy - 5, x + w - 1, cy + 5, 0xFF1A1A1A.toInt(), SoulRenderPipelines.ROUND_SMALL)
        com.soulreturns.config.gui.render.SoulText.drawCenteredOn(g, text, x + w / 2, cy, F_CHIP_SMALL, Theme.ICON, forceCustom = true, scissor = currentClip)
        return w
    }

    // ---------------------------------------------------------------- footer

    private fun drawFooter(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        roundRect(g, x0, y0, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_BR)
        g.fill(x0, y0, x1, y0 + 1, Theme.SEPARATOR)
        val by0 = y0 + 5
        val by1 = y1 - 5
        val moveW = (textWidth("Move GUI", fBody) + 24).coerceAtLeast(56)
        outlineButton(g, mx, my, x0 + 8, by0, x0 + 8 + moveW, by1, "Move GUI") { open(HudMoveScreen(this)) }

        val doneX0 = x1 - 8 - 56
        drawButton(g, mx, my, doneX0, by0, x1 - 8, by1, "Done") {
            commit()
            applyPending()
            onClose()
        }
        var rightEdge = doneX0 - 8
        if (hasPending()) {
            drawButton(g, mx, my, rightEdge - 56, by0, rightEdge, by1, "Apply", accent = true) {
                commit()
                applyPending()
            }
            rightEdge -= 56 + 8
        }
        // The feedback line: muted, one line, cut short when narrow; its two links open in the browser.
        val segments = listOf(
            "Please share problems and feedback on " to null,
            "Discord" to ConfigRegistry.DISCORD_URL,
            " or " to null,
            "GitHub issues" to ConfigRegistry.GITHUB_ISSUES_URL,
        )
        var x = x0 + 8 + moveW + 12
        val limit = rightEdge - 8
        for ((text, url) in segments) {
            if (x >= limit) break
            val w = textWidth(text, fCaption)
            val shown = if (x + w <= limit) text else ellipsize(text, limit - x, fCaption)
            val sw = textWidth(shown, fCaption)
            val hovered = url != null && mx in x..x + sw && my in by0..by1
            drawTextV(g, shown, x, y0, y1, if (url == null) Theme.TEXT_FAINT else if (hovered) Theme.TEXT else Theme.TEXT_DIM, fCaption)
            if (url != null) {
                val uy = (y0 + y1) / 2 + 5
                g.fill(x, uy, x + sw, uy + 1, if (hovered) Theme.ACCENT else 0xFF444444.toInt())
                region(x, by0, x + sw, by1) { Util.getPlatform().openUri(url) }
            }
            x += sw
            if (shown != text) break
        }
    }

    /** Outline button (footer Move GUI, the tip card's action): no fill, 1 px border. */
    private fun outlineButton(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, label: String, onClick: () -> Unit) {
        val hovered = mx in x0..x1 && my in y0..y1
        roundRect(g, x0, y0, x1, y1, if (hovered) 0xFF555555.toInt() else Theme.OUTLINE, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_SMALL)
        com.soulreturns.config.gui.render.SoulText.drawCenteredOn(g, label, (x0 + x1) / 2, (y0 + y1) / 2, fBody, if (hovered) Theme.TEXT else Theme.CHIP_TEXT, forceCustom = true, scissor = currentClip)
        region(x0, y0, x1, y1, onClick)
    }

    // ---------------------------------------------------------------- feature cards

    private fun isOn(card: FeatureCard) = card.master?.get() == true

    /** A full card: icon, name + chips, one line, pin (on hover / while pinned), switch, chevron. */
    private fun drawCard(g: GuiGraphicsExtractor, mx: Int, my: Int, card: FeatureCard, x0: Int, y0: Int, x1: Int, viewport: IntArray, sel: Boolean, onClick: () -> Unit): Int {
        val h = 46
        val y1 = y0 + h
        if (y1 < viewport[1] || y0 > viewport[3]) return h
        val hovered = mx in x0..x1 && my in y0..y1 && my in viewport[1]..viewport[3]
        val on = isOn(card)
        if (sel) {
            roundRect(g, x0, y0, x1, y1, Theme.ACCENT, SoulRenderPipelines.ROUND_MEDIUM)
            roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, Theme.CARD_SELECTED, SoulRenderPipelines.ROUND_MEDIUM)
        } else {
            roundRect(g, x0, y0, x1, y1, if (hovered) 0xFF242424.toInt() else Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        }
        clippedRegion(x0, y0, x1, y1, viewport, onClick)
        iconBox(g, card.icon, x0 + 8, (y0 + y1) / 2 - 13, 26, 15, on)
        // Right side, right to left: chevron, switch (or Open), pin.
        var right = x1 - 10
        caretRight(g, right - 4, (y0 + y1) / 2, if (sel) Theme.ACCENT else Theme.TEXT_FAINT)
        right -= 6 + 2 + 10
        right = drawCardControl(g, mx, my, card, right, (y0 + y1) / 2, viewport, toggleW, toggleH) - 10
        if (hovered || card.id in cfg.menu.pins) {
            pinButton(g, mx, my, card, right - 20, (y0 + y1) / 2 - 10, viewport)
        }
        right -= 20 + 10
        val tx = x0 + 8 + 26 + 10
        drawCardTitle(g, card, tx, y0 + 7 + 7, right - tx)
        val blocked = card.blockedBy()
        val line = if (blocked != null && !on) blocked else card.line
        drawText(g, ellipsize(line, right - tx, fCaption), tx, y0 + 7 + 17, Theme.TEXT_DIM, fCaption)
        flashOverlay(g, card, x0, y0, x1, y1)
        return h
    }

    /** The card's switch (or "Open"); returns its left edge. */
    private fun drawCardControl(g: GuiGraphicsExtractor, mx: Int, my: Int, card: FeatureCard, right: Int, cy: Int, viewport: IntArray, tw: Int, th: Int): Int {
        val master = card.master
        if (master == null && card.openAction == null) return right
        if (master == null) {
            val bw = (textWidth("Open", fBody) + 20).coerceAtLeast(48)
            val bx0 = right - bw
            suppressRegions = true
            drawButton(g, mx, my, bx0, cy - 9, right, cy + 9, "Open") {}
            suppressRegions = false
            clippedRegion(bx0, cy - 9, right, cy + 9, viewport) { card.openAction?.let { onAction(it) } }
            return bx0
        }
        val on = master.get()
        val enabled = on || card.blockedBy() == null
        suppressRegions = true
        drawToggle(g, right - tw, cy - th / 2, on, tw, th, enabled) {}
        suppressRegions = false
        if (enabled) clippedRegion(right - tw, cy - th / 2, right, cy + th / 2, viewport) { master.toggle() }
        return right - tw
    }

    private fun pinButton(g: GuiGraphicsExtractor, mx: Int, my: Int, card: FeatureCard, x: Int, y: Int, viewport: IntArray) {
        val pinned = card.id in cfg.menu.pins
        val hovered = mx in x..x + 20 && my in y..y + 20
        if (hovered) roundRect(g, x, y, x + 20, y + 20, Theme.SEPARATOR, SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, if (pinned) "pushpin_on" else "pushpin", x + 4, y + 4, 12, if (pinned) Theme.ACCENT_LIGHT else if (hovered) Theme.TEXT else 0xFF666666.toInt())
        clippedRegion(x, y, x + 20, y + 20, viewport) {
            MenuState.togglePin(card.id)
            showToast(if (pinned) "${card.name} unpinned from Start" else "${card.name} pinned to Start")
        }
    }

    /** Name in semibold, then NEW / BETA / HUD chips on the cap height. */
    private fun drawCardTitle(g: GuiGraphicsExtractor, card: FeatureCard, x: Int, cy: Int, maxW: Int, extra: String? = null) {
        val semi = MsdfFonts.menuSemibold
        val chips = ArrayList<Triple<String, Int, Int>>()
        if (MenuState.isNew("f:${card.id}")) chips += Triple("NEW", Theme.ACCENT_LIGHT, Theme.NEW_BG)
        if (card.beta) chips += Triple("BETA", Theme.BETA, Theme.BETA_BG)
        if (card.hud) chips += Triple("HUD", Theme.TEXT_DIM, CHIP_HUD_BG)
        val chipsW = chips.sumOf { chipWidth(it.first) + 6 }
        val name = ellipsize(card.name, (maxW - chipsW).coerceAtLeast(20), fBody, semi)
        drawTextV(g, name, x, cy - 7, cy + 7, Theme.TEXT, fBody, semi)
        var cx = x + textWidth(name, fBody, semi) + 6
        for ((t, fg, bg) in chips) cx += drawChip(g, cx, cy, t, fg, bg) + 6
        if (extra != null) drawTextV(g, ellipsize(extra, x + maxW - cx, fCaption), cx + 2, cy - 7, cy + 7, Theme.TEXT_FAINT, fCaption)
    }

    private fun iconBox(g: GuiGraphicsExtractor, icon: String, x: Int, y: Int, size: Int, glyph: Int, on: Boolean) {
        roundRect(g, x, y, x + size, y + size, if (on) Theme.ACCENT_TINT else Theme.ICON_BOX, SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, icon, x + (size - glyph) / 2, y + (size - glyph) / 2, glyph, if (on) Theme.ACCENT_LIGHT else Theme.ICON)
    }

    private fun flashOverlay(g: GuiGraphicsExtractor, target: Any, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (flash !== target && flash != target) return
        val t = (System.currentTimeMillis() - flashAt).toFloat() / FLASH_MS
        if (t >= 1f) {
            flash = null
            return
        }
        val k = if (t < 0.45f) 1f else 1f - (t - 0.45f) / 0.55f
        val ring = ((0x4D * k).toInt() shl 24) or 0x3B82F6
        val edge = ((0xFF * k).toInt() shl 24) or 0x3B82F6
        val fillC = ((0x2E * k).toInt() shl 24) or 0x3B82F6
        // Drawn over the content: only the 0x2E tint covers it; edge and ring are strokes.
        frame(g, x0 - 3, y0 - 3, x1 + 3, y1 + 3, 2, ring)
        frame(g, x0, y0, x1, y1, 1, edge)
        roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, fillC, SoulRenderPipelines.ROUND_MEDIUM)
    }

    private fun frame(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, w: Int, color: Int) {
        g.fill(x0, y0, x1, y0 + w, color)
        g.fill(x0, y1 - w, x1, y1, color)
        g.fill(x0, y0 + w, x0 + w, y1 - w, color)
        g.fill(x1 - w, y0 + w, x1, y1 - w, color)
    }

    /** Scrolls [st] so a flash target at content offset [top]..[bottom] sits in the middle, once. */
    private fun revealFlash(target: Any, st: ScrollState, top: Int, bottom: Int) {
        if (!flashReveal || (flash !== target && flash != target)) return
        flashReveal = false
        st.pos = (top - (st.view - (bottom - top)) / 2).toFloat()
        st.clamp()
    }

    // ---------------------------------------------------------------- activity page

    private fun drawActivity(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, contentW: Int) {
        val tabs = ConfigRegistry.tabsOf(page)
        var tab = tabOf[page]
        if (tabs.none { it.id == tab }) {
            tab = tabs.firstOrNull()?.id ?: ""
            tabOf[page] = tab
        }
        val list = ConfigRegistry.cardsIn(page, tab ?: "")
        val sel = list.firstOrNull { it.id == selected }
        if (sel != null) MenuState.markSeen("f:${sel.id}") // opening its panel counts as seen
        val panelW = (contentW * PANEL_FRACTION).roundToInt().coerceIn(PANEL_MIN_W, PANEL_MAX_W)
        val floats = contentW - panelW < LIST_MIN_W
        val listX1 = if (floats) x1 else x1 - panelW

        val st = scroll("list:$page:$tab")
        st.view = y1 - y0
        val viewport = intArrayOf(x0, y0, listX1, y1)
        beginClip(g, x0, y0, listX1, y1)
        var y = y0 + 12 - st.pos.roundToInt()
        val top = y
        for (c in list) {
            revealFlash(c, st, y - top + 12, y - top + 12 + 46)
            y += drawCard(g, mx, my, c, x0 + 16, y, listX1 - 16, viewport, c === sel) { selected = c.id } + 6
        }
        endClip(g)
        st.content = y - top + 12 - 6 + 16
        drawScrollbar(g, mx, my, st, x0, y0, listX1, y1)

        if (!floats || sel != null) drawPanel(g, mx, my, sel, x1 - panelW, y0, x1, y1, floats)
    }

    // ---------------------------------------------------------------- side panel

    private fun drawPanel(g: GuiGraphicsExtractor, mx: Int, my: Int, card: FeatureCard?, x0: Int, y0: Int, x1: Int, y1: Int, floating: Boolean) {
        if (floating) for (i in 0 until 12) g.fill(x0 - 12 + i, y0, x0 - 11 + i, y1, ((0x8C * (i + 1) / 12) shl 24))
        roundRect(g, x0, y0, x1, y1, Theme.SIDEBAR, SoulRenderPipelines.ROUND_SMALL)
        g.fill(x0, y0, x0 + 1, y1, Theme.SEPARATOR)
        // Swallow clicks on the panel's background so a floating panel never clicks through to the list.
        region(x0, y0, x1, y1) {}
        if (card == null) {
            val cy = (y0 + y1) / 2
            drawIcon(g, "sliders", (x0 + x1) / 2 - 11, cy - 30, 22, 0xFF333333.toInt())
            val semi = MsdfFonts.menuSemibold
            var ly = cy
            for (line in wrapText("Click a feature to see its settings", x1 - x0 - 48, fBody)) {
                drawTextCentered(g, line, (x0 + x1) / 2, ly - 6, Theme.TEXT_DIM, fBody, semi)
                ly += 14
            }
            for (line in wrapText("Only the switch on a card turns it on or off.", x1 - x0 - 48, fCaption)) {
                drawTextCentered(g, line, (x0 + x1) / 2, ly - 4, Theme.TEXT_FAINT, fCaption)
                ly += 13
            }
            return
        }
        val st = scroll("panel:${card.id}")
        st.view = y1 - y0
        val viewport = intArrayOf(x0, y0, x1, y1)
        val ix0 = x0 + 14
        val ix1 = x1 - 14
        beginClip(g, x0, y0, x1, y1)
        var y = y0 + 14 - st.pos.roundToInt()
        val top = y

        // Header: icon, name + path, pin, switch, close.
        val on = isOn(card)
        iconBox(g, card.icon, ix0, y, 30, 17, on)
        var right = ix1
        val closeHover = mx in right - 22..right && my in y + 4..y + 26
        if (closeHover) roundRect(g, right - 22, y + 4, right, y + 26, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, "close", right - 18, y + 8, 14, if (closeHover) Theme.TEXT else Theme.TEXT_DIM)
        clippedRegion(right - 22, y + 4, right, y + 26, viewport) { selected = null }
        right -= 22 + 10
        right = drawCardControl(g, mx, my, card, right, y + 15, viewport, toggleW, toggleH) - 10
        pinButton(g, mx, my, card, right - 20, y + 5, viewport)
        right -= 20 + 10
        val tx = ix0 + 30 + 10
        drawTextV(g, ellipsize(card.name, right - tx, fHeading, MsdfFonts.menuSemibold), tx, y + 1, y + 17, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
        drawTextV(g, ellipsize(ConfigRegistry.pathOf(card), right - tx, fCaption), tx, y + 17, y + 29, Theme.TEXT_FAINT, fCaption)
        y += 30 + 9

        for (line in wrapText(card.description, ix1 - ix0, fCaption)) {
            drawTextV(g, line, ix0, y, y + 13, Theme.TEXT_DIM, fCaption)
            y += 13
        }
        y += 8

        // When it matters: its activities (lit while detected), its areas (lit while there).
        val chips = card.contexts.map { (Detector.of(it)?.label ?: it) to (it in now.active) } +
            card.where.map { RightNow.areaLabel(it) to (it == now.area || it == now.subLocation) }
        if (chips.isNotEmpty()) {
            y -= 2
            var cx = ix0
            for ((label, lit) in chips) {
                val w = textWidth(label, F_9_5) + 14
                if (cx + w > ix1 && cx > ix0) {
                    cx = ix0
                    y += 19
                }
                roundRect(g, cx, y, cx + w, y + 15, if (lit) Theme.CHIP_HERE else Theme.CHIP, SoulRenderPipelines.ROUND_PILL)
                drawTextV(g, label, cx + 7, y, y + 15, if (lit) Theme.CHIP_HERE_TEXT else Theme.CHIP_TEXT, F_9_5)
                cx += w + 4
            }
            y += 15 + 10
        }

        val simple = card.simple.filter { it.visible() }
        val adv = card.advanced.filter { it.visible() }
        val blocked = card.blockedBy()
        val master = card.master
        if (blocked != null && !on) {
            y = offNote(g, mx, my, ix0, y, ix1, viewport, "Needs Clean up inventory on with the crafting grid, recipe book and off-hand slot hidden.", "Hide all three") {
                com.soulreturns.config.ConfigManager.update { copy(general = general.copy(cleanUpInventory = true, hideCraftingGrid = true, hideRecipeBook = true, hideOffhandSlot = true)) }
                showToast("Clean up inventory on: crafting grid, recipe book and off-hand slot hidden")
            }
        } else if (master != null && !on && (simple.isNotEmpty() || adv.isNotEmpty())) {
            y = offNote(g, mx, my, ix0, y, ix1, viewport, "Off while ${card.name} is off. These keep their values and apply once you switch it on.", "Turn on") { master.set(true) }
        }
        val dim = master != null && !on && card.openAction == null
        if (simple.isNotEmpty()) {
            y = optionSection(g, mx, my, simple, ix0, y, ix1, viewport, st, top, inset = 2, dim = dim)
        } else if (adv.isEmpty()) {
            drawTextV(g, "Nothing to set: the switch is all there is.", ix0 + 2, y, y + 28, Theme.TEXT_FAINT, fCaption)
            y += 28
        }
        y = advancedFold(g, mx, my, card.id, adv, ix0, y, ix1, viewport, st, top, inset = 2, dim = dim)
        endClip(g)
        st.content = y - top + 14 + 18
        drawScrollbar(g, mx, my, st, x0, y0, x1, y1)
    }

    private fun offNote(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y: Int, x1: Int, viewport: IntArray, text: String, button: String, onClick: () -> Unit): Int {
        val bw = (textWidth(button, fBody) + 16).coerceAtLeast(56)
        val lines = wrapText(text, x1 - x0 - 9 - bw - 14, fCaption)
        val h = maxOf(28, lines.size * 12 + 10)
        roundRect(g, x0, y, x1, y + h, 0x99242424.toInt(), SoulRenderPipelines.ROUND_SMALL)
        var ly = y + (h - lines.size * 12) / 2
        for (line in lines) {
            drawTextV(g, line, x0 + 9, ly, ly + 12, Theme.TEXT_DIM, fCaption)
            ly += 12
        }
        val cy = y + h / 2
        suppressRegions = true
        drawButton(g, mx, my, x1 - 6 - bw, cy - 9, x1 - 6, cy + 9, button) {}
        suppressRegions = false
        clippedRegion(x1 - 6 - bw, cy - 9, x1 - 6, cy + 9, viewport, onClick)
        return y + h + 8
    }

    /** A section card of option rows; returns the y below it. [dim] reads it as inactive (still editable). */
    private fun optionSection(
        g: GuiGraphicsExtractor, mx: Int, my: Int, opts: List<ConfigOption>, x0: Int, y: Int, x1: Int, viewport: IntArray,
        st: ScrollState, contentTop: Int, inset: Int, dim: Boolean = false, advanced: Boolean = false,
    ): Int {
        val rx0 = x0 + inset
        val rx1 = x1 - inset
        val heights = opts.map { optionRowHeight(it, rx0, rx1, ROW_H) }
        val h = heights.sum() + 8
        roundRect(g, x0, y, x1, y + h, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        if (advanced) g.fill(x0, y + 3, x0 + 2, y + h - 3, 0xFF333333.toInt())
        var ry = y + 4
        for ((i, opt) in opts.withIndex()) {
            val rh = heights[i]
            revealFlash(opt, st, ry - contentTop, ry - contentTop + rh)
            drawOptionRow(g, mx, my, rx0, ry, rx1, ry + rh, opt, viewport[1], viewport[3])
            flashOverlay(g, opt, rx0, ry, rx1, ry + rh)
            ry += rh
        }
        if (dim) {
            val hovered = mx in x0..x1 && my in y..y + h
            roundRect(g, x0, y, x1, y + h, if (hovered) 0x33161616 else 0x80161616.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        }
        return y + h
    }

    /** "› Advanced · n more settings" and, when open, its section. */
    private fun advancedFold(
        g: GuiGraphicsExtractor, mx: Int, my: Int, id: String, adv: List<ConfigOption>, x0: Int, y: Int, x1: Int, viewport: IntArray,
        st: ScrollState, contentTop: Int, inset: Int, dim: Boolean = false,
    ): Int {
        if (adv.isEmpty()) return y
        val open = alwaysAdvanced || id in advOpen
        val fy = y + 8
        val hovered = !alwaysAdvanced && mx in x0..x1 && my in fy..fy + 28
        roundRect(g, x0, fy, x1, fy + 28, if (hovered) 0xFF2C2C2C.toInt() else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_MEDIUM)
        if (open) caret(g, x0 + 12, fy + 13) else caretRight(g, x0 + 12, fy + 14, Theme.CHIP_TEXT)
        drawTextV(g, "Advanced", x0 + 22, fy, fy + 28, Theme.TEXT, fBody)
        val more = "${adv.size} more setting${if (adv.size == 1) "" else "s"}"
        drawTextV(g, more, x1 - 10 - textWidth(more, fCaption), fy, fy + 28, Theme.TEXT_DIM, fCaption)
        if (!alwaysAdvanced) clippedRegion(x0, fy, x1, fy + 28, viewport) { if (!advOpen.remove(id)) advOpen.add(id) }
        else if (mx in x0..x1 && my in fy..fy + 28) tooltip(x0, fy, x1, fy + 28, "Advanced is on for everything (top right)")
        var out = fy + 28
        if (open) out = optionSection(g, mx, my, adv, x0, out + 4, x1, viewport, st, contentTop, inset, dim, advanced = true)
        return out
    }

    // ---------------------------------------------------------------- Settings page

    private fun drawSettings(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val tab = ConfigRegistry.visibleSettingsTabs().firstOrNull { it.id == settingsTab } ?: return
        val st = scroll("settings:${tab.id}")
        st.view = y1 - y0
        val viewport = intArrayOf(x0, y0, x1, y1)
        beginClip(g, x0, y0, x1, y1)
        val bx0 = x0 + 18
        val bx1 = minOf(x1 - 18, bx0 + 580)
        var y = y0 + 12 - st.pos.roundToInt()
        val top = y
        if (tab.id == "about") y = drawAbout(g, mx, my, bx0, y, bx1, viewport) + 12
        for (sec in tab.sections) y = settingsBlock(g, mx, my, sec, bx0, y, bx1, viewport, st, top) + 12
        endClip(g)
        st.content = y - top + 12 + 6
        drawScrollbar(g, mx, my, st, x0, y0, x1, y1)
    }

    private fun settingsBlock(g: GuiGraphicsExtractor, mx: Int, my: Int, sec: SettingsSection, x0: Int, y: Int, x1: Int, viewport: IntArray, st: ScrollState, top: Int): Int {
        val simple = sec.simple.filter { it.visible() }
        val adv = sec.advanced.filter { it.visible() }
        val key = "sec:${sec.id}"
        val start = y
        var yy = y + 2
        drawTextV(g, sec.title, x0 + 4, yy, yy + 14, Theme.TEXT_DIM, fBody)
        yy += 14
        sec.subtitle?.let { sub ->
            for (line in wrapText(sub, x1 - x0 - 8, fCaption)) {
                drawTextV(g, line, x0 + 4, yy, yy + 12, Theme.TEXT_FAINT, fCaption)
                yy += 12
            }
        }
        yy += 6
        val bodyTop = yy
        yy = optionSection(g, mx, my, simple, x0, yy, x1, viewport, st, top, inset = 8)
        yy = advancedFold(g, mx, my, "set:${sec.id}", adv, x0, yy, x1, viewport, st, top, inset = 8)
        revealFlash(key, st, start - top, yy - top)
        flashOverlay(g, key, x0, bodyTop, x1, bodyTop + (yy - bodyTop).coerceAtMost(ROW_H * 2 + 8))
        return yy
    }

    private fun drawAbout(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y: Int, x1: Int, viewport: IntArray): Int {
        val h = 64
        roundRect(g, x0, y, x1, y + h, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        drawIcon(g, "flame", x0 + 12, y + 15, 34, -1)
        drawTextV(g, "Soul", x0 + 58, y + 9, y + 27, Theme.ACCENT, fTitle, MsdfFonts.menuSemibold)
        val ver = "v${Soul.version}"
        val loader = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fabricloader").map { it.metadata.version.friendlyString }.orElse("?")
        drawTextV(g, "$ver · Minecraft 26.2 · Fabric Loader $loader · GPL-3.0-or-later", x0 + 58, y + 27, y + 39, Theme.TEXT_DIM, fCaption)
        // No hover, cursor or hint: the developer unlock is deliberately undiscoverable.
        if (!DevAccess.unlocked) clippedRegion(x0 + 58, y + 27, x0 + 58 + textWidth(ver, fCaption), y + 39, viewport) { onVersionClick() }
        // Update status (spec mocks/updater/ About): a colored dot and one line.
        val (status, color) = updateStatus(UpdateService.snapshot)
        roundRect(g, x0 + 58, y + 46 - 3, x0 + 58 + 6, y + 46 + 3, color, SoulRenderPipelines.ROUND_PILL)
        drawTextV(g, status, x0 + 58 + 10, y + 40, y + 52, 0xFFBBBBBB.toInt(), F_10_5)
        var bx = x1 - 12
        for ((label, url) in listOf("Discord" to ConfigRegistry.DISCORD_URL, "GitHub" to ConfigRegistry.GITHUB_URL)) {
            val bw = (textWidth(label, fBody) + 24).coerceAtLeast(64)
            suppressRegions = true
            drawButton(g, mx, my, bx - bw, y + h / 2 - 10, bx, y + h / 2 + 10, label) {}
            suppressRegions = false
            clippedRegion(bx - bw, y + h / 2 - 10, bx, y + h / 2 + 10, viewport) { Util.getPlatform().openUri(url) }
            bx -= bw + 6
        }
        return y + h
    }

    // ---------------------------------------------------------------- updater (spec mocks/updater/)

    private fun openUpdate(mode: Int) = open(UpdateScreen(this, mode))

    /** About's status line and its dot color. */
    private fun updateStatus(s: UpdateSnapshot): Pair<String, Int> {
        val r = s.release
        val urgent = s.urgent
        if (urgent != null) return (if (urgent.kind == UpdateNotice.REVOKED) "Withdrawn: please update" else "Below the minimum version: please update") to UPD_RED
        if (s.phase == UpdatePhase.READY_RESTART && r != null) return "Restart to update to v${r.version}" to Theme.ACCENT
        if (s.phase == UpdatePhase.DOWNLOADING && r != null) return "Downloading v${r.version} · ${(s.progress * 100).toInt()} %" to Theme.ACCENT
        if (s.phase == UpdatePhase.ERROR && r != null) return "Updating to v${r.version} failed" to UPD_RED
        if (s.phase == UpdatePhase.AVAILABLE && r != null && !s.skipped) return "v${r.version} available" to Theme.ACCENT
        if (s.checking) return "Checking for updates…" to Theme.TEXT_DIM
        val ago = if (s.lastCheckedAt == 0L) "" else " · checked ${agoText(s.lastCheckedAt)}"
        return "Up to date$ago" to UPD_GREEN
    }

    private fun agoText(at: Long): String {
        val min = (System.currentTimeMillis() - at) / 60_000
        return if (min < 1) "just now" else if (min < 60) "$min min ago" else "${min / 60} h ago"
    }

    /** The version-header dot: restart pending (accent) or withdrawn (red), with its tooltip. */
    private fun updateDot(s: UpdateSnapshot): Pair<Int, String>? {
        val urgent = s.urgent
        if (urgent != null) return UPD_RED to (if (urgent.kind == UpdateNotice.REVOKED) "This version was withdrawn" else "This version is below the minimum")
        val r = s.release
        if (s.phase == UpdatePhase.READY_RESTART && r != null) return Theme.ACCENT to "Restart to update to v${r.version}"
        return null
    }

    /** The withdrawn / below-minimum banner's text, null when the running version is fine. */
    private fun updateBannerText(s: UpdateSnapshot): String? {
        val urgent = s.urgent ?: return null
        val to = s.release?.let { "please update to v${it.version}" } ?: "please update"
        val v = "v${s.running}"
        if (urgent.kind == UpdateNotice.REVOKED) {
            val why = urgent.detail?.trim()?.trimEnd('.')?.replaceFirstChar { it.uppercase() } ?: "It has a known problem"
            return "**Soul $v was withdrawn.** $why. Everything keeps working, but $to."
        }
        return "**Soul $v is too old for the backend.** Prices, party sync and notifications may stop working. ${to.replaceFirstChar { it.uppercase() }}."
    }

    /**
     * The red strip on top of every page while the running version is withdrawn or below the
     * minimum: no close button, it stays until Soul is updated. Measures with [g] null; returns the
     * strip's height.
     */
    private fun drawUpdateBanner(g: GuiGraphicsExtractor?, text: String, x0: Int, y0: Int, x1: Int): Int {
        val bx0 = x0 + 18
        val bx1 = x1 - 18
        val top = y0 + 10
        val textH = drawRich(null, RichText.parse(text), 0, 0, bx1 - bx0 - 36, F_10_5, 0, 13)
        val h = textH + 14
        if (g != null) {
            roundRect(g, bx0, top, bx1, top + h, 0xFF6B2525.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
            roundRect(g, bx0 + 1, top + 1, bx1 - 1, top + h - 1, 0xFF3A1616.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
            drawIcon(g, "warn", bx0 + 10, top + (h - 16) / 2, 16, 0xFFFF6B6B.toInt())
            drawRich(g, RichText.parse(text), bx0 + 36, top + 7, bx1 - 10, F_10_5, 0xFFF3C4C4.toInt(), 13)
            region(bx0, top, bx1, top + h) {}
        }
        return h
    }

    private class UpdBtn(val label: String, val accent: Boolean, val onClick: () -> Unit)

    /**
     * Start's update card (mock `updateCard()`): available, downloading, restart, errors, "Updated".
     * Nothing while up to date or when the offered version was skipped (unless withdrawn). Returns
     * its height, 0 when there is none.
     */
    private fun drawUpdateCard(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, viewport: IntArray): Int {
        val s = UpdateService.snapshot
        val r = s.release
        val sizeText = r?.let { UpdateScreen.mb(it.size) } ?: ""
        var stripe = Theme.ACCENT
        var kickerColor = Theme.ACCENT_LIGHT
        var icon = "flame"
        var iconColor = -1
        val kicker: String
        val title: String
        val sub: String
        var bar: Float? = null
        var verifying = false
        val btn: UpdBtn
        val updated = s.updated
        if (s.phase == UpdatePhase.AVAILABLE && r != null && (!s.skipped || s.urgent != null)) {
            val inc = s.incompatible
            if (inc != null) {
                stripe = UPD_ORANGE; kickerColor = UPD_ORANGE; icon = "warn"; iconColor = UPD_ORANGE
                kicker = "Can't install"; title = "Soul v${r.version}"; sub = "$inc. Update it in your launcher."
                btn = UpdBtn("Download page", false) { Util.getPlatform().openUri(UpdateNotices.DOWNLOAD_PAGE) }
            } else {
                kicker = "Update available"; title = "Soul v${r.version}"
                sub = "$sizeText · ${if (r.channel == "beta") "Beta" else "Stable"}" + (r.publishedAt?.let { " · released ${it.take(10)}" } ?: "")
                btn = UpdBtn("Update Now", true) { openUpdate(UpdateScreen.OFFER) }
            }
        } else if (s.phase == UpdatePhase.DOWNLOADING && r != null) {
            verifying = s.verifying
            kicker = if (verifying) "Checking signature" else "Downloading"; title = "Soul v${r.version}"
            sub = if (verifying) "Making sure the file is really from Soul" else "${UpdateScreen.mb((r.size * s.progress).toLong())} of $sizeText"
            bar = if (verifying) 1f else s.progress
            btn = UpdBtn("Details", false) { openUpdate(UpdateScreen.PROGRESS) }
        } else if (s.phase == UpdatePhase.READY_RESTART && r != null) {
            icon = "restart"; iconColor = Theme.ACCENT_LIGHT
            kicker = "Restart to update"; title = "Soul v${r.version} is ready"
            sub = "Close Minecraft and press Play in your launcher. Until then you keep playing on v${s.running}."
            btn = UpdBtn("Close game now", true) { UpdateService.closeGame() }
        } else if (s.phase == UpdatePhase.ERROR && r != null) {
            stripe = UPD_RED; kickerColor = UPD_RED; icon = "x_circle"; iconColor = UPD_RED
            title = "Soul v${r.version}"
            if (s.errorKind == UpdateError.VERIFY) {
                kicker = "Update rejected"; sub = "The file is not signed by Soul, so it was deleted. Nothing was installed."
                btn = UpdBtn("Details", false) { openUpdate(UpdateScreen.PROGRESS) }
            } else if (s.errorKind == UpdateError.INCOMPATIBLE) {
                stripe = UPD_ORANGE; kickerColor = UPD_ORANGE; icon = "warn"; iconColor = UPD_ORANGE
                kicker = "Can't install"; sub = "${s.error}. Update it in your launcher."
                btn = UpdBtn("Download page", false) { Util.getPlatform().openUri(UpdateNotices.DOWNLOAD_PAGE) }
            } else {
                kicker = if (s.errorKind == UpdateError.DOWNLOAD) "Download failed" else "Update failed"
                sub = "${s.error}. Nothing changed."
                btn = UpdBtn("Retry", true) {
                    UpdateService.install()
                    openUpdate(UpdateScreen.PROGRESS)
                }
            }
        } else if (updated != null) {
            stripe = UPD_GREEN; kickerColor = UPD_GREEN; icon = "ok_circle"; iconColor = UPD_GREEN
            kicker = "Updated"; title = "Now on Soul v${updated.to}"; sub = "from v${updated.from}"
            btn = UpdBtn("What's new", true) { openUpdate(UpdateScreen.WHATS_NEW) }
        } else {
            return 0
        }

        val bw = (textWidth(btn.label, fBody) + 20).coerceAtLeast(56)
        val tx = x0 + 12 + 22 + 10
        val tx1 = x1 - 10 - bw - 10
        val subLines = wrapText(sub, tx1 - tx, fCaption)
        val h = 10 + 12 + 15 + subLines.size * 13 + (if (bar != null) 9 else 0) + 10
        roundRect(g, x0, y0, x1, y0 + h, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        g.fill(x0, y0 + 3, x0 + 2, y0 + h - 3, stripe)
        drawIcon(g, icon, x0 + 12, y0 + (h - 22) / 2, 22, iconColor)
        var y = y0 + 10
        drawTextV(g, kicker.uppercase(), tx, y, y + 12, kickerColor, F_CHIP_SMALL, MsdfFonts.menuSemibold)
        y += 12
        drawTextV(g, title, tx, y, y + 15, Theme.TEXT, F_11_5, MsdfFonts.menuSemibold)
        if (r != null && r.channel == "beta" && (s.phase == UpdatePhase.AVAILABLE || s.phase == UpdatePhase.DOWNLOADING)) {
            drawChip(g, tx + textWidth(title, F_11_5, MsdfFonts.menuSemibold) + 6, y + 8, "BETA", Theme.BETA, Theme.BETA_BG)
        }
        y += 15
        for (line in subLines) {
            drawTextV(g, line, tx, y, y + 13, 0xFF999999.toInt(), fCaption)
            y += 13
        }
        val b = bar
        if (b != null) {
            val by = y + 5
            roundRect(g, tx, by, tx1, by + 4, Theme.SEPARATOR, SoulRenderPipelines.ROUND_PILL)
            val fill = tx + ((tx1 - tx) * b.coerceIn(0f, 1f)).toInt()
            if (fill > tx + 2) roundRect(g, tx, by, fill, by + 4, if (verifying) 0xFF60A5FA.toInt() else Theme.ACCENT, SoulRenderPipelines.ROUND_PILL)
        }
        val bx1 = x1 - 10
        val cy = y0 + h / 2
        suppressRegions = true
        if (btn.accent) drawButton(g, mx, my, bx1 - bw, cy - 11, bx1, cy + 11, btn.label, accent = true) {}
        else outlineButton(g, mx, my, bx1 - bw, cy - 11, bx1, cy + 11, btn.label) {}
        suppressRegions = false
        clippedRegion(bx1 - bw, cy - 11, bx1, cy + 11, viewport) { btn.onClick() }
        return h
    }

    // ---------------------------------------------------------------- Start page

    private fun drawStart(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, contentW: Int) {
        val st = scroll("start")
        st.view = y1 - y0
        val viewport = intArrayOf(x0, y0, x1, y1)
        beginClip(g, x0, y0, x1, y1)
        val px0 = x0 + 18
        val px1 = x1 - 18
        var y = y0 + 14 - st.pos.roundToInt()
        val top = y

        val updH = drawUpdateCard(g, mx, my, px0, y, px1, viewport)
        if (updH > 0) y += updH + 14

        // Search hint.
        val hHover = mx in px0..px1 && my in y..y + 32
        roundRect(g, px0, y, px1, y + 32, if (hHover) Theme.ACCENT else Theme.SEPARATOR, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, px0 + 1, y + 1, px1 - 1, y + 31, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        drawIcon(g, "search", px0 + 10, y + 10, 13, Theme.ACCENT_LIGHT)
        val kbdW = kbdWidth("Ctrl") + 3 + kbdWidth("K")
        drawTextV(g, ellipsize("Search everything: features, settings, pages. Try \"sprint\", \"implosion\" or \"prices\".", px1 - 10 - kbdW - 8 - (px0 + 31), fBody), px0 + 31, y, y + 32, if (hHover) Theme.TEXT else Theme.TEXT_DIM, fBody)
        val kx = px1 - 10 - kbdW
        drawKbd(g, kx + drawKbd(g, kx, y + 16, "Ctrl") + 3, y + 16, "K")
        clippedRegion(px0, y, px1, y + 32, viewport) { openPalette() }
        y += 32 + 14

        val two = contentW >= START_TWO_COL_W
        if (two) {
            // grid 1.15fr 1fr, gap 18
            val w = px1 - px0 - 18
            val leftW = (w * 1.15f / 2.15f).roundToInt()
            val yl = drawRightNow(g, mx, my, px0, y, px0 + leftW, viewport, st, top)
            val yr = drawStartSide(g, mx, my, px0 + leftW + 18, y, px1, viewport)
            y = maxOf(yl, yr)
        } else {
            y = drawRightNow(g, mx, my, px0, y, px1, viewport, st, top) + 18
            y = drawStartSide(g, mx, my, px0, y, px1, viewport)
        }
        endClip(g)
        st.content = y - top + 14 + 18
        drawScrollbar(g, mx, my, st, x0, y0, x1, y1)
    }

    private fun header(g: GuiGraphicsExtractor, text: String, x0: Int, y: Int): Int {
        drawTextV(g, text, x0 + 2, y + 4, y + 18, Theme.TEXT_DIM, fBody)
        return y + 18 + 6
    }

    private fun drawRightNow(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y: Int, x1: Int, viewport: IntArray, st: ScrollState, top: Int): Int {
        var yy = header(g, "Right now", x0, y)
        if (nowGroups.isEmpty()) {
            val text = if (now.area == null) "Not on SkyBlock. Features for your island and for what you are doing show up here." else "Nothing Soul does is specific to this right now."
            for (line in wrapText(text, x1 - x0 - 4, F_10_5)) {
                drawTextV(g, line, x0 + 2, yy + 4, yy + 18, Theme.TEXT_FAINT, F_10_5)
                yy += 14
            }
            return yy + 8
        }
        for (grp in nowGroups) {
            val sy = yy + 6
            drawIcon(g, grp.icon, x0 + 2, sy + 1, 11, Theme.ACCENT_LIGHT)
            drawTextV(g, grp.label, x0 + 2 + 11 + 6, sy, sy + 13, Theme.ACCENT_LIGHT, F_10_5, MsdfFonts.menuSemibold)
            val more = "Open tab ›"
            val mw = textWidth(more, fCaption)
            val hovered = mx in x1 - 2 - mw..x1 - 2 && my in sy..sy + 13
            drawTextV(g, more, x1 - 2 - mw, sy, sy + 13, if (hovered) Theme.ACCENT else Theme.TEXT_FAINT, fCaption)
            val first = grp.cards.first()
            clippedRegion(x1 - 2 - mw, sy, x1 - 2, sy + 13, viewport) { openActivity(first.activity, first.tab) }
            yy = sy + 13 + 6
            for (c in grp.cards) yy += drawCard(g, mx, my, c, x0, yy, x1, viewport, false) {
                openActivity(c.activity, c.tab, c.id)
                setFlash(c)
            } + 6
        }
        return yy
    }

    private fun drawStartSide(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y: Int, x1: Int, viewport: IntArray): Int {
        var yy = drawTip(g, mx, my, x0, y, x1, viewport) + 14

        val pins = cfg.menu.pins.mapNotNull { ConfigRegistry.card(it) }
        yy = header(g, "Pinned", x0, yy - 6)
        if (pins.size > 1) {
            val q = "drag to reorder"
            drawTextV(g, q, x1 - 2 - textWidth(q, fCaption), yy - 20, yy - 6, 0xFF444444.toInt(), fCaption)
        }
        if (pins.isEmpty()) {
            yy = emptyLine(g, "Pin features from their card to keep them here.", x0, yy, x1)
        } else {
            for (c in pins) yy += miniCard(g, mx, my, c, x0, yy, x1, viewport, pinned = true) + 6
        }

        val recent = cfg.menu.recent.mapNotNull { ConfigRegistry.card(it) }
        if (recent.isNotEmpty()) {
            yy = header(g, "Recently changed", x0, yy + 8)
            for (c in recent) yy += miniCard(g, mx, my, c, x0, yy, x1, viewport, pinned = false) + 6
        }

        // What's new: every line of the running version, always; seen lines only lose the NEW accent.
        val news = Releases.of(Soul.version)
        if (news.isNotEmpty()) {
            yy += 8
            val semi = MsdfFonts.menuSemibold
            drawTextV(g, "New in ", x0 + 2, yy + 4, yy + 18, Theme.TEXT_DIM, fBody)
            drawTextV(g, "v${Soul.version}", x0 + 2 + textWidth("New in ", fBody), yy + 4, yy + 18, Theme.TEXT, fBody, semi)
            yy += 24
            for (n in news) {
                val lines = wrapText(n.text, x1 - x0 - 26, F_10_5)
                val h = lines.size * 13 + 10
                val hovered = mx in x0..x1 && my in yy..yy + h
                val fresh = MenuRules.isNoteNew(cfg.menu, Soul.version, n)
                if (hovered) roundRect(g, x0, yy, x1, yy + h, Theme.SECTION, SoulRenderPipelines.ROUND_SMALL)
                drawTextV(g, "•", x0 + 8, yy + 5, yy + 18, if (fresh) Theme.ACCENT else Theme.TEXT_FAINT, F_10_5)
                var ly = yy + 5
                for (line in lines) {
                    drawTextV(g, line, x0 + 18, ly, ly + 13, if (hovered) 0xFFFFFFFF.toInt() else if (fresh) 0xFFCCCCCC.toInt() else 0xFF9A9A9A.toInt(), F_10_5)
                    ly += 13
                }
                val target = MenuRules.noteTarget(cfg.menu, Soul.version, n)
                clippedRegion(x0, yy, x1, yy + h, viewport) { jump(target) }
                yy += h + 1
            }
        }
        return yy
    }

    private fun emptyLine(g: GuiGraphicsExtractor, text: String, x0: Int, y: Int, x1: Int): Int {
        var yy = y + 6
        for (line in wrapText(text, x1 - x0 - 4, F_10_5)) {
            drawTextV(g, line, x0 + 2, yy, yy + 14, Theme.TEXT_FAINT, F_10_5)
            yy += 14
        }
        return yy + 6
    }

    /** The tip card above Pinned: fixed height (2 lines max) so cycling never moves the page. */
    private fun drawTip(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y: Int, x1: Int, viewport: IntArray): Int {
        val h = 52
        val t = TIPS[tip % TIPS.size]
        roundRect(g, x0, y, x1, y + h, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        g.fill(x0, y + 3, x0 + 2, y + h - 3, Theme.ACCENT)
        var right = x1 - 8
        val nxHover = mx in right - 20..right && my in y + h / 2 - 10..y + h / 2 + 10
        if (nxHover) roundRect(g, right - 20, y + h / 2 - 10, right, y + h / 2 + 10, Theme.SEPARATOR, SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, "refresh", right - 16, y + h / 2 - 6, 12, if (nxHover) Theme.TEXT else Theme.TEXT_FAINT)
        clippedRegion(right - 20, y + h / 2 - 10, right, y + h / 2 + 10, viewport) { tip = (tip + 1) % TIPS.size }
        right -= 20 + 8
        val bw = textWidth(t.button, fBody) + 18
        suppressRegions = true
        outlineButton(g, mx, my, right - bw, y + h / 2 - 11, right, y + h / 2 + 11, t.button) {}
        suppressRegions = false
        clippedRegion(right - bw, y + h / 2 - 11, right, y + h / 2 + 11, viewport) { tipAction(t.action) }
        right -= bw + 8
        val tx = x0 + 12
        val lines = wrapText(t.text, right - tx, F_10_5)
        val shown = if (lines.size <= 2) lines else listOf(lines[0], ellipsize(lines.drop(1).joinToString(" "), right - tx, F_10_5))
        val blockH = 12 + shown.size * 13
        var ly = y + (h - blockH) / 2
        drawTextV(g, t.kind.uppercase(), tx, ly, ly + 12, Theme.ACCENT_LIGHT, F_CHIP_SMALL, MsdfFonts.menuSemibold)
        ly += 12
        for (line in shown) {
            drawTextV(g, line, tx, ly, ly + 13, 0xFFBBBBBB.toInt(), F_10_5)
            ly += 13
        }
        return y + h
    }

    private fun tipAction(action: String) {
        if (action == "pal") openPalette()
        else if (action == "movegui") open(HudMoveScreen(this))
        else if (action == "invbuttons") onAction("openInventoryButtons")
        else jump(action)
    }

    /** Start's compact card: icon, name + path, pin, switch. Pinned ones drag to reorder. */
    private fun miniCard(g: GuiGraphicsExtractor, mx: Int, my: Int, card: FeatureCard, x0: Int, y0: Int, x1: Int, viewport: IntArray, pinned: Boolean): Int {
        val h = 34
        val y1 = y0 + h
        val hovered = mx in x0..x1 && my in y0..y1 && pressPin == null
        val dragged = pinned && pinDragging && pressPin == card.id
        roundRect(g, x0, y0, x1, y1, if (hovered) 0xFF242424.toInt() else Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        if (pinned) {
            pinRows += Triple(card.id, y0, y1)
            clippedRegion(x0, y0, x1, y1, viewport) {
                pressPin = card.id
                pressY = lastMouseY
                pinDragging = false
            }
        } else {
            clippedRegion(x0, y0, x1, y1, viewport) {
                openActivity(card.activity, card.tab, card.id)
                setFlash(card)
            }
        }
        iconBox(g, card.icon, x0 + 6, (y0 + y1) / 2 - 11, 22, 13, isOn(card))
        var right = x1 - 10
        right = drawCardControl(g, mx, my, card, right, (y0 + y1) / 2, viewport, toggleW, toggleH) - 8
        pinButton(g, mx, my, card, right - 20, (y0 + y1) / 2 - 10, viewport)
        right -= 20 + 8
        val tx = x0 + 6 + 22 + 8
        drawCardTitle(g, card, tx, (y0 + y1) / 2, right - tx, extra = ConfigRegistry.pathOf(card))
        if (dragged) roundRect(g, x0, y0, x1, y1, 0x99161616.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        if (pinned && pinDragging && pressPin != null && pressPin != card.id && lastMouseY in y0..y1) {
            val above = lastMouseY < (y0 + y1) / 2
            val ly = if (above) y0 else y1 - 2
            g.fill(x0, ly, x1, ly + 2, Theme.ACCENT)
        }
        return h
    }

    // ---------------------------------------------------------------- onboarding

    private fun drawOnboarding(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        roundRect(g, x0, y0, x1, y1, 0x8C000000.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        region(x0, y0, x1, y1) {}
        val w = minOf(420, ((x1 - x0) * 0.92f).toInt())
        val pad = 18
        val iw = w - pad * 2
        val semi = MsdfFonts.menuSemibold
        val intro = wrapText("Pick everything that fits. We switch on a few features for each; everything else stays off. Change anything later.", iw, F_10_5)
        val cardIds = ConfigRegistry.onboardingPicks.filter { it.id in picks }.flatMap { it.cards }.distinct()
        val names = cardIds.mapNotNull { ConfigRegistry.card(it)?.name }
        val listWords = if (names.isEmpty()) listOf("Nothing picked yet." to Theme.TEXT_DIM)
        else listOf("Turns on:" to Theme.TEXT_DIM) + names.mapIndexed { i, n -> (if (i < names.size - 1) "$n," else n) to 0xFFCCCCCC.toInt() }
        val listLines = wrapWords(listWords, iw, fCaption)
        val rows = (ConfigRegistry.onboardingPicks.size + 1) / 2
        val gridH = rows * 40 + (rows - 1) * 6
        val avail = y1 - y0 - 8
        val full = 20 + 6 + intro.size * 14 + 12 + gridH + 12 + maxOf(28, listLines.size * 13) + 12 + 22 + pad * 2
        // Short screens drop the intro and tighten the spacing so the buttons stay on screen.
        val tight = full > avail
        val shown = if (tight) emptyList() else intro
        val vpad = if (tight) 10 else pad
        val listH = if (tight) maxOf(13, listLines.size * 13) else maxOf(28, listLines.size * 13)
        val h = 20 + 6 + shown.size * 14 + 12 + gridH + 12 + listH + 12 + 22 + vpad * 2
        val bx0 = (x0 + x1) / 2 - w / 2
        val by0 = ((y0 + y1) / 2 - h / 2).coerceAtLeast(y0 + 4)
        roundRect(g, bx0 - 1, by0 - 1, bx0 + w + 1, by0 + h + 1, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, bx0, by0, bx0 + w, by0 + h, 0xFF1A1A1A.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        var y = by0 + vpad
        drawIcon(g, "flame", bx0 + pad, y, 20, -1)
        drawTextV(g, ellipsize("Welcome to Soul! What do you play?", iw - 28, fTitle, semi), bx0 + pad + 28, y, y + 20, Theme.TEXT, fTitle, semi)
        y += 20 + 6
        for (line in shown) {
            drawTextV(g, line, bx0 + pad, y, y + 14, Theme.TEXT_DIM, F_10_5)
            y += 14
        }
        y += 12
        val cw = (iw - 6) / 2
        for ((i, p) in ConfigRegistry.onboardingPicks.withIndex()) {
            val cx0 = bx0 + pad + (i % 2) * (cw + 6)
            val cy0 = y + (i / 2) * 46
            val on = p.id in picks
            val hovered = mx in cx0..cx0 + cw && my in cy0..cy0 + 40
            roundRect(g, cx0, cy0, cx0 + cw, cy0 + 40, if (on) Theme.ACCENT else 0xFF2C2C2C.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
            roundRect(g, cx0 + 1, cy0 + 1, cx0 + cw - 1, cy0 + 39, if (on) 0xFF1B2638.toInt() else if (hovered) 0xFF282828.toInt() else 0xFF222222.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
            iconBox(g, p.icon, cx0 + 8, cy0 + 7, 26, 15, on)
            drawTextV(g, ellipsize(p.name, cw - 70, fBody, semi), cx0 + 43, cy0 + 6, cy0 + 21, Theme.TEXT, fBody, semi)
            val n = p.cards.size
            drawTextV(g, "$n feature${if (n == 1) "" else "s"}", cx0 + 43, cy0 + 21, cy0 + 33, Theme.TEXT_DIM, F_9_5)
            val bx = cx0 + cw - 10 - 12
            if (on) {
                roundRect(g, bx, cy0 + 14, bx + 12, cy0 + 26, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
                drawIcon(g, "check", bx + 1, cy0 + 15, 10, 0xFFFFFFFF.toInt())
            } else {
                roundRect(g, bx, cy0 + 14, bx + 12, cy0 + 26, 0xFF444444.toInt(), SoulRenderPipelines.ROUND_SMALL)
                roundRect(g, bx + 1, cy0 + 15, bx + 11, cy0 + 25, 0xFF222222.toInt(), SoulRenderPipelines.ROUND_SMALL)
            }
            region(cx0, cy0, cx0 + cw, cy0 + 40) { if (!picks.remove(p.id)) picks.add(p.id) }
        }
        y += gridH + 12
        var ly = y
        for (line in listLines) {
            var lx = bx0 + pad
            for ((word, color) in line) {
                drawTextV(g, word, lx, ly, ly + 13, color, fCaption)
                lx += textWidth("$word ", fCaption)
            }
            ly += 13
        }
        y += listH + 12
        drawButton(g, mx, my, bx0 + pad, y, bx0 + pad + textWidth("Skip", fBody) + 28, y + 22, "Skip") { finishOnboarding(emptyList()) }
        val goLabel = if (cardIds.isEmpty()) "Turn them on" else "Turn on ${cardIds.size} feature${if (cardIds.size == 1) "" else "s"}"
        val gw = textWidth(goLabel, fBody) + 28
        val gx1 = bx0 + w - pad
        if (cardIds.isEmpty()) {
            roundRect(g, gx1 - gw, y, gx1, y + 22, 0x662563EB, SoulRenderPipelines.ROUND_SMALL)
            com.soulreturns.config.gui.render.SoulText.drawCenteredOn(g, goLabel, gx1 - gw / 2, y + 11, fBody, Theme.TEXT_DIM, forceCustom = true)
        } else {
            drawButton(g, mx, my, gx1 - gw, y, gx1, y + 22, goLabel, accent = true) { finishOnboarding(cardIds) }
        }
    }

    private fun finishOnboarding(cardIds: List<String>) {
        var n = 0
        for (id in cardIds) {
            val master = ConfigRegistry.card(id)?.master ?: continue
            if (!master.get()) master.set(true)
            n++
        }
        MenuState.set { copy(onboardingDone = true) }
        onboardingOpen = false
        picks.clear()
        page = "start"
        if (cardIds.isNotEmpty()) showToast("$n features on. The Start page lists what is active.")
    }

    // ---------------------------------------------------------------- toast

    private fun drawToast(g: GuiGraphicsExtractor, x0: Int, x1: Int, y1: Int) {
        val text = toast ?: return
        if (System.currentTimeMillis() > toastUntil) {
            toast = null
            return
        }
        val w = textWidth(text, F_10_5) + 24
        val cx = (x0 + x1) / 2
        val ty1 = y1 - 52
        roundRect(g, cx - w / 2 - 1, ty1 - 25, cx + w / 2 + 1, ty1 + 1, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, cx - w / 2, ty1 - 24, cx + w / 2, ty1, 0xFF242424.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        drawTextV(g, text, cx - w / 2 + 12, ty1 - 24, ty1, Theme.TEXT, F_10_5)
    }

    // ---------------------------------------------------------------- search palette

    private fun openPalette() {
        if (onboardingOpen) return
        commit()
        closeDropdown()
        paletteIndex = MenuSearch.index(ConfigRegistry.cards, ConfigRegistry.visibleSettingsTabs(), ConfigRegistry.optionSynonyms)
        paletteField.set("")
        paletteField.focused = true
        paletteOpen = true
        paletteSel = 0
        refreshPalette(selectBest = false)
    }

    private fun closePalette() {
        paletteOpen = false
    }

    private fun refreshPalette(selectBest: Boolean) {
        paletteGroups = MenuSearch.results(paletteIndex, paletteField.text, RightNow.cards(ConfigRegistry.cards, now))
        if (selectBest) paletteSel = MenuSearch.bestIndex(paletteGroups)
        val n = paletteGroups.sumOf { it.items.size }
        paletteSel = paletteSel.coerceIn(0, (n - 1).coerceAtLeast(0))
        scroll("palette").pos = 0f
        paletteReveal = true
    }

    private fun paletteFlat(): List<SearchItem> = paletteGroups.flatMap { it.items }

    private fun drawPalette(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int, uiH: Int, cardX0: Int, cardY0: Int, cardW: Int, cardH: Int) {
        g.fill(0, 0, uiW, uiH, 0x38000000)
        region(0, 0, uiW, uiH) { closePalette() }
        val w = minOf(PALETTE_W, cardW - 40)
        val x0 = cardX0 + (cardW - w) / 2
        val y0 = cardY0 + (cardH * PALETTE_TOP).toInt()
        val maxH = (cardH * PALETTE_MAX_H).toInt()
        val x1 = x0 + w

        // Results layout first, to size the box.
        val rowH = 34
        val headH = 19
        val flat = paletteFlat()
        val contentH = if (flat.isEmpty()) 50 else paletteGroups.sumOf { headH + it.items.size * rowH } + 6
        val fieldH = 8 + 32 + 8
        val footH = 23
        val listH = minOf(contentH, maxH - fieldH - footH).coerceAtLeast(40)
        val y1 = y0 + fieldH + listH + footH

        roundRect(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_LARGE)
        roundRect(g, x0, y0, x1, y1, Theme.PALETTE, SoulRenderPipelines.ROUND_LARGE)
        region(x0, y0, x1, y1) {}

        // Field.
        val fx0 = x0 + 8
        val fx1 = x1 - 8
        val fy0 = y0 + 8
        val fy1 = fy0 + 32
        roundRect(g, fx0, fy0, fx1, fy1, Theme.ACCENT, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, fx0 + 1, fy0 + 1, fx1 - 1, fy1 - 1, Theme.PALETTE_FIELD, SoulRenderPipelines.ROUND_MEDIUM)
        drawIcon(g, "search", fx0 + 10, fy0 + 10, 13, Theme.ACCENT_LIGHT)
        val escW = kbdWidth("esc")
        val escX = fx1 - 6 - escW
        drawKbd(g, escX, (fy0 + fy1) / 2, "esc")
        region(escX, fy0 + 8, escX + escW, fy1 - 8) { closePalette() }
        val tx = fx0 + 31
        val q = paletteField.text
        if (q.isEmpty()) {
            drawTextV(g, "Search features, settings and pages", tx, fy0, fy1, Theme.TEXT_FAINT, F_12)
        } else {
            var shown = q
            val avail = escX - 8 - tx
            while (shown.isNotEmpty() && textWidth(shown, F_12) > avail) shown = shown.substring(1)
            drawTextV(g, shown, tx, fy0, fy1, Theme.TEXT, F_12)
        }
        if ((System.currentTimeMillis() / 500) % 2 == 0L) {
            val shownW = textWidth(q, F_12).coerceAtMost(escX - 8 - tx)
            g.fill(tx + shownW, fy0 + 9, tx + shownW + 1, fy1 - 9, Theme.TEXT)
        }

        // Results.
        val ly0 = y0 + fieldH
        val ly1 = ly0 + listH
        val st = scroll("palette")
        st.view = listH
        st.content = contentH
        val viewport = intArrayOf(x0, ly0, x1, ly1)
        beginClip(g, x0, ly0, x1, ly1)
        var y = ly0 - st.pos.roundToInt()
        val top = y
        var i = 0
        if (flat.isEmpty()) {
            drawTextCentered(g, "Nothing matches \"$q\". Try fewer letters or another word.", (x0 + x1) / 2, ly0 + 20, 0xFF666666.toInt(), fBody)
        }
        for (grp in paletteGroups) {
            drawTextV(g, grp.title, x0 + 14, y + 7, y + headH, 0xFF777777.toInt(), F_CHIP_SMALL, MsdfFonts.menuSemibold)
            y += headH
            for (it in grp.items) {
                val idx = i++
                val rx0 = x0 + 6
                val rx1 = x1 - 6
                val ry0 = y
                val ry1 = y + rowH
                if (paletteReveal && idx == paletteSel) {
                    paletteReveal = false
                    st.reveal(ry0 - top, ry1 - top, 4)
                }
                val inView = my in ly0..ly1
                if (inView && mx in rx0..rx1 && my in ry0..ry1 && (lastHoverX != mx || lastHoverY != my)) paletteSel = idx
                val sel = idx == paletteSel
                if (sel) {
                    roundRect(g, rx0, ry0, rx1, ry1, Theme.ACCENT, SoulRenderPipelines.ROUND_MEDIUM)
                    roundRect(g, rx0 + 1, ry0 + 1, rx1 - 1, ry1 - 1, Theme.PALETTE_SELECTED, SoulRenderPipelines.ROUND_MEDIUM)
                }
                paletteRow(g, it, rx0, ry0, rx1, ry1, sel, viewport)
                y = ry1
            }
        }
        endClip(g)
        lastHoverX = mx
        lastHoverY = my
        drawScrollbar(g, mx, my, st, x0, ly0, x1, ly1)

        // Key hints.
        g.fill(x0, ly1, x1, ly1 + 1, 0xFF262626.toInt())
        val hints = listOf(listOf("arrow_up", "arrow_down") to "move", listOf("enter") to "open", listOf("tab") to "toggle", listOf("esc") to "close")
        val hintW = hints.sumOf { (keys, label) -> keys.sumOf { k -> if (k == "tab" || k == "esc") textWidth(k, F_9_5, MsdfFonts.menuSemibold) + 4 else 11 } + textWidth(label, F_9_5) } + 16 * (hints.size - 1)
        var hx = (x0 + x1) / 2 - hintW / 2
        val hy0 = ly1 + 1
        for ((keys, label) in hints) {
            for (k in keys) {
                if (k == "tab" || k == "esc") {
                    drawTextV(g, k, hx, hy0, y1, Theme.CHIP_TEXT, F_9_5, MsdfFonts.menuSemibold)
                    hx += textWidth(k, F_9_5, MsdfFonts.menuSemibold) + 4
                } else {
                    drawIcon(g, k, hx, (hy0 + y1) / 2 - 5, 10, 0xFF777777.toInt())
                    hx += 11
                }
            }
            hx += 3
            drawTextV(g, label, hx, hy0, y1, 0xFF777777.toInt(), F_9_5)
            hx += textWidth(label, F_9_5) + 16
        }
    }

    private var lastHoverX = 0
    private var lastHoverY = 0

    private fun paletteRow(g: GuiGraphicsExtractor, it: SearchItem, x0: Int, y0: Int, x1: Int, y1: Int, sel: Boolean, viewport: IntArray) {
        val cy = (y0 + y1) / 2
        roundRect(g, x0 + 8, cy - 12, x0 + 32, cy + 12, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x0 + 9, cy - 11, x0 + 31, cy + 11, 0xFF242424.toInt(), SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, it.icon, x0 + 14, cy - 6, 13, Theme.CHIP_TEXT)
        var right = x1 - 8
        val kind = it.kind.label
        drawTextV(g, kind, right - textWidth(kind, fChip), y0, y1, if (sel) Theme.ACCENT_LIGHT else 0xFF666666.toInt(), fChip)
        right -= 54 + 9
        val card = it.card
        if (it.kind == SearchItem.Kind.FEATURE && card?.master != null) {
            val m = card.master
            suppressRegions = true
            drawToggle(g, right - 26, cy - 7, m.get(), 26, 14) {}
            suppressRegions = false
            clippedRegion(right - 26, cy - 7, right, cy + 7, viewport) { flipFeature(it) }
            right -= 26 + 9
        }
        val tx = x0 + 8 + 24 + 9
        val semi = MsdfFonts.menuSemibold
        // Name with the first query token highlighted.
        val name = ellipsize(it.name, right - tx - (if (card?.beta == true && it.kind == SearchItem.Kind.FEATURE) chipWidth("BETA") + 6 else 0), fBody, semi)
        val tok = MenuSearch.tokens(paletteField.text).firstOrNull()
        val at = if (tok == null) -1 else name.lowercase().indexOf(tok)
        val ny0 = cy - 13
        val ny1 = cy + 1
        if (at < 0) {
            drawTextV(g, name, tx, ny0, ny1, Theme.TEXT, fBody, semi)
        } else {
            val a = name.substring(0, at)
            val b = name.substring(at, minOf(name.length, at + tok!!.length))
            val c = name.substring(minOf(name.length, at + tok.length))
            var nx = tx
            drawTextV(g, a, nx, ny0, ny1, Theme.TEXT, fBody, semi)
            nx += textWidth(a, fBody, semi)
            drawTextV(g, b, nx, ny0, ny1, Theme.ACCENT_LIGHT, fBody, semi)
            nx += textWidth(b, fBody, semi)
            drawTextV(g, c, nx, ny0, ny1, Theme.TEXT, fBody, semi)
        }
        if (card?.beta == true && it.kind == SearchItem.Kind.FEATURE) drawChip(g, tx + textWidth(name, fBody, semi) + 6, (ny0 + ny1) / 2, "BETA", Theme.BETA, Theme.BETA_BG)
        val sub = if (it.kind == SearchItem.Kind.SETTING && it.advanced) "${it.path} · Advanced" else it.path
        drawTextV(g, ellipsize(sub, right - tx, F_9_5), tx, cy + 1, cy + 13, Theme.TEXT_DIM, F_9_5)
        clippedRegion(x0, y0, x1, y1, viewport) { choose(it) }
    }

    private fun flipFeature(it: SearchItem?) {
        val master = it?.takeIf { i -> i.kind == SearchItem.Kind.FEATURE }?.card?.master ?: return
        master.toggle()
    }

    private fun choose(it: SearchItem) {
        closePalette()
        val card = it.card
        if (it.kind == SearchItem.Kind.FEATURE && card != null) {
            jump("f:${card.id}")
        } else if (it.kind == SearchItem.Kind.PAGE) {
            val st = it.settingsTab
            if (st != null) openSettings(st) else openActivity(it.activity ?: return, it.tab)
        } else if (card != null) {
            openActivity(card.activity, card.tab, card.id)
            if (it.advanced && !alwaysAdvanced) advOpen.add(card.id)
            it.option?.let { o -> setFlash(o) }
        } else {
            openSettings(it.settingsTab ?: return)
            if (it.advanced && !alwaysAdvanced) advOpen.add("set:${it.section}")
            it.option?.let { o -> setFlash(o) }
        }
    }

    // ---------------------------------------------------------------- actions

    override fun onAction(id: String) {
        if (id == "openMapConfig") {
            open(DungeonMapConfigScreen(this))
        } else if (id == ConfigRegistry.MOVE_GUI) {
            open(HudMoveScreen(this))
        } else if (id == "openCommandKeybinds") {
            open(CommandKeybindScreen(this))
        } else if (id == "openInventoryButtons") {
            MenuState.markSeen("f:invbuttons")
            open(InventoryButtonsScreen(this))
        } else if (id == "openSearchEverywhere") {
            MenuState.markSeen("f:searcheverywhere")
            open(com.soulreturns.features.search.SearchEverywhere.screen(this))
        } else if (id == "openContests") {
            MenuState.markSeen("f:contests")
            com.soulreturns.features.contests.ContestsNav.openHome()
        } else if (id == "openInventoryInfo") {
            open(InventoryInfoScreen(this))
        } else if (id == "openSoundLibrary") {
            open(SoundLibraryScreen(this))
        } else if (id == "openSoundReplacer") {
            open(SoundReplacerScreen(this))
        } else if (id == ConfigRegistry.AUTO_UI_SCALE) {
            pendingSlider.keys.removeAll { it.label == ConfigRegistry.UI_SCALE }
            AutoUiScale.applyPick()
            showToast("UI size set to ${SoulUi.scale} for this monitor")
        } else if (id == "replayOnboarding") {
            picks.clear()
            onboardingOpen = true
        } else if (id == "applyBackendUrl") {
            reconnectBackend()
        } else if (id == "updateCheck") {
            com.soulreturns.platform.updater.UpdateService.checkNow()
            showToast("Checking for updates")
        } else if (id == "updateUnskip") {
            UpdateService.unskip()
        } else if (id == "updateCopyLink") {
            minecraft?.keyboardHandler?.setClipboard(UpdateNotices.DOWNLOAD_PAGE)
            showToast("Download link copied")
        } else if (id == "lockDevAccess") {
            showConfirm(
                "Lock developer mode?",
                "The Dev tab, /soul dev and the dev keybinds disappear from this client. Unlocking again needs the developer password.",
                "Lock",
            ) {
                DevAccess.lock()
                openSettings("about")
            }
        } else if (id == "resetFishingSession") {
            FishingTracker.resetSession()
            showToast("Fishing session counters and the stopwatch are back to zero")
        } else if (id == "resetDragonSession") {
            DragonProfitTracker.resetSession()
            showToast("Dragon session counters are back to zero")
        } else if (id == "resetDragonAllTime") {
            // Confirmed: the stats file is local and nothing anywhere holds a second copy of it.
            showConfirm(
                "Reset all-time dragons?",
                "Every dragon, drop and Summoning Eye recorded on this SkyBlock profile is erased.\nThere is no copy of it anywhere else.",
                "Erase",
            ) {
                DragonProfitTracker.resetAll()
                showToast("All-time dragon counters cleared")
            }
        }
    }

    private fun onVersionClick() {
        val nowMs = System.currentTimeMillis()
        versionClicks = if (nowMs - lastVersionClickAt > VERSION_CLICK_GAP_MS) 1 else versionClicks + 1
        lastVersionClickAt = nowMs
        if (versionClicks < VERSION_CLICKS) return
        versionClicks = 0
        showPrompt("Developer access", "Enter the developer password.", "Unlock", masked = true) { password ->
            setPromptState(busy = true)
            SoulScope.io.launch {
                val error = try {
                    DevAccess.unlock(password)
                } catch (t: Exception) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    "Something went wrong: ${t.message}"
                }
                Minecraft.getInstance().execute {
                    if (minecraft?.gui?.screen() !== this@SoulConfigScreen) return@execute
                    if (error == null) {
                        showAlert(
                            "Developer mode unlocked",
                            "Settings has a Dev tab now. /soul dev shows up in tab completion after you next join a server.",
                            success = true,
                        )
                    } else {
                        setPromptState(busy = false, error = error)
                    }
                }
            }
        }
    }

    /**
     * Apply the Backend URL field and reconnect: the config field overrides any `-Dsoul.backendUrl`
     * / `SOUL_BACKEND_URL`, auth is cleared and redone against the new host, the live dungeon SSE is
     * dropped (it reconnects to the new host), and a modal reports the result.
     */
    private fun reconnectBackend() {
        commit()
        SoulHttp.overrideEnvWithConfig = true
        val url = SoulHttp.backendBaseUrl()
        showAlert("Reconnecting…", "Authenticating with\n$url", busy = true)
        Thread({
            BackendAuth.clear()
            val token = try {
                BackendAuth.ensureAuthenticated(forceRefresh = true)
            } catch (_: Throwable) {
                null
            }
            try {
                BackendClient.reset()
                RealtimeClient.reconnect("backend URL changed")
                SoulBackendChannel.onDungeonEnd()
            } catch (_: Throwable) {
            }
            val ok = token != null
            Minecraft.getInstance().execute {
                if (minecraft?.gui?.screen() !== this) return@execute
                if (ok) {
                    showAlert("Connected", "Authenticated with\n$url", success = true)
                } else {
                    showAlert("Connection failed", "Could not authenticate with\n$url\n\nCheck the URL and that you're online, then try again.")
                }
            }
        }, "soul-backend-reconnect").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- input

    override fun charTyped(event: CharacterEvent): Boolean {
        if (paletteOpen && !alertModalOpen) {
            if (paletteField.charTyped(event)) refreshPalette(selectBest = true)
            return true
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen || capturingKeybind != null) return super.keyPressed(event)
        val key = event.key()
        if (key == GLFW.GLFW_KEY_K && event.hasControlDown()) {
            if (onboardingOpen) return true
            if (paletteOpen) closePalette() else openPalette()
            return true
        }
        if (paletteOpen) {
            val flat = paletteFlat()
            if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
                if (flat.isNotEmpty()) paletteSel = (paletteSel + (if (key == GLFW.GLFW_KEY_DOWN) 1 else -1) + flat.size) % flat.size
                paletteReveal = true
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                flat.getOrNull(paletteSel)?.let { choose(it) }
            } else if (key == GLFW.GLFW_KEY_TAB) {
                flipFeature(flat.getOrNull(paletteSel))
            } else if (key == GLFW.GLFW_KEY_ESCAPE) {
                closePalette()
            } else {
                val before = paletteField.text
                paletteField.keyPressed(event, minecraft?.keyboardHandler)
                if (paletteField.text != before) refreshPalette(selectBest = true)
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && focusedText == null && editingSlider == null && !colorPickerOpen && !dropdownOpen) {
            if (onboardingOpen) return true
            if (selected != null && page != "start" && page != "settings") {
                selected = null
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (pressPin != null) {
            val s = SoulUi.factor()
            lastMouseX = (click.x() / s).roundToInt()
            lastMouseY = (click.y() / s).roundToInt()
            if (kotlin.math.abs(lastMouseY - pressY) > 4) pinDragging = true
            return true
        }
        return super.mouseDragged(click, dragX, dragY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val id = pressPin
        if (id != null) {
            pressPin = null
            if (pinDragging) {
                pinDragging = false
                // Drop above or below the row under the pointer (by half).
                val targetRow = pinRows.firstOrNull { lastMouseY in it.second..it.third }
                if (targetRow != null && targetRow.first != id) {
                    val rest = cfg.menu.pins.filter { it != id }
                    val at = rest.indexOf(targetRow.first) + if (lastMouseY >= (targetRow.second + targetRow.third) / 2) 1 else 0
                    MenuState.movePin(id, at)
                }
            } else {
                ConfigRegistry.card(id)?.let {
                    openActivity(it.activity, it.tab, it.id)
                    setFlash(it)
                }
            }
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val s = SoulUi.factor()
        val target = scrollTargetAt((mouseX / s).roundToInt(), (mouseY / s).roundToInt())
        if (target != null) {
            // Over a tab strip the wheel scrolls sideways.
            val d = if (target.horizontal) (if (scrollX != 0.0) scrollX else scrollY) else scrollY
            target.state.scrollBy(-d.toFloat() * SCROLL_STEP)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        commit()
        val target = parent
        if (target != null) minecraft?.setScreenAndShow(target) else super.onClose()
    }
}
