package com.soulreturns.features.profileviewer.gui

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.PlayerIdentityResolver
import com.soulreturns.data.hypixel.PlayerSkillCaps
import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.features.profileviewer.ProfileLookupFailure
import com.soulreturns.features.profileviewer.ProfileView
import com.soulreturns.features.profileviewer.ProfileViewerService
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * The Profile Viewer — `/spv`. A centred card with a header (player, profile pager, name field), a tab
 * strip and a scrollable body holding the active [ProfileTab].
 *
 * ### It opens before the data arrives
 * The legacy screen only opened once the fetch had succeeded, so a slow backend made `/spv` look like
 * it had done nothing at all. This one opens on the *start* of the lookup and shows a loading state,
 * then swaps to content or to an error card. That is what the three seams on
 * [ProfileViewerService] are for, wired in [register]: `onLookupStarted` → [beginLookup],
 * `present` → [deliver], `presentFailure` → [deliverFailure].
 *
 * ### State is read every frame, never rebuilt
 * The shown profile, active tab and scroll offset are plain `@Volatile` fields that
 * [extractRenderState] re-reads each frame — 26.2's retained mode calls it every frame regardless, so
 * a dirty-flag protocol would buy nothing. Switching profile or tab just assigns a field.
 *
 * A background thread writes those fields (the fetch completing), which is why they are `@Volatile`
 * and why every seam hops to the client thread first.
 */
