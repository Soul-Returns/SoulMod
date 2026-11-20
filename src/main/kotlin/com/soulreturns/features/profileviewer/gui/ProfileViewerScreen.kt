package com.soulreturns.features.profileviewer.gui

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.PlayerIdentityResolver
import com.soulreturns.data.hypixel.PlayerSkillCaps
import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.activeMemberCount
import com.soulreturns.features.party.PartyManager
import com.soulreturns.features.profileviewer.ProfileLookupFailure
import com.soulreturns.features.profileviewer.ProfileView
import com.soulreturns.features.profileviewer.ProfileViewerService
import com.soulreturns.features.profileviewer.gui.ProfileViewerLayout as L
import com.soulreturns.features.profileviewer.gui.ProfileViewerState as St
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.PlayerModel
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.OverviewSection
import com.soulreturns.features.profileviewer.gui.sections.SoulSection
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Profile Viewer — `/spv`, spec `mocks/profile-viewer/`. A centered card: header (who, which
 * profile, player search, status), a section rail, and the active [ProfileSection]'s body built
 * with the card toolkit (`gui/kit/`).
 *
 * ### Staged loading
 * The screen opens on the *start* of a lookup ([ProfileViewerService.onLookupStarted]), shows the
 * face and exact name as soon as the name resolves ([ProfileViewerService.onResolved]), then every
 * profile at once ([ProfileViewerService.present]); Soul stats and skill caps follow per player.
 * Failures arrive through [ProfileViewerService.presentFailure] and pick a state in
 * [ProfileViewerState.shell] — offline and rate-limited keep (or bring back) what was loaded before.
 *
 * ### State is read every frame
 * Every field is written on the client thread (each seam hops there first) and re-read by
 * [extractRenderState]; sections are rebuilt every frame from [ProfileContext], so switching
 * profile or section just assigns a field.
 */
