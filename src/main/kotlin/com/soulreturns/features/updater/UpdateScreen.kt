package com.soulreturns.features.updater

import com.soulreturns.config.gui.ConfigRegistry
import com.soulreturns.config.gui.ConfigScreenBase
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.updater.ReleaseNotesFormat.Block
import com.soulreturns.platform.updater.ReleaseNote
import com.soulreturns.platform.updater.UpdateError
import com.soulreturns.platform.updater.UpdateNotice
import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateService
import com.soulreturns.platform.updater.UpdateSnapshot
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.RichText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The update window (spec `mocks/updater/`, modal layer), drawn over [parent] (the config menu) or
 * over the game when opened from chat: "Update available" with the changelog of every version it
 * skips over (Skip / Update Now), the download (progress, signature step, "restart required" with
 * Later / Close game now), the errors (download failed, rejected, needs a newer Fabric), and "Updated
 * to vX" with its notes after the restart. Esc and × mean "later"; nothing installs without a click.
 *
 * The user's changes against the mock: no "close when done" checkbox or countdown.
 */
class UpdateScreen(
    private val parent: Screen?,
    initialMode: Int,
    /** Opened by [UpdateTitlePrompt.beforeTitle] before the main menu exists: leaving it lets vanilla build the title screen. */
    private val initial: Boolean = false,
) : ConfigScreenBase(Component.literal("Soul update")) {
    companion object {
        /** Pick by state: updated → what's new, offer → offer, download/ready/error → progress. */
        const val AUTO = 0
        const val OFFER = 1
        const val PROGRESS = 2
        const val WHATS_NEW = 3

        /** The one-time "turn on automatic updates?" question (title screen, [UpdateTitlePrompt]). */
        const val ASK_AUTO = 4

        private const val MODAL_W = 360
        private const val MODAL_MAX_H = 0.82f
        private const val MODAL_MIN_H = 160
        private const val MODAL_CAP_H = 520
        private const val PAD = 16
        private const val GAP = 12
        private const val BTN_H = 24
        private const val BTN_MIN_W = 64
        private const val LINE = 14
        private const val SCROLL_STEP = 24f
        private const val HOST = "sky.soulreturns.com"

        private fun f(px: Float) = px * MsdfFont.LEGACY_EM
        private val F_TITLE = f(13f)
        private val F_SUB = f(10f)
        private val F_TEXT = f(10.5f)
        private val F_VER = f(11.5f)

        private val GREEN = 0xFF4ADE80.toInt()
        private val RED = 0xFFE05252.toInt()
        private val ORANGE = 0xFFF0A640.toInt()
        private val NOTES_BG = 0xFF141414.toInt()
        private val NOTES_EDGE = 0xFF262626.toInt()
        private val MODAL_BG = 0xF21A1A1A.toInt()

        fun isOpen(): Boolean = Minecraft.getInstance().gui.screen() is UpdateScreen

        fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    }

    private var mode = initialMode
    private val notesScroll = ScrollState()

    override fun init() {
        parent?.init(width, height)
    }

    override fun isPauseScreen(): Boolean = parent?.isPauseScreen ?: false

    private fun later() {
        if (mode == ASK_AUTO) return answerAuto(false)
        if (mode == WHATS_NEW) UpdateService.dismissUpdated()
        leave(parent)
    }

    override fun onClose() = later()

    /** Back to [target]; before the main menu: on to the title screen; opened from chat: back to the game. */
    private fun leave(target: Screen?) {
        when {
            target != null -> minecraft?.setScreenAndShow(target)
            initial -> UpdateTitlePrompt.finishInitial()
            else -> super.onClose()
        }
    }

    /** Replaced by another mod's screen before the player answered: the title screen must still follow. */
    override fun removed() {
        super.removed()
        if (initial) UpdateTitlePrompt.finishInitialIfIdle()
    }

    /** Remembers the answer, then shows what is pending on the title screen (the offer) or returns to it. */
    private fun answerAuto(turnOn: Boolean) {
        UpdateTitlePrompt.answerAutoInstall(turnOn)
        val next = UpdateTitlePrompt.next(parent, initial)
        if (next != null) minecraft?.setScreenAndShow(next) else leave(parent)
    }

    private fun resolve(s: UpdateSnapshot): Int {
        if (mode == ASK_AUTO) return ASK_AUTO
        if (mode == WHATS_NEW && s.updated != null) return WHATS_NEW
        val busy = s.phase == UpdatePhase.DOWNLOADING || s.phase == UpdatePhase.READY_RESTART || s.phase == UpdatePhase.ERROR
        if (mode == PROGRESS || busy) return if (s.release != null) PROGRESS else AUTO
        if (s.phase == UpdatePhase.AVAILABLE) return OFFER
        if (s.updated != null) return WHATS_NEW
        return AUTO
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        parent?.let {
            it.extractRenderState(g, -10_000, -10_000, partialTick)
            g.nextStratum()
        }
        regions.clear()
        scrollTargets.clear()
        val sc = SoulUi.factor()
        val uiW = (width / sc).toInt()
        val uiH = (height / sc).toInt()
        val mx = (mouseX / sc).toInt()
        val my = (mouseY / sc).toInt()
        g.pose().pushMatrix()
        g.pose().scale(sc, sc)

        g.fill(0, 0, uiW, uiH, 0xB0000000.toInt())
        // A stray click outside must not decide the one-time question for good.
        region(0, 0, uiW, uiH) { if (mode != ASK_AUTO) later() }

        val s = UpdateService.snapshot
        mode = resolve(s)
        val w = minOf(MODAL_W, uiW - 16)
        val tallH = max(MODAL_MIN_H, minOf((uiH * MODAL_MAX_H).toInt(), MODAL_CAP_H))
        val parts = parts(s)
        val tall = mode == OFFER || mode == WHATS_NEW
        val inner = w - PAD * 2
        val fixed = parts.sumOf { if (it.flex) 0 else it.height(inner) }
        val h = if (tall) tallH else minOf(fixed + PAD * 2, uiH - 16)
        val x0 = (uiW - w) / 2
        val y0 = (uiH - h) / 2
        panel(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, MODAL_BG, 0xFF333333.toInt())
        region(x0, y0, x0 + w, y0 + h) {}

        var y = y0 + PAD
        val flexH = (h - PAD * 2 - fixed).coerceAtLeast(40)
        for (p in parts) {
            val ph = if (p.flex) flexH else p.height(inner)
            p.draw(g, mx, my, x0 + PAD, y, x0 + w - PAD, y + ph)
            y += ph
        }
        drawTooltip(g, uiW, uiH)
        g.pose().popMatrix()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val sc = SoulUi.factor()
        val target = scrollTargetAt((mouseX / sc).roundToInt(), (mouseY / sc).roundToInt())
        if (target != null) {
            target.state.scrollBy(-scrollY.toFloat() * SCROLL_STEP)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    // ---------------------------------------------------------------- parts

    /** One vertical piece of the window: fixed height for a width, or [flex] (the changelog). */
    private class Part(val flex: Boolean = false, val height: (Int) -> Int, val draw: (GuiGraphicsExtractor, Int, Int, Int, Int, Int, Int) -> Unit)

    private class Btn(val label: String, val accent: Boolean = false, val enabled: Boolean = true, val tip: String? = null, val right: Boolean = false, val onClick: () -> Unit)

    private fun parts(s: UpdateSnapshot): List<Part> {
        val r = s.release
        val running = "v${s.running}"
        if (mode == ASK_AUTO) {
            return listOf(
                header("download", Theme.ACCENT_LIGHT, "Automatic updates", Theme.TEXT, "Would you like to turn on auto-updates for Soul?"),
                info("New versions **download by themselves** and are used the next time you start the game. You can change this in **Settings › About**.", Theme.ACCENT),
                buttons(
                    Btn("No thanks") { answerAuto(false) },
                    Btn("Turn on", accent = true, right = true) { answerAuto(true) },
                ),
            )
        }
        if (mode == WHATS_NEW && s.updated != null) {
            val u = s.updated!!
            return listOf(
                header("ok_circle", GREEN, "Updated to v${u.to}", GREEN, "from v${u.from} · the old jar was removed"),
                changelog(u.notes),
                buttons(Btn("Got it", accent = true, right = true) { later() }),
            )
        }
        if (mode == OFFER && r != null) {
            val beta = r.channel == "beta"
            val urgent = s.urgent
            val out = arrayListOf(
                header("flame", -1, "Update available", Theme.TEXT, "$running → **v${r.version}** · ${if (beta) "Beta" else "Stable"} · ${mb(r.size)}", beta),
                changelog(r.notes),
            )
            if (urgent != null) {
                val text = if (urgent.kind == UpdateNotice.REVOKED) "**$running was withdrawn**: ${urgent.detail ?: "it has a known problem"}. Please update."
                else "**$running is below the minimum version** the backend supports."
                out += info(text, RED)
            }
            s.incompatible?.let { out += info("**$it.** Update it in your launcher, then check again.", ORANGE) }
            val first = if (urgent != null) Btn("Later") { later() } else Btn("Skip", tip = "Skip this version: no reminders until the next one") {
                UpdateService.skip()
                later()
            }
            out += buttons(
                first,
                Btn("Update Now", accent = true, enabled = s.canInstall, tip = if (s.canInstall) null else s.cannotInstallReason, right = true) {
                    UpdateService.install()
                    mode = PROGRESS
                },
            )
            return out
        }
        if (mode == PROGRESS && r != null) return progressParts(s, r.version, r.size, running)
        // Nothing to offer: up to date (or still checking).
        return listOf(
            header("ok_circle", GREEN, if (s.checking) "Checking…" else "Up to date", GREEN, "Soul $running"),
            buttons(Btn("OK", accent = true, right = true) { later() }),
        )
    }

    private fun progressParts(s: UpdateSnapshot, version: String, size: Long, running: String): List<Part> {
        val v = "v$version"
        val restartInfo = info(
            "**Restart Minecraft to use the new version.** Close the game, then press Play in your launcher; Soul can't start it again by itself. Not now? Click **Later** and keep playing on $running.",
            ORANGE,
        )
        if (s.phase == UpdatePhase.DOWNLOADING) {
            val ver = s.verifying
            return listOf(
                header("download", Theme.ACCENT_LIGHT, if (ver) "Checking signature…" else "Downloading $v", Theme.TEXT, if (ver) "SHA-256 of the jar against the Ed25519-signed release" else "from $HOST"),
                progress(s.progress, size, ver, false),
                restartInfo,
                buttons(
                    Btn("Later", tip = "Keeps downloading in the background") { later() },
                    Btn("Close game now", enabled = false, tip = "Available once the download is done", right = true) {},
                ),
            )
        }
        if (s.phase == UpdatePhase.READY_RESTART) {
            return listOf(
                header("ok_circle", GREEN, "Ready: restart required", GREEN, "$v is saved next to your current Soul jar"),
                progress(1f, size, false, true),
                restartInfo,
                buttons(
                    Btn("Later", tip = "Updates the next time you start Minecraft") { later() },
                    Btn("Close game now", accent = true, tip = "Saves and quits Minecraft like Quit Game", right = true) { UpdateService.closeGame() },
                ),
            )
        }
        val err = s.error ?: ""
        if (s.errorKind == UpdateError.VERIFY) {
            return listOf(
                header("x_circle", RED, "Update rejected", RED, "$v · signature check failed"),
                info("The downloaded file **does not match Soul's signed release**, so it was deleted and nothing was installed. You keep $running.", RED),
                msg("This should never happen. Please tell us on Discord. Soul tries again at the next check. `$err`"),
                buttons(
                    Btn("Report on Discord") { Util.getPlatform().openUri(ConfigRegistry.DISCORD_URL) },
                    Btn("OK", accent = true, right = true) { later() },
                ),
            )
        }
        if (s.errorKind == UpdateError.INCOMPATIBLE) {
            return listOf(
                header("warn", ORANGE, "Can't install $v", Theme.TEXT, "Your launcher setup is too old for it"),
                info("**$err.** Update it in your launcher (Prism: Edit › Version › Fabric Loader › Change version), then check again.", ORANGE),
                buttons(
                    Btn("Download page") { Util.getPlatform().openUri(UpdateNotices.DOWNLOAD_PAGE) },
                    Btn("OK", accent = true, right = true) { later() },
                ),
            )
        }
        val pct = (s.progress * 100).toInt()
        return listOf(
            header("x_circle", RED, if (s.errorKind == UpdateError.DOWNLOAD) "Download failed" else "Update failed", RED, if (pct > 0) "$v · stopped at $pct %" else v),
            info(
                if (s.errorKind == UpdateError.DOWNLOAD) "The download from **$HOST** failed: $err. Nothing was changed: you keep $running."
                else "$err. Nothing was changed: you keep $running.",
                RED,
            ),
            msg("If this keeps happening, get the jar from the **download page** and replace it by hand."),
            buttons(
                Btn("Later") { later() },
                Btn("Download page") { Util.getPlatform().openUri(UpdateNotices.DOWNLOAD_PAGE) },
                Btn("Retry", accent = true, right = true) { UpdateService.install() },
            ),
        )
    }

    private fun header(icon: String, iconColor: Int, title: String, titleColor: Int, sub: String, beta: Boolean = false) = Part(height = { 30 }) { g, mx, my, x0, y0, x1, _ ->
        drawIcon(g, icon, x0, y0 + 2, 26, iconColor)
        val tx = x0 + 26 + 10
        drawTextV(g, title, tx, y0, y0 + 18, titleColor, F_TITLE, MsdfFonts.menuSemibold)
        if (beta) drawChip(g, tx + textWidth(title, F_TITLE, MsdfFonts.menuSemibold) + 6, y0 + 9, "BETA", Theme.BETA, Theme.BETA_BG)
        drawRich(g, RichText.parse(sub), tx, y0 + 18, x1 - 24, F_SUB, Theme.TEXT_DIM, 12)
        // ×: later (Esc).
        val bx0 = x1 - 18
        val hover = mx in bx0..x1 && my in y0..y0 + 18
        if (hover) roundRect(g, bx0, y0, x1, y0 + 18, Theme.SEPARATOR, SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, "close", bx0 + 4, y0 + 4, 10, if (hover) Theme.TEXT else 0xFF666666.toInt())
        region(bx0, y0, x1, y0 + 18) { later() }
        if (hover) tooltip(bx0, y0, x1, y0 + 18, "Later (Esc)")
    }

    private fun info(text: String, stripe: Int) = Part(height = { w -> GAP + 16 + drawRich(null, RichText.parse(text), 0, 0, w - 20, F_TEXT, 0, LINE) }) { g, _, _, x0, y0, x1, y1 ->
        val top = y0 + GAP
        roundRect(g, x0, top, x1, y1, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        slice(g, x0, top, x1, y1, SoulPanel.MEDIUM, x0, top, x0 + 2, y1, stripe)
        drawRich(g, RichText.parse(text), x0 + 10, top + 8, x1 - 10, F_TEXT, 0xFFCCCCCC.toInt(), LINE)
    }

    private fun msg(text: String) = Part(height = { w -> GAP + drawRich(null, RichText.parse(text), 0, 0, w, F_TEXT, 0, LINE) }) { g, _, _, x0, y0, x1, _ ->
        drawRich(g, RichText.parse(text), x0, y0 + GAP, x1, F_TEXT, 0xFFBBBBBB.toInt(), LINE)
    }

    private fun progress(frac: Float, size: Long, verifying: Boolean, done: Boolean) = Part(height = { GAP + 14 + 4 }) { g, _, _, x0, y0, x1, _ ->
        val top = y0 + GAP
        if (!done) {
            val left = if (verifying) "**${mb(size)}** of ${mb(size)}" else "**${mb((size * frac).toLong())}** of ${mb(size)}"
            drawRich(g, RichText.parse(left), x0, top, x1, F_SUB, Theme.TEXT_DIM, 12)
            val right = if (verifying) "verifying" else "${(frac * 100).toInt()} %"
            drawTextV(g, right, x1 - textWidth(right, F_SUB), top, top + 12, Theme.TEXT_DIM, F_SUB)
        }
        val by = top + 14
        roundRect(g, x0, by, x1, by + 4, Theme.SEPARATOR, SoulRenderPipelines.ROUND_PILL)
        if (verifying) {
            // Moving stripes while the jar is checked (mock `.uc-bar.ver`).
            val shift = ((System.currentTimeMillis() / 20) % 16).toInt()
            var x = x0 - 16 + shift
            var light = false
            g.enableScissor(x0, by, x1, by + 4)
            while (x < x1) {
                g.fill(x, by, x + 8, by + 4, if (light) 0xFF60A5FA.toInt() else Theme.ACCENT)
                light = !light
                x += 8
            }
            g.disableScissor()
        } else {
            val fill = x0 + ((x1 - x0) * frac.coerceIn(0f, 1f)).toInt()
            if (fill > x0 + 2) roundRect(g, x0, by, fill, by + 4, if (done) GREEN else Theme.ACCENT, SoulRenderPipelines.ROUND_PILL)
        }
    }

    private fun buttons(vararg btns: Btn) = Part(height = { 14 + BTN_H }) { g, mx, my, x0, y0, x1, _ ->
        val top = y0 + 14
        var left = x0
        var right = x1
        // Right-aligned buttons are listed in visual order; lay them out from the right edge.
        for (b in btns.filter { it.right }.reversed()) {
            val bw = (textWidth(b.label, fBody) + 24).coerceAtLeast(BTN_MIN_W)
            button(g, mx, my, right - bw, top, right, top + BTN_H, b)
            right -= bw + 6
        }
        for (b in btns.filter { !it.right }) {
            val bw = (textWidth(b.label, fBody) + 24).coerceAtLeast(BTN_MIN_W)
            button(g, mx, my, left, top, left + bw, top + BTN_H, b)
            left += bw + 6
        }
    }

    private fun button(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, b: Btn) {
        if (b.enabled) {
            drawButton(g, mx, my, x0, y0, x1, y1, b.label, accent = b.accent) { b.onClick() }
        } else {
            roundRect(g, x0, y0, x1, y1, if (b.accent) 0x662563EB else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            SoulText.drawCenteredOn(g, b.label, (x0 + x1) / 2, (y0 + y1) / 2, fBody, Theme.TEXT_FAINT, forceCustom = true)
            region(x0, y0, x1, y1) {}
        }
        val tip = b.tip
        if (tip != null && mx in x0..x1 && my in y0..y1) tooltip(x0, y0, x1, y1, tip)
    }

    // ---------------------------------------------------------------- changelog

    private fun changelog(notes: List<ReleaseNote>) = Part(flex = true, height = { 0 }) { g, mx, my, x0, y0, x1, y1 ->
        val top = y0 + GAP
        panel(g, x0, top, x1, y1, NOTES_BG, NOTES_EDGE)
        val blocks = ReleaseNotesFormat.blocks(notes)
        val cx0 = x0 + 10
        val cx1 = x1 - 10
        notesScroll.view = y1 - top - 2
        notesScroll.content = blocksHeight(blocks, cx1 - cx0) + 16
        notesScroll.clamp()
        beginClip(g, x0 + 1, top + 1, x1 - 1, y1 - 1)
        var y = top + 8 - notesScroll.pos.roundToInt()
        if (blocks.isEmpty()) drawTextV(g, "No release notes were published for this version.", cx0, y, y + LINE, Theme.TEXT_DIM, F_TEXT)
        for ((i, b) in blocks.withIndex()) y += drawBlock(g, b, i == 0, cx0, y, cx1, draw = true)
        endClip(g)
        drawScrollbar(g, mx, my, notesScroll, x0, top, x1, y1)
    }

    private fun blocksHeight(blocks: List<Block>, w: Int): Int = blocks.withIndex().sumOf { (i, b) -> drawBlock(null, b, i == 0, 0, 0, w, draw = false) }

    /** Draws (or measures, with [g] null) one block; returns its height. */
    private fun drawBlock(g: GuiGraphicsExtractor?, b: Block, first: Boolean, x0: Int, y: Int, x1: Int, draw: Boolean): Int {
        if (b is Block.Version) {
            val top = if (first) 0 else 10
            if (draw && g != null) {
                val semi = MsdfFonts.menuSemibold
                drawTextV(g, b.version, x0, y + top, y + top + 15, Theme.TEXT, F_VER, semi)
                var x = x0 + textWidth(b.version, F_VER, semi) + 6
                if (b.beta) x += drawChip(g, x, y + top + 8, "BETA", Theme.BETA, Theme.BETA_BG) + 6
                b.date?.let { drawTextV(g, it, x, y + top, y + top + 15, 0xFF666666.toInt(), F_SUB) }
            }
            return top + 15 + 2
        }
        if (b is Block.Heading) {
            if (draw && g != null) drawTextV(g, b.text.uppercase(Locale.ROOT), x0, y + 8, y + 8 + 12, Theme.ACCENT_LIGHT, F_SUB, MsdfFonts.menuSemibold)
            return 8 + 12 + 3
        }
        if (b is Block.Bullet) {
            if (draw && g != null) drawTextV(g, "•", x0 + 2, y + 1, y + 1 + LINE, Theme.ACCENT, F_TEXT)
            return drawRich(if (draw) g else null, b.spans, x0 + 12, y + 1, x1, F_TEXT, 0xFFCCCCCC.toInt(), LINE) + 2
        }
        if (b is Block.Paragraph) return drawRich(if (draw) g else null, b.spans, x0, y, x1, F_TEXT, 0xFFAAAAAA.toInt(), LINE) + 4
        return 0
    }
}