class ProfileViewerScreen private constructor(
    private val parent: Screen?,
) : SoulScreenBase(Component.literal("Soul Profile Viewer")), ProfilePainter {

    companion object {
        /**
         * Point [ProfileViewerService]'s three presenter seams at this screen. Called once, from
         * [com.soulreturns.Soul]; until then `/spv` keeps printing its chat summary.
         */
        fun register() {
            ProfileViewerService.onLookupStarted = { name -> onClient { beginLookup(name) } }
            ProfileViewerService.present = { view -> onClient { deliver(view) } }
            ProfileViewerService.presentFailure = { failure -> onClient { deliverFailure(failure) } }
        }

        private const val HEADER_H = 44
        private const val TABS_H = 30
        private const val SCROLL_STEP = 20
        private const val BODY_PAD = 16
        private const val CLOSE_SIZE = 18
        private const val SWITCHER_H = 18
        private const val NAME_FIELD_W = 128

        /** Below this the name field is dropped rather than squeezed — the command still works. */
        private const val NAME_FIELD_MIN_W = 64

        private const val STATE_LOADING = 0
        private const val STATE_LOADED = 1
        private const val STATE_FAILED = 2

        private fun onClient(block: () -> Unit) {
            val mc = Minecraft.getInstance()
            if (mc.isSameThread) block() else mc.execute(block)
        }

        /** The open Profile Viewer, if that is what the player is looking at. */
        private fun current(): ProfileViewerScreen? =
            Minecraft.getInstance().gui?.screen() as? ProfileViewerScreen

        /**
         * A lookup started: show it. Reuses the open screen so typing a name into its field does not
         * stack screens, and opens a new one otherwise — deferred through `execute`, because a `/spv`
         * typed into chat is dispatched before the chat screen tears itself down and an inline
         * `setScreenAndShow` would be undone by it (same reason `/soul` defers).
         */
        private fun beginLookup(name: String) {
            // Don't flash a screen for a command that is switched off; the service reports it in chat.
            if (!enabled()) return
            val open = current()
            if (open != null) {
                open.startLoading(name)
                return
            }
            val mc = Minecraft.getInstance()
            // No parent: `/spv` is typed into chat, and returning to the chat screen on close would
            // be a surprise. A Soul screen that opens this one can pass itself instead.
            val screen = ProfileViewerScreen(null)
            screen.startLoading(name)
            mc.execute { mc.setScreenAndShow(screen) }
        }

        /**
         * A profile arrived. Normally the loading screen is already up; if the player closed it while
         * the fetch was in flight, that is a cancellation and the result is dropped. A screen is
         * opened only for a caller that never announced a start.
         */
        private fun deliver(view: ProfileView) {
            val open = current()
            if (open != null) {
                open.accept(view)
                return
            }
            if (!enabled()) return
            val mc = Minecraft.getInstance()
            val screen = ProfileViewerScreen(null)
            screen.accept(view)
            mc.execute { mc.setScreenAndShow(screen) }
        }

        /**
         * A lookup failed. Only ever fills in an already-open screen: the chat line has gone out
         * regardless, and opening a screen to deliver bad news the player did not stay for would be
         * worse than the chat line they can already read.
         */
        private fun deliverFailure(failure: ProfileLookupFailure) {
            current()?.accept(failure)
        }

        private fun enabled(): Boolean = try {
            cfg.profileViewer.enabled
        } catch (_: Throwable) {
            true
        }
    }

    // ------------------------------------------------------------------ state

    @Volatile private var state = STATE_LOADING
    @Volatile private var loadingName = ""
    @Volatile private var failure: ProfileLookupFailure? = null

    @Volatile private var identity: PlayerIdentity? = null
    @Volatile private var profiles: SkyblockProfiles? = null
    @Volatile private var current: SkyblockProfile? = null
    @Volatile private var member: SkyblockProfileMember? = null
    @Volatile private var fromCache = false
    @Volatile private var soulStats: SoulStatsEnvelope? = null
    @Volatile private var playerCaps: PlayerSkillCaps? = null

    private var activeTabId = ProfileTabs.ALL.first().id
    private var bodyScroll = 0f
    private var bodyContentH = 0
    private var bodyRect = intArrayOf(0, 0, 0, 0)

    private var nameQuery = ""
    private var nameFocused = false

    /**
     * The extractor for the frame being drawn, so [ProfilePainter] can forward onto the base's draw
     * helpers. Set at the top of [extractRenderState] and cleared at the bottom; a tab only ever draws
     * inside that window, so nothing can hold it past the frame.
     */
    private var frame: GuiGraphicsExtractor? = null

    // ------------------------------------------------------------------ state transitions

    private fun startLoading(name: String) {
        state = STATE_LOADING
        loadingName = name
        failure = null
        // Drop the previous player entirely. Keeping their body under a header that already says the
        // new name is the kind of half-updated screen someone screenshots as a wrong-numbers bug.
        identity = null
        profiles = null
        current = null
        member = null
        soulStats = null
        playerCaps = null
        fromCache = false
        bodyScroll = 0f
        nameFocused = false
        nameQuery = ""
        closeDropdown()
    }

    private fun accept(view: ProfileView) {
        identity = view.identity
        profiles = view.profiles
        current = view.profile
        member = view.member
        fromCache = view.fromCache
        failure = null
        bodyScroll = 0f
        state = STATE_LOADED
        loadExtras(view.identity)
    }

    private fun accept(f: ProfileLookupFailure) {
        failure = f
        state = STATE_FAILED
    }

    /**
     * Fetch the two side dishes: the Soul-collected stats (which decide whether the Soul tab exists)
     * and the account-specific skill caps (which decide whether Taming and Skill Average are right).
     *
     * Both are separate from the profile fetch and from each other because all three have different
     * failure models — and here both failures mean the same thing: **carry on without it**. Nothing is
     * ever reported to the user, so the screen is already usable while these are in flight and stays
     * usable if they never arrive.
     */
    private fun loadExtras(who: PlayerIdentity) {
        val own = isSelf(who)
        SoulScope.io.launch {
            val stats = try {
                SoulStatsClient.soulStats(who.uuidUndashed, ownProfile = own)
            } catch (_: Throwable) {
                null
            }
            val caps = try {
                SkillCapsClient.caps(who.uuidUndashed)
            } catch (_: Throwable) {
                null
            }
            onClient {
                // A second lookup may have finished while this was in flight; only the current
                // player's data may be shown.
                if (identity?.uuidUndashed == who.uuidUndashed) {
                    soulStats = stats
                    playerCaps = caps
                }
            }
        }
    }

    private fun isSelf(who: PlayerIdentity): Boolean {
        val self = try {
            MojangUuid.undashed(Minecraft.getInstance().user.profileId)
        } catch (_: Throwable) {
            null
        }
        return self != null && self.equals(who.uuidUndashed, ignoreCase = true)
    }

    /** The context the tabs read, or null while there is nothing loaded. */
    private fun context(): ProfileContext? {
        val id = identity ?: return null
        val all = profiles ?: return null
        val profile = current ?: return null
        val m = member ?: return null
        // Caps are per profile — a bingo profile and a main profile on one account genuinely
        // differ — so they are resolved for the profile actually being shown, never borrowed.
        val caps = playerCaps?.forProfile(profile.profileId, profile.selected)
        return ProfileContext(id, all, profile, m, soulStats, caps, fromCache, isSelf(id))
    }

    private fun visibleTabs(ctx: ProfileContext): List<ProfileTab> = ProfileTabs.ALL.filter { it.visible(ctx) }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        regions.clear()
        frame = g

        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()

        g.pose().pushMatrix()
        g.pose().scale(s, s)

        // Viewport-relative with a cap and no minimum: a fixed minimum overflows the (small)
        // composable viewport at high SoulUi.scale.
        val cardW = (uiW * 0.9f).toInt().coerceAtMost(900).coerceAtMost(uiW - 8)
        val cardH = (uiH * 0.88f).toInt().coerceAtMost(700).coerceAtMost(uiH - 8)
        val cardX0 = (uiW - cardW) / 2
        val cardY0 = (uiH - cardH) / 2
        val cardX1 = cardX0 + cardW
        val cardY1 = cardY0 + cardH

        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, cardX0, cardY0, cardX1, cardY1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)

        val ctx = if (state == STATE_LOADED) context() else null
        drawHeader(g, mx, my, cardX0, cardY0, cardX1, ctx)

        if (ctx == null) {
            // Loading / failed: no tab strip, the whole card below the header is the message.
            drawPlaceholder(g, mx, my, cardX0, cardY0 + HEADER_H, cardX1, cardY1)
        } else {
            val tabs = visibleTabs(ctx)
            if (tabs.none { it.id == activeTabId }) activeTabId = tabs.firstOrNull()?.id ?: ""
            drawTabStrip(g, mx, my, cardX0, cardY0 + HEADER_H, cardX1, tabs)
            val bodyY0 = cardY0 + HEADER_H + TABS_H
            drawBody(g, cardX0, bodyY0, cardX1, cardY1, tabs.firstOrNull { it.id == activeTabId }, ctx)
        }

        // The dropdown overlays everything, so it draws LAST and its regions therefore win the
        // reverse hit test — see SoulScreenBase's overlay gotcha.
        drawOpenDropdown(g, uiW, uiH, mx, my)

        g.pose().popMatrix()
        frame = null
    }

    private fun drawHeader(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        ctx: ProfileContext?,
    ) {
        roundRect(g, x0, y0, x1, y0 + HEADER_H, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fillGradient(x0, y0 + HEADER_H, x1, y0 + HEADER_H + 6, 0x50000000, 0x00000000)
        val bottom = y0 + HEADER_H

        // Close (right-most). `×` is U+00D7 — in the atlas for every family, unlike `✕`.
        val closeX0 = x1 - 8 - CLOSE_SIZE
        val closeY0 = y0 + (HEADER_H - CLOSE_SIZE) / 2
        val closeHover = mx in closeX0..(closeX0 + CLOSE_SIZE) && my in closeY0..(closeY0 + CLOSE_SIZE)
        if (closeHover) {
            roundRect(g, closeX0, closeY0, closeX0 + CLOSE_SIZE, closeY0 + CLOSE_SIZE, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        }
        drawTextV(
            g,
            "×",
            closeX0 + (CLOSE_SIZE - textWidth("×", fHeading)) / 2,
            closeY0,
            closeY0 + CLOSE_SIZE,
            if (closeHover) Theme.TEXT else Theme.TEXT_DIM,
            fHeading,
        )
        region(closeX0, closeY0, closeX0 + CLOSE_SIZE, closeY0 + CLOSE_SIZE) { onClose() }

        // Player name (left), then the SkyBlock Level badge — the metric that answers "how far along
        // is this account", so it stays visible next to the name rather than only inside a tab.
        val shownName = ctx?.identity?.name ?: loadingName.ifBlank { "Profile Viewer" }
        drawTextV(g, shownName, x0 + 16, y0, bottom, Theme.TEXT, fTitle, MsdfFonts.menuSemibold)
        var leftEdge = x0 + 16 + textWidth(shownName, fTitle, MsdfFonts.menuSemibold)
        val sbLevel = ctx?.member?.skyblockLevel
        if (sbLevel != null) {
            val badge = "SB ${sbLevel.level}"
            val bw = textWidth(badge, fCaption, MsdfFonts.menuSemibold) + 12
            val by0 = y0 + (HEADER_H - 16) / 2
            roundRect(g, leftEdge + 8, by0, leftEdge + 8 + bw, by0 + 16, Theme.ACCENT_DIM, SoulRenderPipelines.ROUND_PILL)
            drawTextV(g, badge, leftEdge + 14, by0, by0 + 16, 0xFFFFFFFF.toInt(), fCaption, MsdfFonts.menuSemibold)
            leftEdge += 8 + bw
        }
        if (ctx != null && ctx.fromCache) {
            drawTextV(g, "· cached", leftEdge + 8, y0, bottom, Theme.TEXT_FAINT, fCaption)
            leftEdge += 8 + textWidth("· cached", fCaption)
        }

        // Name field, then the profile switcher, laid out right-to-left from the close button.
        var rightEdge = closeX0 - 8
        val switcherW = if (ctx == null) 0 else switcherWidth(ctx)
        val room = rightEdge - (leftEdge + 12) - switcherW
        val fieldW = NAME_FIELD_W.coerceAtMost(room)
        if (fieldW >= NAME_FIELD_MIN_W) {
            val fieldY0 = y0 + (HEADER_H - SWITCHER_H) / 2
            drawTextBox(g, rightEdge - fieldW, fieldY0, rightEdge, fieldY0 + SWITCHER_H, nameQuery, "Look up…", nameFocused) {
                nameFocused = true
            }
            rightEdge -= fieldW + 12
        }
        if (ctx != null && switcherW > 0) drawSwitcher(g, rightEdge - switcherW, y0, ctx)
    }

    /** Token identifying this screen's one dropdown to [SoulScreenBase]. */
    private val switcherToken = Any()

    /** `Banana`, plus the game mode when it is not a normal profile. */
    private fun profileLabel(profile: SkyblockProfile): String {
        val mode = profile.gameMode?.takeIf { it.isNotBlank() }
        return profile.cuteName + if (mode == null) "" else " ($mode)"
    }

    /**
     * The dropdown list's wording. The currently-*shown* profile is the highlighted row, so the marker
     * here means the currently-**played** one — spelled out rather than starred, because `★` is in no
     * MSDF atlas and would draw nothing at all.
     */
    private fun profileListLabel(profile: SkyblockProfile): String =
        profileLabel(profile) + if (profile.selected) " · playing" else ""

    /** Sized for the longest entry, so switching profiles never resizes the control. */
    private fun switcherWidth(ctx: ProfileContext): Int {
        val widest = ctx.profiles.profiles.maxOfOrNull { textWidth(profileListLabel(it), fBody) } ?: 0
        return (widest + 28).coerceIn(90, 220)
    }

    /**
     * The profile switcher: a dropdown rather than a pair of arrows, so every profile is visible at
     * once and reaching the fourth one is one click instead of three.
     */
    private fun drawSwitcher(g: GuiGraphicsExtractor, x0: Int, y0: Int, ctx: ProfileContext) {
        val all = ctx.profiles.profiles
        val w = switcherWidth(ctx)
        val by0 = y0 + (HEADER_H - SWITCHER_H) / 2
        val by1 = by0 + SWITCHER_H
        // A single profile is not a choice — plain text, with no affordance inviting a click.
        if (all.size <= 1) {
            drawTextV(g, profileLabel(ctx.profile), x0 + 6, by0, by1, Theme.TEXT, fBody, MsdfFonts.menuSemibold)
            return
        }
        drawDropdownButton(g, x0, by0, x0 + w, by1, profileLabel(ctx.profile), isDropdownOpen(switcherToken))
        region(x0, by0, x0 + w, by1) {
            nameFocused = false
            if (isDropdownOpen(switcherToken)) {
                closeDropdown()
            } else {
                openDropdownAt(
                    switcherToken,
                    x0,
                    by0,
                    x0 + w,
                    by1,
                    all.map { profileListLabel(it) },
                    all.indexOfFirst { it.profileId == ctx.profile.profileId }.coerceAtLeast(0),
                ) { index -> showProfile(all.getOrNull(index)) }
            }
        }
    }

    private fun drawTabStrip(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        tabs: List<ProfileTab>,
    ) {
        g.fill(x0, y0 + TABS_H, x1, y0 + TABS_H + 1, Theme.SEPARATOR)
        var tx = x0 + 12
        val tabY0 = y0 + 4
        val tabY1 = y0 + TABS_H - 5
        for (tab in tabs) {
            val tw = textWidth(tab.label, fBody, MsdfFonts.menuSemibold) + 20
            if (tx + tw > x1 - 12) break // Never overflow the card; a hidden tab is better than a bled one.
            val active = tab.id == activeTabId
            val hovered = mx in tx..(tx + tw) && my in tabY0..tabY1
            if (active) {
                roundRect(g, tx, tabY0, tx + tw, tabY1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            } else if (hovered) {
                roundRect(g, tx, tabY0, tx + tw, tabY1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            }
            drawTextV(
                g,
                tab.label,
                tx + 10,
                tabY0,
                tabY1,
                if (active) 0xFFFFFFFF.toInt() else Theme.TEXT_DIM,
                fBody,
                MsdfFonts.menuSemibold,
            )
            val id = tab.id
            region(tx, tabY0, tx + tw, tabY1) {
                nameFocused = false
                if (id != activeTabId) {
                    activeTabId = id
                    bodyScroll = 0f
                }
            }
            tx += tw + 6
        }
    }

    private fun drawBody(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        tab: ProfileTab?,
        ctx: ProfileContext,
    ) {
        bodyRect = intArrayOf(x0, y0, x1, y1)
        if (tab == null) return

        beginClip(g, x0, y0, x1, y1)
        val top = y0 + 12 - bodyScroll.roundToInt()
        val canvas = ProfileCanvas(this, x0 + BODY_PAD, x1 - BODY_PAD - 6, top, y0, y1, dry = false)
        tab.draw(canvas, ctx)
        endClip(g)

        bodyContentH = (canvas.y + bodyScroll.roundToInt()) - (y0 + 12) + 12
        bodyScroll = clampScroll(bodyScroll, bodyContentH, y1 - y0)
        drawScrollbar(g, x1 - 8, y0 + 6, y1 - 6, y1 - y0)
    }

    /** A hairline scroll indicator — only drawn when there is something below the fold. */
    private fun drawScrollbar(g: GuiGraphicsExtractor, x: Int, y0: Int, y1: Int, viewportH: Int) {
        val overflow = bodyContentH - viewportH
        if (overflow <= 0) return
        val trackH = y1 - y0
        val thumbH = (trackH * viewportH.toFloat() / bodyContentH).roundToInt().coerceIn(16, trackH)
        val travel = trackH - thumbH
        val thumbY = y0 + (travel * (bodyScroll / overflow)).roundToInt().coerceIn(0, travel)
        roundRect(g, x, y0, x + 3, y1, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
        roundRect(g, x, thumbY, x + 3, thumbY + thumbH, Theme.TEXT_FAINT, SoulRenderPipelines.ROUND_PILL)
    }

    /** The loading and failure states — both a centred block where the tabs and body would be. */
    private fun drawPlaceholder(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val f = failure
        if (state == STATE_FAILED && f != null) {
            drawTextCentered(g, f.headline, cx, cy - 34, 0xFFE05252.toInt(), fHeading, MsdfFonts.menuSemibold)
            val detail = f.detail
            if (!detail.isNullOrBlank()) {
                val lines = wrapText(detail, (x1 - x0) - 80, fBody)
                lines.forEachIndexed { i, line ->
                    drawTextCentered(g, line, cx, cy - 14 + i * 14, Theme.TEXT_DIM, fBody)
                }
            }
            val btnY0 = cy + 34
            drawButton(g, mx, my, cx - 66, btnY0, cx - 6, btnY0 + 20, "Retry", accent = true) {
                ProfileViewerService.open(f.name)
            }
            drawButton(g, mx, my, cx + 6, btnY0, cx + 66, btnY0 + 20, "Close") { onClose() }
            return
        }

        val label = if (loadingName.isBlank()) "Loading…" else "Loading $loadingName…"
        drawTextCentered(g, label, cx, cy - 16, Theme.TEXT_DIM, fBody)
        // Indeterminate bar: we have no progress to report, so don't pretend to.
        val barW = 140
        val barX0 = cx - barW / 2
        val thumbW = 44
        val phase = ((System.currentTimeMillis() % 1400L) / 1400.0).toFloat()
        // Ping-pong so the thumb reads as activity rather than as a repeating jump.
        val t = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
        val thumbX = barX0 + ((barW - thumbW) * t).roundToInt()
        roundRect(g, barX0, cy + 2, barX0 + barW, cy + 6, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
        roundRect(g, thumbX, cy + 2, thumbX + thumbW, cy + 6, Theme.ACCENT, SoulRenderPipelines.ROUND_PILL)
    }

    // ------------------------------------------------------------------ ProfilePainter

    override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {
        val g = frame ?: return
        // A square corner has no SDF to compute — a plain fill is cheaper and pixel-exact.
        if (corner == ProfilePainter.CORNER_SQUARE) g.fill(x0, y0, x1, y1, color) else roundRect(g, x0, y0, x1, y1, color, pipelineFor(corner))
    }

    /** `when (Int)` is safe — the banned form is a subject `when` over an enum or sealed type. */
    private fun pipelineFor(corner: Int): RenderPipeline =
        when (corner) {
            ProfilePainter.CORNER_SMALL -> SoulRenderPipelines.ROUND_SMALL
            ProfilePainter.CORNER_PILL -> SoulRenderPipelines.ROUND_PILL
            else -> SoulRenderPipelines.ROUND_MEDIUM
        }

    override fun text(s: String, x: Int, y: Int, color: Int, size: Float, bold: Boolean) {
        val g = frame ?: return
        drawText(g, s, x, y, color, size, weightFor(bold))
    }

    override fun textV(s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
        val g = frame ?: return
        drawTextV(g, s, x, top, bottom, color, size, weightFor(bold))
    }

    override fun measure(s: String, size: Float, bold: Boolean): Int = textWidth(s, size, weightFor(bold))

    override fun wrap(s: String, maxW: Int, size: Float): List<String> = wrapText(s, maxW, size)

    private fun weightFor(bold: Boolean) = if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular

    // ------------------------------------------------------------------ input

    /** Show one of this player's other profiles. */
    private fun showProfile(profile: SkyblockProfile?) {
        val next = profile ?: return
        val id = identity ?: return
        // A profile the response has no member entry for cannot be shown; leave the current one up
        // rather than blanking the body. Hypixel only returns profiles the uuid belongs to, so this
        // is defensive.
        val nextMember = next.member(id.uuidUndashed) ?: return
        current = next
        member = nextMember
        bodyScroll = 0f
    }

    /** Look up whatever is in the name field, if it could be a username. */
    private fun submitName() {
        val typed = nameQuery.trim()
        nameFocused = false
        if (typed.isEmpty()) return
        if (!PlayerIdentityResolver.isValidName(typed)) {
            // Say so here rather than in chat: chat is behind this screen and cannot be read.
            accept(ProfileLookupFailure(typed, "Not a valid username", typed))
            return
        }
        ProfileViewerService.open(typed)
    }

    override fun onEmptyClick() {
        nameFocused = false
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (!nameFocused) return super.charTyped(event)
        if (!event.isAllowedChatCharacter()) return super.charTyped(event)
        val s = event.codepointAsString()
        // Only what a Minecraft username can contain; anything else is dropped silently.
        if (nameQuery.length < 16 && s.length == 1 && (s[0] == '_' || s[0] in 'a'..'z' || s[0] in 'A'..'Z' || s[0] in '0'..'9')) {
            nameQuery += s
        }
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // One Escape per thing to leave: an open dropdown first, then the field, then the screen.
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && dropdownOpen) {
            closeDropdown()
            return true
        }
        if (nameFocused) {
            when (event.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    nameQuery = nameQuery.dropLast(1)
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    submitName()
                    return true
                }
                // Escape leaves the field without closing the screen — one Escape per thing to leave.
                GLFW.GLFW_KEY_ESCAPE -> {
                    nameFocused = false
                    nameQuery = ""
                    return true
                }
            }
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