class ProfileViewerScreen private constructor(
    private val parent: Screen?,
) : SoulScreenBase(Component.literal("Soul Profile Viewer")) {

    companion object {
        private val logger = SoulLogger("Soul/ProfileViewer")

        /** Point [ProfileViewerService]'s presenter seams at this screen. Called once, from [com.soulreturns.Soul]. */
        fun register() {
            ProfileViewerService.onLookupStarted = { name -> onClient { beginLookup(name) } }
            ProfileViewerService.onResolved = { identity -> onClient { current()?.resolvedTo(identity) } }
            ProfileViewerService.present = { view -> onClient { deliver(view) } }
            ProfileViewerService.presentFailure = { failure -> onClient { deliverFailure(failure) } }
        }

        private const val SCROLL_STEP = 24
        private const val POP_SEARCH = "search"
        private const val POP_PROFILE = "profile"
        private const val POP_KEYS = "keys"
        private const val POP_PAD = 5
        private const val SEARCH_POP_W = 250
        private const val PROFILE_POP_W = 316
        private const val KEYS_POP_W = 240
        private const val SEARCH_ROW_H = 28
        private const val SEARCH_GROUP_H = 22
        private const val PROFILE_ROW_H = 48
        private const val KEYS_ROW_H = 20

        /** Views fetched this session, by lower-case name: what offline and rate-limited fall back to. */
        private const val CACHED_VIEWS = 8

        private class Cached(val view: ProfileView, val at: Long, val soul: SoulStatsAnswer?, val caps: PlayerSkillCaps?, val capsLoaded: Boolean)

        private val cache = object : LinkedHashMap<String, Cached>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > CACHED_VIEWS
        }

        private fun onClient(block: () -> Unit) {
            val mc = Minecraft.getInstance()
            if (mc.isSameThread) block() else mc.execute(block)
        }

        private fun current(): ProfileViewerScreen? = Minecraft.getInstance().gui?.screen() as? ProfileViewerScreen

        /**
         * A lookup started: show it. Reuses the open screen; otherwise opens one through `execute`,
         * because a `/spv` typed into chat is dispatched before the chat screen tears itself down.
         */
        private fun beginLookup(name: String) {
            if (!enabled()) return
            current()?.let {
                it.startLoading(name)
                return
            }
            val mc = Minecraft.getInstance()
            val screen = ProfileViewerScreen(null)
            screen.startLoading(name)
            mc.execute { mc.setScreenAndShow(screen) }
        }

        /** A profile arrived. If the player closed the screen meanwhile, that was a cancellation. */
        private fun deliver(view: ProfileView) {
            current()?.let {
                it.accept(view)
                return
            }
            if (!enabled()) return
            val mc = Minecraft.getInstance()
            val screen = ProfileViewerScreen(null)
            screen.accept(view)
            mc.execute { mc.setScreenAndShow(screen) }
        }

        /** Only ever fills an open screen: the chat line has gone out regardless. */
        private fun deliverFailure(failure: ProfileLookupFailure) {
            current()?.accept(failure)
        }

        private fun enabled(): Boolean = try {
            cfg.profileViewer.enabled
        } catch (_: Throwable) {
            true
        }

        private fun selfUuid(): String? = try {
            MojangUuid.undashed(Minecraft.getInstance().user.profileId)
        } catch (_: Throwable) {
            null
        }

        private fun selfName(): String = Minecraft.getInstance().user.name
    }

    // ------------------------------------------------------------------ state

    private var lookupName = ""
    private var loading = false
    private var resolved: PlayerIdentity? = null
    private var failure: ProfileLookupFailure? = null
    private var view: ProfileView? = null
    private var profile: SkyblockProfile? = null
    private var member: SkyblockProfileMember? = null
    private var isSelfView = false
    private var loadedAt = 0L
    private var retryUntil = 0L
    private var soulAnswer: SoulStatsAnswer? = null
    private var playerCaps: PlayerSkillCaps? = null
    private var capsLoaded = false

    private var sectionId = OverviewSection.id
    private val scrolls = HashMap<String, ScrollState>()
    private val railScroll = ScrollState()
    private val viewState = ViewState()
    private val search = SoulTextField()
    private var popover: String? = null
    private var searchHl = -1

    // ------------------------------------------------------------------ transitions

    private fun startLoading(name: String) {
        val same = view?.identity?.name?.equals(name, ignoreCase = true) == true
        lookupName = name
        loading = true
        failure = null
        popover = null
        search.focused = false
        if (!same) {
            // A different player: drop the previous one entirely, never a half-updated screen.
            view = null
            profile = null
            member = null
            resolved = null
            soulAnswer = null
            playerCaps = null
            capsLoaded = false
            viewState.clear()
            scrolls.clear()
            figure = null
        }
    }

    private fun resolvedTo(identity: PlayerIdentity) {
        if (loading && identity.name.equals(lookupName, ignoreCase = true)) resolved = identity
    }

    private fun accept(v: ProfileView) {
        val same = view?.identity?.uuidUndashed == v.identity.uuidUndashed
        val keepProfile = if (same) profile?.profileId?.let { id -> v.profiles.profiles.firstOrNull { it.profileId == id } } else null
        val shown = keepProfile ?: v.profile
        view = v
        profile = shown
        member = shown.member(v.identity.uuidUndashed) ?: v.member
        resolved = v.identity
        lookupName = v.identity.name
        isSelfView = selfUuid()?.equals(v.identity.uuidUndashed, ignoreCase = true) == true
        loadedAt = System.currentTimeMillis() - (v.preloaded?.loadedAgoMs ?: 0L)
        failure = null
        loading = false
        if (!isSelfView) RecentPlayers.remember(v.identity.name, v.identity.uuidUndashed)
        val pre = v.preloaded
        if (pre != null) {
            soulAnswer = pre.soulStats
            playerCaps = pre.caps
            capsLoaded = true
            remember()
        } else {
            if (!same) {
                soulAnswer = null
                playerCaps = null
                capsLoaded = false
            }
            loadExtras(v.identity)
        }
    }

    private fun accept(f: ProfileLookupFailure) {
        loading = false
        failure = f
        f.identity?.let { resolved = it }
        val now = System.currentTimeMillis()
        if (f.kind == ProfileLookupFailure.RATE_LIMITED) retryUntil = now + (f.retryAfterSeconds ?: St.DEFAULT_RETRY_S) * 1000
        val fallback = f.kind == ProfileLookupFailure.RATE_LIMITED || f.kind == ProfileLookupFailure.OFFLINE
        if (fallback && view?.identity?.name?.equals(f.name, ignoreCase = true) == true) return
        val cached = if (fallback) synchronized(cache) { cache[f.name.lowercase()] } else null
        if (cached != null) {
            view = cached.view
            profile = cached.view.profile
            member = cached.view.member
            resolved = cached.view.identity
            isSelfView = selfUuid()?.equals(cached.view.identity.uuidUndashed, ignoreCase = true) == true
            loadedAt = cached.at
            soulAnswer = cached.soul
            playerCaps = cached.caps
            capsLoaded = cached.capsLoaded
            return
        }
        view = null
        profile = null
        member = null
    }

    /** Soul stats and skill caps: separate requests with their own failure models; none blocks the screen. */
    private fun loadExtras(who: PlayerIdentity) {
        val own = isSelfView
        SoulScope.io.launch {
            val stats = try {
                SoulStatsClient.fetch(who.uuidUndashed, ownProfile = own)
            } catch (_: Throwable) {
                SoulStatsAnswer(null, SoulStatsClient.CODE_UNAVAILABLE)
            }
            onClient { if (view?.identity?.uuidUndashed == who.uuidUndashed) soulAnswer = stats }
            val caps = try {
                SkillCapsClient.caps(who.uuidUndashed)
            } catch (_: Throwable) {
                null
            }
            onClient {
                if (view?.identity?.uuidUndashed == who.uuidUndashed) {
                    playerCaps = caps
                    capsLoaded = true
                    remember()
                }
            }
        }
    }

    private fun remember() {
        val v = view ?: return
        synchronized(cache) { cache[v.identity.name.lowercase()] = Cached(v, loadedAt, soulAnswer, playerCaps, capsLoaded) }
    }

    private fun showProfile(p: SkyblockProfile) {
        val id = view?.identity ?: return
        val m = p.member(id.uuidUndashed) ?: return
        profile = p
        member = m
        popover = null
        scrolls.clear()
    }

    private fun lookup(name: String) {
        val n = name.trim()
        popover = null
        search.focused = false
        search.set("")
        if (n.isEmpty()) return
        if (!PlayerIdentityResolver.isValidName(n)) {
            startLoading(n)
            accept(ProfileLookupFailure(n, "Not a valid username", n, ProfileLookupFailure.INVALID_NAME))
            return
        }
        ProfileViewerService.open(n)
    }

    private fun refresh() {
        if (retryUntil > System.currentTimeMillis()) return
        val name = view?.identity?.name ?: lookupName.ifEmpty { return }
        ProfileViewerService.open(name, profile?.cuteName)
    }

    private fun goSection(id: String) {
        sectionId = id
        popover = null
    }

    private fun context(now: Long): ProfileContext? {
        val v = view ?: return null
        val p = profile ?: return null
        val m = member ?: return null
        return ProfileContext(
            v.identity, v.profiles, p, m, isSelfView,
            playerCaps?.forProfile(p.profileId, p.selected), capsLoaded, soulAnswer, now,
            opener = ::goSection,
            looker = ::lookup,
        )
    }

    private fun visibleSections(ctx: ProfileContext?): List<ProfileSection> =
        if (ctx == null) ProfileSections.ALL else ProfileSections.ALL.filter { safe(true) { it.visible(ctx) } }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        logger.warn("Profile Viewer section failed: ${e.message}")
        fallback
    }

    // ------------------------------------------------------------------ frame

    private var frame: GuiGraphicsExtractor? = null
    private var mx = 0
    private var my = 0
    private var now = 0L
    private val bodyRect = IntArray(4)
    private var pendingItem: ItemStack? = null
    private var searchAnchor = IntArray(4)
    private var profileAnchor = IntArray(4)
    private var keysAnchor = IntArray(4)
    private val faces = HashMap<String, ResolvableProfile>()
    private var figure: ProfileFigure? = null
    private var rawMouseX = 0f
    private var rawMouseY = 0f

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        scrollTargets.clear()
        frame = g
        pendingItem = null
        now = System.currentTimeMillis()
        val s = SoulUi.factor()
        val uiW = width / s
        val uiH = height / s
        mx = (mouseX / s).toInt()
        my = (mouseY / s).toInt()
        rawMouseX = mouseX.toFloat()
        rawMouseY = mouseY.toFloat()
        g.pose().pushMatrix()
        g.pose().scale(s, s)

        val card = L.card(uiW, uiH)
        val ctx = context(now)
        val hasView = ctx != null
        val shell = St.shell(loading, resolved != null || view != null, hasView, failure?.kind, now, loadedAt, retryUntil)

        g.fill(0, 0, uiW.toInt() + 1, uiH.toInt() + 1, Theme.DIM)
        roundRect(g, card.x0, card.y0, card.x1, card.y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)

        val mainX0 = if (shell.rail) card.x0 + card.railW else card.x0
        if (shell.rail) drawRail(g, card, ctx)
        drawMain(g, card, mainX0, ctx, shell)
        drawHeader(g, card, ctx, shell)

        // Popovers overlay everything, so they draw LAST and their regions win the reverse hit test.
        if (popover != null) {
            region(0, 0, uiW.toInt(), uiH.toInt()) { popover = null }
            drawPopover(g, card, ctx)
        }
        drawTooltip(g, uiW.toInt(), uiH.toInt())
        g.pose().popMatrix()
        pendingItem?.let { if (popover == null) g.setTooltipForNextFrame(Minecraft.getInstance().font, it, mouseX, mouseY) }
        frame = null
    }

    // ------------------------------------------------------------------ header

    private fun drawHeader(g: GuiGraphicsExtractor, card: L.Card, ctx: ProfileContext?, shell: St.Shell) {
        val y0 = card.y0
        val y1 = y0 + L.HEAD_H
        roundRect(g, card.x0, y0, card.x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fillGradient(card.x0, y1, card.x1, y1 + L.HEAD_SHADOW, 0x50000000, 0x00000000)
        val top = y0 + (L.HEAD_H - L.CONTROL_H) / 2
        val bottom = top + L.CONTROL_H

        // Right to left: close, keys, status, You, search.
        var rx = card.x1 - L.HEAD_PAD_R
        rx -= L.CONTROL_H
        iconButton(g, rx, top, L.CONTROL_H, "pv_close", "Close (Esc)", danger = true) { onClose() }
        rx -= L.HEAD_GAP + L.CONTROL_H
        keysAnchor = intArrayOf(rx, top, rx + L.CONTROL_H, bottom)
        iconButton(g, rx, top, L.CONTROL_H, "pv_keys", "Keyboard shortcuts (?)", active = popover == POP_KEYS) { popover = if (popover == POP_KEYS) null else POP_KEYS }
        val statusW = statusWidth(shell, card.searchCollapsed)
        if (statusW > 0) {
            rx -= L.HEAD_GAP + statusW
            drawStatus(g, rx, top, bottom, shell, card.searchCollapsed)
        }
        val showYou = !isSelfView || view == null
        if (showYou) {
            val label = if (card.searchCollapsed) "" else "You"
            val w = 8 + 14 + (if (label.isEmpty()) 0 else 5 + tw(label, KitStyle.BODY, true)) + 8
            rx -= L.HEAD_GAP + w
            val bx = rx
            val hover = hit(bx, top, bx + w, bottom)
            rect(bx, top, bx + w, bottom, Theme.FIELD_BORDER, Corner.SMALL)
            rect(bx + 1, top + 1, bx + w - 1, bottom - 1, if (hover) Theme.PANEL_HOVER else Theme.TOP_NAV, Corner.SMALL)
            selfUuid()?.let { face(g, it, bx + 8, top + 6, 14) }
            if (label.isNotEmpty()) txt(label, bx + 8 + 14 + 5, top, bottom, if (hover) Theme.TEXT else KitStyle.TEXT_MID, KitStyle.BODY, true)
            if (hover) tooltip(bx, top, bx + w, bottom, listOf("Your profile (Ctrl+M)"))
            region(bx, top, bx + w, bottom) { lookup(selfName()) }
        }
        val searchOpen = popover == POP_SEARCH || search.focused
        if (card.searchCollapsed && !searchOpen) {
            rx -= L.HEAD_GAP + L.CONTROL_H
            searchAnchor = intArrayOf(rx, top, rx + L.CONTROL_H, bottom)
            iconButton(g, rx, top, L.CONTROL_H, "pv_search", "Look up a player (Ctrl+F)") { openSearch() }
        } else {
            rx -= L.HEAD_GAP + card.searchW
            searchAnchor = intArrayOf(rx, top, rx + card.searchW, bottom)
            drawTextField(g, rx, top, rx + card.searchW, bottom, search, "Look up a player", inset = 24, background = Theme.FIELD_BG, border = Theme.FIELD_BORDER) {
                viewState.blurFields()
                popover = POP_SEARCH
                searchHl = -1
            }
            drawIcon(g, "pv_search", rx + 8, top + 7, 12, if (search.focused) Theme.TEXT else Theme.TEXT_DIM)
            if (!card.searchCollapsed && search.text.isEmpty() && !search.focused) kbd(g, "Ctrl F", rx + card.searchW - 6, (top + bottom) / 2, alignRight = true)
        }
        val rightEdge = rx - L.HEAD_GAP

        // Left: face, name and SkyBlock Level, then the profile switcher.
        var lx = card.x0 + L.HEAD_PAD_L
        val known = (resolved != null || view != null) && shell.body != St.BODY_NOT_FOUND
        val who = view?.identity ?: resolved
        val fy = y0 + (L.HEAD_H - L.FACE) / 2
        if (known && who != null) face(g, who.uuidUndashed, lx, fy, L.FACE) else skeleton(lx, fy, lx + L.FACE, fy + L.FACE)
        lx += L.FACE + 9
        val name = if (known && who != null) who.name else lookupName
        val nameW = tw(name, KitStyle.HEADING, true)
        txt(name, lx, y0 + 11, y0 + 26, Theme.TEXT, KitStyle.HEADING, true)
        var idW = nameW
        if (known && isSelfView && view != null) idW += Chip.you().let { 5 + it.paint(canvas, lx + nameW + 5, y0 + 18) }
        val subTop = y0 + 27
        val sb = member?.skyblockLevel
        if (shell.body == St.BODY_SECTION && sb != null) {
            val lv = "${sb.level}"
            val lvW = tw(lv, KitStyle.CAPTION, true)
            txt(lv, lx, subTop, subTop + 12, Format.levelColor(sb.level), KitStyle.CAPTION, true)
            txt(" SkyBlock Level", lx + lvW, subTop, subTop + 12, Theme.TEXT_DIM, KitStyle.CAPTION)
            idW = max(idW, lvW + tw(" SkyBlock Level", KitStyle.CAPTION, false))
        } else {
            val sub = when (shell.body) {
                St.BODY_NOT_FOUND -> "Not found"
                St.BODY_NO_PROFILES -> "No SkyBlock profiles"
                St.BODY_OFFLINE_EMPTY, St.BODY_RATE_LIMITED_EMPTY, St.BODY_ERROR -> "Not loaded"
                else -> null
            }
            if (sub != null) txt(sub, lx, subTop, subTop + 12, Theme.TEXT_DIM, KitStyle.CAPTION) else skeleton(lx, subTop + 2, lx + 90, subTop + 9)
        }
        lx += idW + L.HEAD_GAP
        if (ctx != null && shell.body == St.BODY_SECTION) {
            drawProfileButton(g, ctx, lx, top, bottom, rightEdge)
        } else if (known && loading) {
            val cy = (top + bottom) / 2
            skeleton(lx, cy - 4, lx + 120, cy + 4)
        }
    }

    private fun drawProfileButton(g: GuiGraphicsExtractor, ctx: ProfileContext, x0: Int, top: Int, bottom: Int, maxX: Int) {
        val p = ctx.profile
        val multi = ctx.profiles.profiles.size > 1
        val mode = Chip.gameMode(p.gameMode)
        val count = "${ctx.profiles.profiles.size}"
        var w = 10 + tw(p.cuteName, KitStyle.BODY, true) + (mode?.let { 5 + it.width(canvas) } ?: 0) +
            (if (p.selected) 5 + tw("· playing", KitStyle.CAPTION, false) else 0) +
            (if (multi) 8 + tw(count, KitStyle.LABEL, false) + 6 + 5 + 10 else 0) + 8
        w = min(w, max(60, maxX - x0))
        val x1 = x0 + w
        val open = popover == POP_PROFILE
        profileAnchor = intArrayOf(x0, top, x1, bottom)
        if (multi) {
            val hover = hit(x0, top, x1, bottom)
            rect(x0, top, x1, bottom, if (hover || open) Theme.ACCENT else Theme.FIELD_BORDER, Corner.SMALL)
            rect(x0 + 1, top + 1, x1 - 1, bottom - 1, 0xFF202020.toInt(), Corner.SMALL)
            if (hover) tooltip(x0, top, x1, bottom, listOf("Switch profile (Ctrl+P)"))
            region(x0, top, x1, bottom) { popover = if (open) null else POP_PROFILE }
        }
        var cx = x0 + 10
        txt(p.cuteName, cx, top, bottom, Theme.TEXT, KitStyle.BODY, true)
        cx += tw(p.cuteName, KitStyle.BODY, true)
        if (mode != null) cx += 5 + mode.paint(canvas, cx + 5, (top + bottom) / 2)
        if (p.selected) {
            txt("· playing", cx + 5, top, bottom, Theme.TEXT_FAINT, KitStyle.CAPTION)
            cx += 5 + tw("· playing", KitStyle.CAPTION, false)
        }
        if (multi) {
            val cw = tw(count, KitStyle.LABEL, false) + 6
            rect(cx + 8, top + 7, cx + 8 + cw, bottom - 7, 0xFF1A1A1A.toInt(), Corner.SMALL)
            txt(count, cx + 11, top, bottom, Theme.TEXT_DIM, KitStyle.LABEL)
            drawIcon(g, "pv_caret", cx + 8 + cw + 5, top + 8, 10, Theme.TEXT_DIM)
        }
    }

    /** `.status`: a 6 px dot plus its 2 px margin, or the 10 px spinner while loading. */
    private fun statusLead(shell: St.Shell): Int = if (shell.status == St.STATUS_LOADING) 10 else 8

    private fun statusWidth(shell: St.Shell, narrow: Boolean): Int {
        if (shell.status == St.STATUS_NONE) return 0
        val text = if (narrow) "" else statusText(shell)
        val textW = if (text.isEmpty()) 0 else 4 + tw(text, KitStyle.CAPTION, false)
        return statusLead(shell) + textW + (if (shell.status == St.STATUS_LOADING) 0 else 4 + 18)
    }

    private fun statusText(shell: St.Shell): String = when (shell.status) {
        St.STATUS_LOADING -> "Loading…"
        St.STATUS_FRESH -> "Updated ${Format.ago(now - loadedAt)}"
        St.STATUS_STALE -> "Loaded ${Format.ago(now - loadedAt)}"
        St.STATUS_RATE_LIMITED -> "Wait ${max(1L, (retryUntil - now + 999) / 1000)} s"
        St.STATUS_OFFLINE -> "Offline"
        else -> ""
    }

    private fun drawStatus(g: GuiGraphicsExtractor, x0: Int, top: Int, bottom: Int, shell: St.Shell, narrow: Boolean) {
        val color = when (shell.status) {
            St.STATUS_STALE, St.STATUS_RATE_LIMITED -> KitStyle.MAXED
            St.STATUS_OFFLINE -> KitStyle.ERROR
            St.STATUS_LOADING -> Theme.ACCENT
            else -> 0xFF3FAE5C.toInt()
        }
        val cy = (top + bottom) / 2
        if (shell.status == St.STATUS_LOADING) spinner(g, x0, cy - 5) else rect(x0, cy - 3, x0 + 6, cy + 3, color, Corner.PILL)
        val text = if (narrow) "" else statusText(shell)
        var x = x0 + statusLead(shell)
        if (text.isNotEmpty()) {
            x += 4
            txt(text, x, top, bottom, if (shell.status == St.STATUS_FRESH || shell.status == St.STATUS_LOADING) Theme.TEXT_DIM else color, KitStyle.CAPTION)
            x += tw(text, KitStyle.CAPTION, false)
        }
        val tip = when (shell.status) {
            St.STATUS_FRESH -> "Loaded from Hypixel ${Format.ago(now - loadedAt)}. Press R to refresh."
            St.STATUS_STALE -> "This data is from earlier. Refresh to load it again."
            St.STATUS_RATE_LIMITED -> "Soul's server limits lookups to 30 a minute."
            St.STATUS_OFFLINE -> "Can't reach Soul's server. Showing data from ${Format.ago(now - loadedAt)}."
            else -> null
        }
        if (tip != null && hit(x0, top, x, bottom)) tooltip(x0, top, x, bottom, listOf(tip), "status")
        if (shell.status != St.STATUS_LOADING) {
            val disabled = shell.status == St.STATUS_RATE_LIMITED
            val by = cy - 9
            val hover = !disabled && hit(x + 4, by, x + 22, by + 18)
            if (hover) rect(x + 4, by, x + 22, by + 18, Theme.PANEL_HOVER, Corner.SMALL)
            drawIcon(g, "pv_refresh", x + 4 + 4, by + 4, 11, if (disabled) 0x59AAAAAA else if (hover) Theme.TEXT else KitStyle.TEXT_MID)
            if (!disabled) region(x + 4, by, x + 22, by + 18) { refresh() }
        }
    }

    /**
     * `.spin`: a 10 px ring with a blue quarter turning once per 0.8 s. The render layer has no conic
     * fill, so the quarter is the ring clipped to one quadrant, stepping a quadrant every 0.2 s.
     */
    private fun spinner(g: GuiGraphicsExtractor, x: Int, y: Int) {
        rect(x, y, x + 10, y + 10, 0xFF2A2A2A.toInt(), Corner.PILL)
        val q = ((now % 800) / 200).toInt()
        val qx = if (q == 1 || q == 2) x + 5 else x
        val qy = if (q >= 2) y + 5 else y
        g.enableScissor(qx, qy, qx + 5, qy + 5)
        rect(x, y, x + 10, y + 10, Theme.ACCENT, Corner.PILL)
        g.disableScissor()
        rect(x + 3, y + 3, x + 7, y + 7, Theme.TOP_NAV, Corner.PILL)
    }

    // ------------------------------------------------------------------ rail

    private fun drawRail(g: GuiGraphicsExtractor, card: L.Card, ctx: ProfileContext?) {
        val x0 = card.x0
        val x1 = x0 + card.railW
        val y0 = card.y0 + L.HEAD_H
        val y1 = card.y1
        roundRect(g, x0, y0, x1, y1, Theme.SIDEBAR, SoulRenderPipelines.ROUND_BL)
        val sections = visibleSections(ctx)
        val (rows, rowsH) = L.railRows(sections.map { it.id to it.group }, card.miniRail)
        // `.legend` is the last thing in the rail's scroll content, pushed to the bottom when there is room.
        val legendY = if (card.miniRail) -1 else max(rowsH - L.RAIL_PAD_Y + L.RAIL_GAP, y1 - y0 - L.RAIL_LEGEND_H - L.RAIL_PAD_Y)
        railScroll.view = y1 - y0
        railScroll.content = if (legendY < 0) rowsH else legendY + L.RAIL_LEGEND_H + L.RAIL_PAD_Y
        railScroll.clamp()
        val pad = if (card.miniRail) L.RAIL_PAD_X_MINI else L.RAIL_PAD_X
        val vp = intArrayOf(x0, y0, x1, y1)
        beginClip(g, x0, y0, x1, y1)
        val off = y0 - railScroll.pos.roundToInt()
        for (row in rows) {
            val ry = off + row.y
            if (row.kind == L.ROW_GROUP) {
                val label = row.label!!.uppercase()
                val lw = trackedW(label, KitStyle.LABEL, KitStyle.CHIP_TRACKING)
                tracked(g, label, x0 + pad + 8 + lw / 2, ry + 16, Theme.TEXT_FAINT, KitStyle.LABEL, KitStyle.CHIP_TRACKING)
                continue
            }
            if (row.kind == L.ROW_SEP) {
                g.fill(x0 + pad + 4, ry + 6, x1 - pad - 4, ry + 7, Theme.SEPARATOR)
                continue
            }
            val s = sections.first { it.id == row.sectionId }
            val ix0 = x0 + pad
            val ix1 = x1 - pad
            val on = s.id == sectionId
            val hover = hit(ix0, ry, ix1, ry + row.h) && my in y0 until y1
            val soul = s.group == ProfileSection.GROUP_SOUL
            val muted = ctx != null && safe(false) { s.muted(ctx) }
            if (on) rect(ix0, ry, ix1, ry + row.h, Theme.ACCENT, Corner.SMALL) else if (hover) rect(ix0, ry, ix1, ry + row.h, Theme.PANEL_HOVER, Corner.SMALL)
            val color = if (on) 0xFFFFFFFF.toInt() else if (hover) Theme.TEXT else if (soul && !muted) Theme.ACCENT_LIGHT else Theme.TEXT_DIM
            val lock = ctx?.let { safe(null) { s.locked(it) } }
            if (card.miniRail) {
                drawIcon(g, s.icon, (ix0 + ix1) / 2 - 7, ry + 6, 14, color)
                if (lock != null) drawIcon(g, "pv_lock", ix1 - 11, ry + row.h - 11, 8, Theme.TEXT_FAINT)
                if (hover) tooltip(ix0, ry, ix1, ry + row.h, listOf(s.title))
            } else {
                drawIcon(g, s.icon, ix0 + 8, ry + 6, 14, color)
                val badge = if (lock == null && ctx != null) safe(null) { s.badge(ctx) } else null
                val rightW = if (lock != null) 10 else badge?.let { tw(it, KitStyle.CAPTION, false) } ?: 0
                val labelX = ix0 + 8 + 14 + 9
                txt(ellipsize(s.title, ix1 - 8 - rightW - 6 - labelX, KitStyle.BODY, weight(true)), labelX, ry, ry + row.h, color, KitStyle.BODY, true)
                if (lock != null) {
                    drawIcon(g, "pv_lock", ix1 - 8 - 10, ry + 8, 10, Theme.TEXT_FAINT)
                    if (hit(ix1 - 20, ry, ix1, ry + row.h)) tooltip(ix1 - 20, ry, ix1, ry + row.h, listOf(lock))
                } else if (badge != null) {
                    txt(badge, ix1 - 8 - rightW, ry, ry + row.h, if (on) 0xB3FFFFFF.toInt() else Theme.TEXT_FAINT, KitStyle.CAPTION)
                }
            }
            clippedRegion(ix0, ry, ix1, ry + row.h, vp) { goSection(s.id) }
        }
        if (legendY >= 0) {
            val ly = off + legendY
            g.fill(x0 + pad, ly, x1 - pad, ly + 1, 0xFF222222.toInt())
            val chip = Chip.soul()
            val cx = x0 + pad + 6
            chip.paint(canvas, cx, ly + 9 + 6)
            val tx = cx + chip.width(canvas) + 6
            txt("recorded by Soul,", tx, ly + 9, ly + 20, Theme.TEXT_FAINT, KitStyle.TINY)
            txt("not by Hypixel", tx, ly + 20, ly + 31, Theme.TEXT_FAINT, KitStyle.TINY)
        }
        endClip(g)
        drawScrollbar(g, mx, my, railScroll, x0, y0, x1, y1)
    }

    // ------------------------------------------------------------------ main area

    private fun drawMain(g: GuiGraphicsExtractor, card: L.Card, x0: Int, ctx: ProfileContext?, shell: St.Shell) {
        val x1 = card.x1
        val y0 = card.y0 + L.HEAD_H
        val y1 = card.y1
        if (shell.body == St.BODY_SECTION && ctx != null) {
            drawSection(g, x0, y0, x1, y1, ctx, shell)
        } else if (shell.body == St.BODY_LOADING || shell.body == St.BODY_RESOLVING) {
            drawSkeleton(x0 + L.BODY_PAD_X, y0 + L.SEC_HEAD_PAD_TOP, x1 - L.BODY_PAD_X, y1 - L.BODY_PAD_BOTTOM)
        } else {
            drawCenterState(g, x0, y0, x1, y1, shell)
        }
        // `.pv-main::before`: the rail's 6 px shadow over the main area, rgba(0,0,0,.25) to transparent.
        if (shell.rail) for (i in 0 until 6) g.fill(x0 + i, y0, x0 + i + 1, y1, (0x40 * (6 - i) / 6) shl 24)
    }

    private fun drawSection(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, ctx: ProfileContext, shell: St.Shell) {
        val sections = visibleSections(ctx)
        val section = sections.firstOrNull { it.id == sectionId } ?: sections.first().also { sectionId = it.id }
        val soul = section.group == ProfileSection.GROUP_SOUL

        // Section header.
        val hTop = y0 + L.SEC_HEAD_PAD_TOP
        val hBottom = hTop + L.SEC_HEAD_H
        var hx = x0 + L.BODY_PAD_X
        drawIcon(g, section.icon, hx, hTop, 14, if (soul) Theme.ACCENT_LIGHT else Theme.TEXT_DIM)
        hx += 14 + 8
        txt(section.title, hx, hTop, hBottom, if (soul) Theme.ACCENT_LIGHT else Theme.TEXT, KitStyle.HEADING, true)
        hx += tw(section.title, KitStyle.HEADING, true) + 8
        var kx = x1 - L.BODY_PAD_X
        kx -= kbd(g, "PgDn", kx, (hTop + hBottom) / 2, alignRight = true) + 3
        kx -= kbd(g, "PgUp", kx, (hTop + hBottom) / 2, alignRight = true) + 8
        section.subtitle?.let { txt(ellipsize(it, kx - hx, KitStyle.CAPTION, weight(false)), hx, hTop, hBottom, Theme.TEXT_DIM, KitStyle.CAPTION) }

        // Banner.
        var top = hBottom + L.SEC_HEAD_PAD_BOTTOM
        if (shell.banner != St.BANNER_NONE) {
            drawBanner(g, x0 + L.BODY_PAD_X, top, x1 - L.BODY_PAD_X, shell)
            top += L.BANNER_H + L.BANNER_GAP
        }

        // Body: build, measure, scroll, paint.
        val bx0 = x0 + L.BODY_PAD_X
        val bx1 = x1 - L.BODY_PAD_X
        val bw = bx1 - bx0
        val ui = Ui(section.id, viewState)
        try {
            section.build(ctx, ui)
        } catch (e: Throwable) {
            logger.warn("Section ${section.id} failed to build: ${e.message}")
            ui.nodes.clear()
            ui.empty("pv_warn", "This section couldn't be drawn", "Something in ${ctx.name}'s profile surprised it. Other sections still work.")
        }
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val contentH = root.height(canvas, bw)
        val st = scrolls.getOrPut(section.id) { ScrollState() }
        st.view = y1 - top
        st.content = L.BODY_PAD_TOP + contentH + L.BODY_PAD_BOTTOM
        st.clamp()
        bodyRect[0] = x0
        bodyRect[1] = top
        bodyRect[2] = x1
        bodyRect[3] = y1
        canvas.viewTop = top
        canvas.viewBottom = y1
        beginClip(g, x0, top, x1, y1)
        root.paint(canvas, bx0, top + L.BODY_PAD_TOP - st.pos.roundToInt(), bw, contentH)
        endClip(g)
        drawScrollbar(g, mx, my, st, x0, top, x1 - 4, y1 - 4)
    }

    private fun drawBanner(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, shell: St.Shell) {
        val y1 = y0 + L.BANNER_H
        val ago = Format.ago(now - loadedAt)
        val bg: Int
        val edge: Int
        val icon: String
        val text: String
        var button: String? = null
        if (shell.banner == St.BANNER_RATE_LIMITED) {
            bg = 0xFF2A2214.toInt(); edge = KitStyle.MAXED; icon = "pv_clock"
            text = "§lToo many lookups right now.§r You can look up the next player in ${max(1L, (retryUntil - now + 999) / 1000)} s. This is what was loaded $ago."
        } else if (shell.banner == St.BANNER_OFFLINE) {
            bg = 0xFF2A1616.toInt(); edge = KitStyle.ERROR; icon = "pv_offline"
            text = "§lCan't reach Soul's server.§r Showing what was loaded $ago."
            button = "Try again"
        } else {
            bg = KitStyle.CARD_BG; edge = Theme.TEXT_DIM; icon = "pv_clock"
            text = "Loaded $ago. Things may have changed since."
            button = "Refresh"
        }
        rect(x0, y0, x1, y1, bg, Corner.MEDIUM)
        g.fill(x0, y0 + 2, x0 + 2, y1 - 2, edge)
        drawIcon(g, icon, x0 + 10, (y0 + y1) / 2 - 7, 14, if (edge == Theme.TEXT_DIM) KitStyle.TEXT_SOFT else edge)
        var right = x1 - 10
        if (button != null) {
            val bw = tw(button, KitStyle.BODY, false) + 18
            val bx = right - bw
            val hover = hit(bx, y0 + 5, right, y1 - 5)
            rect(bx, y0 + 5, right, y1 - 5, if (hover) Theme.CONTROL_HOVER else Theme.PANEL_HOVER, Corner.SMALL)
            txt(button, bx + 9, y0 + 5, y1 - 5, Theme.TEXT, KitStyle.BODY)
            region(bx, y0 + 5, right, y1 - 5) { refresh() }
            right = bx - 8
        }
        Rich.of(text).ellipsize(canvas, KitStyle.BODY, right - (x0 + 32)).draw(canvas, x0 + 32, y0, y1, KitStyle.BODY, KitStyle.TEXT_SOFT)
    }

    /** `skeleton()` in mock.js: identity card, five tiles, four cards, all pulsing. */
    private fun drawSkeleton(x0: Int, y0: Int, x1: Int, y1: Int) {
        val w = x1 - x0
        rect(x0, y0, x1, y0 + 80, KitStyle.CARD_BG, Corner.MEDIUM)
        skeleton(x0 + 14, y0 + 12, x0 + 70, y0 + 68)
        skeleton(x0 + 84, y0 + 26, x0 + 264, y0 + 34)
        skeleton(x0 + 84, y0 + 41, x0 + 204, y0 + 49)
        var y = y0 + 88
        val cols = com.soulreturns.features.profileviewer.gui.kit.KitLayout.autoFitColumns(w, KitStyle.TILE_MIN, KitStyle.GAP, 5)
        val tracks = com.soulreturns.features.profileviewer.gui.kit.KitLayout.trackX(w, cols, KitStyle.GAP)
        for (i in 0 until cols) {
            val tx = x0 + tracks[i]
            val tw = tracks[i + 1] - KitStyle.GAP - tracks[i]
            rect(tx, y, tx + tw, y + 58, KitStyle.CARD_BG, Corner.MEDIUM)
            skeleton(tx + 11, y + 10, tx + 11 + tw * 6 / 10, y + 18)
            skeleton(tx + 11, y + 25, tx + 11 + tw * 4 / 10, y + 37)
            skeleton(tx + 11, y + 44, tx + tw - 11, y + 52)
        }
        y += 66
        val gc = com.soulreturns.features.profileviewer.gui.kit.KitLayout.autoFillColumns(w, 300, KitStyle.GAP)
        val gt = com.soulreturns.features.profileviewer.gui.kit.KitLayout.trackX(w, gc, KitStyle.GAP)
        var i = 0
        while (i < 4 && y < y1) {
            val c = i % gc
            val cx = x0 + gt[c]
            val cw = gt[c + 1] - KitStyle.GAP - gt[c]
            rect(cx, y, cx + cw, min(y1, y + 150), KitStyle.CARD_BG, Corner.MEDIUM)
            skeleton(cx + 12, y + 12, cx + 12 + cw * 3 / 10, y + 20)
            skeleton(cx + 12, y + 27, cx + cw - 12, y + 35)
            skeleton(cx + 12, y + 42, cx + cw - 12, y + 50)
            skeleton(cx + 12, y + 57, cx + 12 + cw * 7 / 10, y + 65)
            i++
            if (i % gc == 0) y += 158
        }
    }

    /** `stateBody()` in mock.js: not found, no profiles, offline / rate limited with nothing cached, other errors. */
    private fun drawCenterState(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, shell: St.Shell) {
        val cx = (x0 + x1) / 2
        var y = y0 + 70
        val name = resolved?.name ?: lookupName
        var title: String
        var text: String
        var button: String? = null
        var showRecent = false
        var faceUuid: String? = null
        var icon = "pv_search"
        if (shell.body == St.BODY_NOT_FOUND) {
            title = "No Minecraft account is called \"$lookupName\""
            text = "Check the spelling. Names are 3 to 16 letters, numbers or underscores."
            showRecent = true
        } else if (shell.body == St.BODY_NO_PROFILES) {
            title = "$name hasn't played SkyBlock"
            text = "This account has no SkyBlock profiles. Profiles show up here a few minutes after someone first joins SkyBlock."
            showRecent = true
            faceUuid = resolved?.uuidUndashed
        } else if (shell.body == St.BODY_OFFLINE_EMPTY) {
            icon = "pv_offline"
            title = "Can't reach Soul's server"
            text = "Profiles load through Soul's server, which isn't answering. Nothing is saved for $name yet."
            button = "Try again"
        } else if (shell.body == St.BODY_RATE_LIMITED_EMPTY) {
            icon = "pv_clock"
            val left = max(0L, (retryUntil - now + 999) / 1000)
            title = "Too many lookups right now"
            text = if (left > 0) "Soul's server limits lookups to 30 a minute. You can look up $name in $left s." else "You can look up $name again now."
            button = if (left > 0) null else "Try again"
        } else {
            icon = "pv_warn"
            title = failure?.headline ?: "Something went wrong"
            text = failure?.detail ?: ""
            button = "Try again"
        }
        if (faceUuid != null) {
            rect(cx - 28, y, cx + 28, y + 56, Theme.SIDEBAR, Corner.MEDIUM)
            face(g, faceUuid, cx - 24, y + 4, 48)
            y += 56
        } else {
            drawIcon(g, icon, cx - 15, y, 30, Theme.TEXT_FAINT)
            y += 30
        }
        y += 6 + 6
        txt(title, cx - tw(title, KitStyle.HEADING, true) / 2, y, y + 16, Theme.TEXT, KitStyle.HEADING, true)
        y += 16 + 6
        for (line in Rich.of(text).wrap(canvas, KitStyle.BODY, min(380, x1 - x0 - 40))) {
            val lw = line.width(canvas, KitStyle.BODY)
            line.draw(canvas, cx - lw / 2, y, y + 14, KitStyle.BODY, Theme.TEXT_DIM)
            y += 14
        }
        if (button != null) {
            y += 6 + 10
            val bw = tw(button, KitStyle.BODY, false) + 24
            val bx = cx - bw / 2
            val hover = hit(bx, y, bx + bw, y + 22)
            rect(bx, y, bx + bw, y + 22, if (hover) Theme.ACCENT else Theme.ACCENT_DIM, Corner.SMALL)
            txt(button, bx + 12, y, y + 22, 0xFFFFFFFF.toInt(), KitStyle.BODY)
            region(bx, y, bx + bw, y + 22) { lookup(name) }
            y += 22
        }
        if (showRecent) {
            val recent = RecentPlayers.list().filterNot { it.name.equals(name, ignoreCase = true) }
            if (recent.isNotEmpty()) {
                y += 6 + 12
                val chips = recent.map { it to tw(it.name, KitStyle.BODY, false) + 9 + 14 + 6 + 5 }
                val rowW = x1 - x0 - 40
                var line = ArrayList<Pair<RecentPlayers.Entry, Int>>()
                val lines = ArrayList<List<Pair<RecentPlayers.Entry, Int>>>()
                var used = 0
                for (c in chips) {
                    if (line.isNotEmpty() && used + 6 + c.second > rowW) {
                        lines += line
                        line = ArrayList()
                        used = 0
                    }
                    used += (if (line.isEmpty()) 0 else 6) + c.second
                    line += c
                }
                if (line.isNotEmpty()) lines += line
                for (l in lines) {
                    var px = cx - (l.sumOf { it.second } + 6 * (l.size - 1)) / 2
                    for ((entry, w) in l) {
                        val n = entry.name
                        val hover = hit(px, y, px + w, y + 22)
                        rect(px, y, px + w, y + 22, if (hover) Theme.ACCENT else Theme.FIELD_BORDER, Corner.SMALL)
                        rect(px + 1, y + 1, px + w - 1, y + 21, 0xFF202020.toInt(), Corner.SMALL)
                        if (entry.uuid != null) face(g, entry.uuid, px + 5, y + 4, 14) else drawIcon(g, "pv_user", px + 5, y + 4, 14, Theme.TEXT_DIM)
                        txt(n, px + 5 + 14 + 6, y, y + 22, if (hover) 0xFFFFFFFF.toInt() else KitStyle.TEXT_SOFT, KitStyle.BODY)
                        region(px, y, px + w, y + 22) { lookup(n) }
                        px += w + 6
                    }
                    y += 28
                }
            }
        }
    }

    // ------------------------------------------------------------------ popovers

    private class SearchRow(val name: String, val right: String, val uuid: String?, val you: Boolean, val enter: Boolean, val group: String?)

    /** `searchPop()` in mock.js: the typed name, You, Recent, In your party; filtered by what is typed. */
    private fun searchRows(): List<SearchRow> {
        val q = search.text.trim().lowercase()
        fun match(n: String) = q.isEmpty() || n.lowercase().contains(q)
        val rows = ArrayList<SearchRow>()
        if (q.isNotEmpty()) rows += SearchRow(search.text.trim(), "Enter", null, you = false, enter = true, group = null)
        val me = selfName()
        if (match(me)) rows += SearchRow(me, "Ctrl M", selfUuid(), you = true, enter = false, group = "You")
        for (r in RecentPlayers.list()) {
            if (match(r.name) && !r.name.equals(me, ignoreCase = true)) rows += SearchRow(r.name, Format.ago(now - r.at), r.uuid, you = false, enter = false, group = "Recent")
        }
        val party = PartyManager.getPartyState()?.members?.values?.filterNot { it.name.equals(me, ignoreCase = true) }.orEmpty()
        for (p in party) if (match(p.name)) rows += SearchRow(p.name, "party", p.uuid?.let { MojangUuid.undashed(it) }, you = false, enter = false, group = "In your party")
        return rows
    }

    private fun drawPopover(g: GuiGraphicsExtractor, card: L.Card, ctx: ProfileContext?) {
        if (popover == POP_SEARCH) drawSearchPop(g, card) else if (popover == POP_PROFILE && ctx != null) drawProfilePop(g, card, ctx) else if (popover == POP_KEYS) drawKeysPop(card)
    }

    private fun popBox(x: Int, y: Int, w: Int, h: Int) {
        region(x, y, x + w, y + h) {}
        rect(x - 1, y - 1, x + w + 1, y + h + 1, Theme.FIELD_BORDER, Corner.MEDIUM)
        rect(x, y, x + w, y + h, Theme.PALETTE, Corner.MEDIUM)
    }

    private fun popGroupLabel(label: String, x: Int, y: Int) {
        val u = label.uppercase()
        tracked(frame!!, u, x + 6 + trackedW(u, KitStyle.LABEL, KitStyle.CHIP_TRACKING) / 2, y + 12, Theme.TEXT_FAINT, KitStyle.LABEL, KitStyle.CHIP_TRACKING)
    }

    private fun drawSearchPop(g: GuiGraphicsExtractor, card: L.Card) {
        val rows = searchRows()
        val w = SEARCH_POP_W
        var h = POP_PAD * 2 + 22
        var last: String? = "-"
        for (r in rows) {
            if (r.group != last) {
                if (last != "-") h += 9
                if (r.group != null) h += SEARCH_GROUP_H
                last = r.group
            }
            h += SEARCH_ROW_H
        }
        if (rows.isEmpty()) h += 26
        val x = L.popoverX(searchAnchor[0], searchAnchor[2], w, card.x0, card.w, alignLeft = false)
        val y0 = searchAnchor[3] + 4
        popBox(x, y0, w, h)
        var y = y0 + POP_PAD
        last = "-"
        searchHl = searchHl.coerceIn(-1, rows.size - 1)
        for ((i, r) in rows.withIndex()) {
            if (r.group != last) {
                if (last != "-") {
                    g.fill(x + 2 + POP_PAD, y + 4, x + w - 2 - POP_PAD, y + 5, Theme.SEPARATOR)
                    y += 9
                }
                if (r.group != null) {
                    popGroupLabel(r.group, x + POP_PAD, y)
                    y += SEARCH_GROUP_H
                }
                last = r.group
            }
            val rx0 = x + POP_PAD
            val rx1 = x + w - POP_PAD
            val hover = hit(rx0, y, rx1, y + SEARCH_ROW_H)
            if (hover || i == searchHl) rect(rx0, y, rx1, y + SEARCH_ROW_H, Theme.PALETTE_SELECTED, Corner.SMALL)
            if (r.uuid != null) face(g, r.uuid, rx0 + 6, y + 6, 16) else drawIcon(g, "pv_user", rx0 + 6, y + 6, 16, Theme.TEXT_DIM)
            txt(r.name, rx0 + 6 + 16 + 7, y, y + SEARCH_ROW_H, Theme.TEXT, KitStyle.BODY)
            var nx = rx0 + 6 + 16 + 7 + tw(r.name, KitStyle.BODY, false)
            if (r.you) nx += 5 + Chip.you().paint(canvas, nx + 5, y + SEARCH_ROW_H / 2)
            if (r.enter) kbd(g, r.right, rx1 - 6, y + SEARCH_ROW_H / 2, alignRight = true) else txt(r.right, rx1 - 6 - tw(r.right, KitStyle.CAPTION, false), y, y + SEARCH_ROW_H, Theme.TEXT_DIM, KitStyle.CAPTION)
            val target = r.name
            region(rx0, y, rx1, y + SEARCH_ROW_H) { lookup(target) }
            y += SEARCH_ROW_H
        }
        if (rows.isEmpty()) {
            txt("No match. Press Enter to look it up.", x + POP_PAD + 6, y, y + 26, Theme.TEXT_DIM, KitStyle.BODY)
            y += 26
        }
        g.fill(x + POP_PAD, y + 4, x + w - POP_PAD, y + 5, Theme.SEPARATOR)
        var fx = x + POP_PAD + 6
        for (part in listOf("Enter looks up · ", "↑↓", " choose · Esc closes")) {
            if (part == "↑↓") {
                // The UI font has no arrows: the mock's ↑↓ as icons.
                drawIcon(g, "arrow_up", fx, y + 9, 8, Theme.TEXT_FAINT)
                drawIcon(g, "arrow_down", fx + 7, y + 9, 8, Theme.TEXT_FAINT)
                fx += 15
            } else {
                txt(part, fx, y + 6, y + 20, Theme.TEXT_FAINT, KitStyle.TINY)
                fx += tw(part, KitStyle.TINY, false)
            }
        }
    }

    private fun drawProfilePop(g: GuiGraphicsExtractor, card: L.Card, ctx: ProfileContext) {
        val list = ctx.profiles.profiles
        val w = PROFILE_POP_W
        val h = POP_PAD * 2 + list.size * PROFILE_ROW_H + 25
        val x = L.popoverX(profileAnchor[0], profileAnchor[2], w, card.x0, card.w, alignLeft = true)
        val y0 = profileAnchor[3] + 4
        popBox(x, y0, w, h)
        var y = y0 + POP_PAD
        for (p in list) {
            val m = p.member(ctx.identity.uuidUndashed)
            val rx0 = x + POP_PAD
            val rx1 = x + w - POP_PAD
            val on = p.profileId == ctx.profile.profileId
            val hover = hit(rx0, y, rx1, y + PROFILE_ROW_H)
            if (on) {
                rect(rx0, y, rx1, y + PROFILE_ROW_H, Theme.CARD_SELECTED, Corner.SMALL)
                g.fill(rx0, y + 2, rx0 + 2, y + PROFILE_ROW_H - 2, Theme.ACCENT)
            } else if (hover) {
                rect(rx0, y, rx1, y + PROFILE_ROW_H, Theme.PALETTE_SELECTED, Corner.SMALL)
            }
            var nx = rx0 + 8
            txt(p.cuteName, nx, y + 6, y + 24, Theme.TEXT, KitStyle.BODY, true)
            nx += tw(p.cuteName, KitStyle.BODY, true)
            Chip.gameMode(p.gameMode)?.let { nx += 5 + it.paint(canvas, nx + 5, y + 15) }
            if (p.selected) txt("· playing", nx + 5, y + 6, y + 24, Theme.TEXT_FAINT, KitStyle.CAPTION)
            if (m != null) {
                val members = p.activeMemberCount
                val avg = m.skillAverageCapped(playerCaps?.forProfile(p.profileId, p.selected))
                val line = (if (members > 1) "Co-op · $members members" else "Solo") +
                    (avg?.let { " · Skill avg ${String.format(java.util.Locale.ROOT, "%.1f", it)}" } ?: "") +
                    " · Cata ${m.catacombsLevel.level}"
                txt(line, rx0 + 8, y + 25, y + 41, Theme.TEXT_DIM, KitStyle.CAPTION)
                m.skyblockLevel?.let {
                    val lv = "${it.level}"
                    txt(lv, rx1 - 8 - tw(lv, KitStyle.HEADING, true), y, y + PROFILE_ROW_H, Format.levelColor(it.level), KitStyle.HEADING, true)
                }
            }
            region(rx0, y, rx1, y + PROFILE_ROW_H) { showProfile(p) }
            y += PROFILE_ROW_H
        }
        g.fill(x + POP_PAD, y + 4, x + w - POP_PAD, y + 5, Theme.SEPARATOR)
        txt("All profiles came in one request; switching is instant.", x + POP_PAD + 6, y + 9, y + 23, Theme.TEXT_FAINT, KitStyle.TINY)
    }

    private val keys = listOf(
        "Ctrl F  or  /" to "Look up a player",
        "Ctrl M" to "Your own profile",
        "Ctrl P" to "Switch profile",
        "PgUp / PgDn" to "Previous / next section",
        "1 – 9" to "Jump to a section",
        "0" to "Soul section",
        "R" to "Refresh",
        "Esc" to "Close popups, then the viewer",
    )

    private fun drawKeysPop(card: L.Card) {
        val w = KEYS_POP_W
        val kw = 82
        val labelW = w - 10 - kw - 8 - 10
        // A label wider than the row wraps, and its row grows (`.kr` with a two-line span).
        val wrapped = keys.map { (k, label) -> k to Rich.of(label).wrap(canvas, KitStyle.BODY, labelW) }
        val rowH = wrapped.map { KEYS_ROW_H + 14 * (it.second.size - 1) }
        val h = 16 + rowH.sum()
        val x = L.popoverX(keysAnchor[0], keysAnchor[2], w, card.x0, card.w, alignLeft = false)
        val y0 = keysAnchor[3] + 4
        popBox(x, y0, w, h)
        var y = y0 + 8
        for ((i, entry) in wrapped.withIndex()) {
            val (k, lines) = entry
            val cy = y + rowH[i] / 2
            rect(x + 10, cy - 6, x + 10 + kw, cy + 6, Theme.FIELD_BORDER, Corner.SMALL)
            rect(x + 11, cy - 5, x + 9 + kw, cy + 5, 0xFF1A1A1A.toInt(), Corner.SMALL)
            txt(k, x + 10 + (kw - tw(k, KitStyle.LABEL, false)) / 2, cy - 6, cy + 6, 0xFF999999.toInt(), KitStyle.LABEL)
            var ly = y + 3
            for (line in lines) {
                line.draw(canvas, x + 10 + kw + 8, ly, ly + 14, KitStyle.BODY, KitStyle.TEXT_SOFT)
                ly += 14
            }
            y += rowH[i]
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun hit(x0: Int, y0: Int, x1: Int, y1: Int): Boolean = mx in x0 until x1 && my in y0 until y1

    private fun pipeline(corner: Int): RenderPipeline? = when (corner) {
        Corner.SMALL -> SoulRenderPipelines.ROUND_SMALL
        Corner.MEDIUM -> SoulRenderPipelines.ROUND_MEDIUM
        Corner.LARGE -> SoulRenderPipelines.ROUND_LARGE
        Corner.PILL -> SoulRenderPipelines.ROUND_PILL
        else -> null
    }

    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {
        val g = frame ?: return
        if (x1 <= x0 || y1 <= y0 || color ushr 24 == 0) return
        val p = pipeline(corner)
        if (p == null) g.fill(x0, y0, x1, y1, color) else roundRect(g, x0, y0, x1, y1, color, p)
    }

    private fun weight(bold: Boolean): MsdfFont = if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular

    private fun txt(s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean = false) {
        val g = frame ?: return
        if (s.isNotEmpty()) drawTextV(g, s, x, top, bottom, color, size, weight(bold))
    }

    private fun tw(s: String, size: Float, bold: Boolean): Int = textWidth(s, size, weight(bold))

    private fun trackingPx(size: Float, tracking: Float): Float = tracking * size / MsdfFont.LEGACY_EM

    private fun trackedW(s: String, size: Float, tracking: Float, bold: Boolean = true): Int =
        SoulText.widthTracked(s, size, weight(bold), trackingPx(size, tracking)).roundToInt()

    private fun tracked(g: GuiGraphicsExtractor, s: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean = true) =
        SoulText.drawCenteredTracked(g, s, cx, cy, size, color, weight(bold), trackingPx(size, tracking), scissor = currentClip)

    /** `.kbd`: a small key cap; returns its width. [x] is its right edge when [alignRight]. */
    private fun kbd(g: GuiGraphicsExtractor, label: String, x: Int, cy: Int, alignRight: Boolean): Int {
        val w = tw(label, KitStyle.LABEL, false) + 6
        val x0 = if (alignRight) x - w else x
        rect(x0, cy - 6, x0 + w, cy + 6, 0xFF3A3A3A.toInt(), Corner.SMALL)
        rect(x0 + 1, cy - 5, x0 + w - 1, cy + 5, 0xFF1A1A1A.toInt(), Corner.SMALL)
        txt(label, x0 + 3, cy - 6, cy + 6, 0xFF999999.toInt(), KitStyle.LABEL)
        return w
    }

    private fun iconButton(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int, icon: String, tip: String, danger: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
        val hover = hit(x, y, x + size, y + size)
        if (hover && danger) rect(x, y, x + size, y + size, 0xFFB03030.toInt(), Corner.SMALL) else if (hover || active) rect(x, y, x + size, y + size, Theme.PANEL_HOVER, Corner.SMALL)
        drawIcon(g, icon, x + (size - 14) / 2, y + (size - 14) / 2, 14, if (hover || active) Theme.TEXT else KitStyle.TEXT_MID)
        if (hover) tooltip(x, y, x + size, y + size, listOf(tip))
        region(x, y, x + size, y + size, onClick)
    }

    private fun skeleton(x0: Int, y0: Int, x1: Int, y1: Int) {
        val t = (now % 2200) / 1100f
        val k = if (t > 1f) 2f - t else t
        val shade = 0x26 + (k * 10).toInt()
        rect(x0, y0, x1, y1, (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade, Corner.SMALL)
    }

    private fun face(g: GuiGraphicsExtractor, uuidUndashed: String, x: Int, y: Int, size: Int) {
        val profile = faces.getOrPut(uuidUndashed) { ProfileFigure.profile(uuidUndashed) }
        PlayerFaceExtractor.extractRenderState(g, profile, x, y, size)
    }

    // ------------------------------------------------------------------ the kit's canvas

    private val canvas = object : KitCanvas {
        override var viewTop = 0
        override var viewBottom = 0
        override val nowMs: Long get() = now
        override val state: ViewState get() = viewState

        override fun width(text: String, size: Float, bold: Boolean): Int = tw(text, size, bold)

        override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean): Int = trackedW(text, size, tracking, bold)

        override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) = this@ProfileViewerScreen.rect(x0, y0, x1, y1, color, corner)

        override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) = txt(text, x, top, bottom, color, size, bold)

        override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
            frame?.let { tracked(it, text, cx, cy, color, size, tracking, bold) }
        }

        override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {
            frame?.let { drawIcon(it, name, x, y, size, color) }
        }

        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {
            val g = frame ?: return
            g.pose().pushMatrix()
            g.pose().translate(x.toFloat(), y.toFloat())
            g.pose().scale(size / 16f, size / 16f)
            g.fakeItem(stack, 0, 0)
            if (count) g.itemDecorations(Minecraft.getInstance().font, stack, 0, 0)
            g.pose().popMatrix()
        }

        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {
            frame?.let { face(it, uuidUndashed, x, y, size) }
        }

        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int): Boolean =
            popover == null && hit(x0, y0, x1, y1) && my >= bodyRect[1] && my < bodyRect[3] && mx >= bodyRect[0] && mx < bodyRect[2]

        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) = clippedRegion(x0, y0, x1, y1, bodyRect, action)

        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {
            if (hovered(x0, y0, x1, y1)) this@ProfileViewerScreen.tooltip(x0, y0, x1, y1, lines, key)
        }

        override fun itemTooltip(stack: ItemStack) {
            if (popover == null) pendingItem = stack
        }

        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {
            val g = frame ?: return
            val f = viewState.field(key)
            drawTextField(g, x0, y0, x1, y1, f, placeholder, viewport = bodyRect, inset = 20, background = KitStyle.FIELD_BG, border = KitStyle.FIELD_EDGE) {
                search.focused = false
                popover = null
                viewState.blurFields()
                f.focused = true
            }
            drawIcon(g, "pv_search", x0 + 6, y0 + (y1 - y0 - 11) / 2, 11, if (f.focused) Theme.TEXT else Theme.TEXT_DIM)
        }

        override fun openSection(id: String) = goSection(id)

        override fun gradient(x0: Int, y0: Int, x1: Int, y1: Int, top: Int, bottom: Int, corner: Int) {
            val g = frame ?: return
            val p = pipeline(corner)
            if (p == null) {
                g.fillGradient(x0, y0, x1, y1, top, bottom)
                return
            }
            // Rounded ends in the end colors, the vertical gradient between them.
            val r = min(6, (y1 - y0) / 2)
            roundRect(g, x0, y0, x1, y0 + 2 * r, top, p)
            roundRect(g, x0, y1 - 2 * r, x1, y1, bottom, p)
            g.fillGradient(x0, y0 + r, x1, y1 - r, top, bottom)
        }

        override fun playerModel(uuidUndashed: String, name: String, x0: Int, y0: Int, x1: Int, y1: Int, armor: List<ItemStack?>?): Int {
            val g = frame ?: return PlayerModel.UNAVAILABLE
            val f = figure?.takeIf { it.uuidUndashed == uuidUndashed } ?: ProfileFigure(uuidUndashed, name).also { figure = it }
            val s = SoulUi.factor()
            return f.draw(g, (x0 * s).roundToInt(), (y0 * s).roundToInt(), (x1 * s).roundToInt(), (y1 * s).roundToInt(), rawMouseX, rawMouseY, armor)
        }
    }

    // ------------------------------------------------------------------ input

    private fun openSearch() {
        viewState.blurFields()
        popover = POP_SEARCH
        search.focused = true
        searchHl = -1
    }

    private fun stepSection(delta: Int) {
        val list = visibleSections(context(now))
        val i = list.indexOfFirst { it.id == sectionId }
        val next = (i + delta).coerceIn(0, list.lastIndex)
        goSection(list[next].id)
    }

    override fun onEmptyClick() {
        search.focused = false
        viewState.blurFields()
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        if (!event.isAllowedChatCharacter()) return super.charTyped(event)
        val s = event.codepointAsString()
        if (search.focused) {
            // Only what a Minecraft username can contain.
            if (search.text.length < 16 && s.length == 1 && (s[0] == '_' || s[0].isLetterOrDigit() && s[0].code < 128)) {
                search.charTyped(event)
                popover = POP_SEARCH
                searchHl = -1
            }
            return true
        }
        viewState.focusedField()?.let {
            it.charTyped(event)
            return true
        }
        if (s.length != 1) return super.charTyped(event)
        val ch = s[0]
        if (ch == '/') {
            openSearch()
            return true
        }
        if (ch == '?') {
            popover = if (popover == POP_KEYS) null else POP_KEYS
            return true
        }
        if (ch == 'r' || ch == 'R') {
            refresh()
            return true
        }
        if (ch in '1'..'9') {
            visibleSections(context(now)).getOrNull(ch - '1')?.let { goSection(it.id) }
            return true
        }
        if (ch == '0') {
            goSection(SoulSection.id)
            return true
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) return super.keyPressed(event)
        val key = event.key()
        if (event.hasControlDown()) {
            if (key == GLFW.GLFW_KEY_F) {
                openSearch()
                return true
            }
            if (key == GLFW.GLFW_KEY_M) {
                lookup(selfName())
                return true
            }
            if (key == GLFW.GLFW_KEY_P && view != null) {
                popover = if (popover == POP_PROFILE) null else POP_PROFILE
                return true
            }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (popover != null) {
                popover = null
                search.focused = false
                return true
            }
            if (search.focused || viewState.focusedField() != null) {
                search.focused = false
                viewState.blurFields()
                return true
            }
            onClose()
            return true
        }
        if (search.focused) {
            if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
                val n = searchRows().size
                if (n > 0) searchHl = ((searchHl + (if (key == GLFW.GLFW_KEY_DOWN) 1 else -1)) % n + n) % n
                return true
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                val rows = searchRows()
                val pick = rows.getOrNull(searchHl)?.name ?: search.text.trim()
                lookup(pick)
                return true
            }
            search.keyPressed(event, minecraft?.keyboardHandler)
            return true
        }
        viewState.focusedField()?.let {
            it.keyPressed(event, minecraft?.keyboardHandler)
            return true
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || (key == GLFW.GLFW_KEY_DOWN && event.hasAltDown())) {
            stepSection(1)
            return true
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP || (key == GLFW.GLFW_KEY_UP && event.hasAltDown())) {
            stepSection(-1)
            return true
        }
        if (key == GLFW.GLFW_KEY_F5) {
            refresh()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (popover != null) return true
        val s = SoulUi.factor()
        val target = scrollTargetAt((mouseX / s).roundToInt(), (mouseY / s).roundToInt())
        if (target != null) {
            target.state.scrollBy(-scrollY.toFloat() * SCROLL_STEP)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        val target = parent
        if (target != null) minecraft?.setScreenAndShow(target) else super.onClose()
    }

    override fun removed() {
        figure = null
        super.removed()
    }
}
