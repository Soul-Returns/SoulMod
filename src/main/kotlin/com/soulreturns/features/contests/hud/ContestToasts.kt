package com.soulreturns.features.contests.hud

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.core.events.Events
import com.soulreturns.features.contests.ContestEndedSignal
import com.soulreturns.features.contests.ContestInvitedSignal
import com.soulreturns.features.contests.ContestPassedSignal
import com.soulreturns.features.contests.ContestPlayerFinishedSignal
import com.soulreturns.features.contests.ContestRunFlaggedSignal
import com.soulreturns.features.contests.ContestStandingsUpdatedSignal
import com.soulreturns.features.contests.FriendRequestSignal
import com.soulreturns.features.contests.gui.ContestView
import com.soulreturns.features.contests.gui.ContestsFormat
import com.soulreturns.features.contests.gui.ContestsLayout
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f

/**
 * Contest toasts (mock `TOASTS`): someone passed you, someone finished, a flagged run, an invitation,
 * standings updated after the end, you won / it's over. A top-right stack, 6 s each; gated on
 * `contests.toasts`.
 */
object ContestToasts : MovableHud("contest_toasts", "Contest toasts", "general") {
    const val OK = 0xFF4ADE80.toInt()
    const val WARN = 0xFFF0A640.toInt()
    const val BAD = 0xFFE05252.toInt()
    const val INFO = 0xFF7FB0FF.toInt()

    class Toast(val kind: Int, val icon: String, val title: String, val text: String, val at: Long)

    private val live = ArrayList<Toast>()
    private const val MAX = 3
    private const val W = ContestsLayout.TOAST_W
    private const val PAD = 7
    private const val TITLE = 10f
    private const val BODY = 9f
    private const val LINE = 11

    var clock: () -> Long = System::currentTimeMillis

    fun register() {
        Events.subscribe<ContestPassedSignal> { s ->
            show(WARN, "ct_up", "${s.by.name} passed you",
                if (s.myRank != null && s.behind != null) "You're ${ContestsFormat.ord(s.myRank)} now · ${Format.short(s.behind)} behind ${ContestsFormat.ord(s.myRank - 1)}" else "Open /soul contests for the leaderboard")
        }
        Events.subscribe<ContestPlayerFinishedSignal> { s ->
            if (s.isMe) show(OK, "ct_trophy", "You finished ${s.contestName}", "Final total ${Format.short(s.total)}. ${s.stillGoing} still going.")
            else show(OK, "ct_trophy", "${s.player.name} finished", "Final total ${Format.short(s.total)}. ${s.stillGoing} ${if (s.stillGoing == 1) "player is" else "players are"} still going.")
        }
        Events.subscribe<ContestRunFlaggedSignal> { s ->
            show(BAD, "ct_flag", "Your run ${s.n ?: ""} was flagged".replace("  ", " "), (com.soulreturns.features.contests.gui.Checks.reason(s.reason) ?: "Hypixel's counters don't match it.") + " It still counts. /soul contests")
        }
        Events.subscribe<ContestInvitedSignal> { s ->
            val c = s.summary
            show(INFO, "ct_bell", "${c.invitedBy?.name ?: c.owner.name} invited you", "${c.name} · ${c.goal.label}. Join in chat or /soul contests")
        }
        Events.subscribe<ContestStandingsUpdatedSignal> { s ->
            val rank = s.newRank?.let { " You're ${ContestsFormat.ord(it)} now." } ?: ""
            show(WARN, "ct_refresh", "${s.contestName} standings updated", "${s.runner?.let { "A late run by ${it.name} counted." } ?: "A late run counted."}$rank")
        }
        Events.subscribe<FriendRequestSignal> { s -> show(INFO, "ct_user_plus", "${s.from.name} wants to be friends", "Accept in chat or /soul friends") }
        Events.subscribe<ContestEndedSignal> { s -> onEnded(s) }
    }

    /** The end toast; also the whole notice when the end animation is Off. */
    private fun onEnded(s: ContestEndedSignal) {
        val snap = s.snapshot
        val v = ContestView(snap, com.soulreturns.features.contests.ContestsStore.selfUuid() ?: "")
        if (v.canceled) {
            show(BAD, "ct_ban", "${snap.contest.name} was canceled", snap.contest.canceledReason?.takeIf { it.isNotEmpty() } ?: "Nothing from it counts.")
            return
        }
        val w = v.ranked.firstOrNull()
        val me = v.me
        if (s.won && me != null) {
            val second = v.ranked.getOrNull(1)
            show(OK, "ct_crown", "You won ${snap.contest.name}!", "${ContestsFormat.metric(v.type, me.total)} from ${me.units} ${v.type.unit.plural}" + (second?.let { " · ${Format.short(me.total - it.total)} ahead of 2nd" } ?: ""))
        } else {
            show(OK, "ct_trophy", "${snap.contest.name} is over", (w?.let { "${it.name} wins with ${Format.short(it.total)}." } ?: "") + (me?.rank?.let { " You placed ${ContestsFormat.ord(it)}." } ?: "") + " /soul contests")
        }
    }

    fun show(kind: Int, icon: String, title: String, text: String) {
        if (!cfg.contests.toasts) return
        live += Toast(kind, icon, title, text, clock())
        while (live.size > MAX) live.removeAt(0)
    }

    private fun active(): List<Toast> {
        val now = clock()
        live.removeAll { now - it.at > ContestsLayout.TOAST_MS }
        return live
    }

    private val PREVIEW = listOf(Toast(WARN, "ct_up", "A friend passed you", "You're 3rd now · 1.2M behind 2nd", 0))

    override fun isEnabled(): Boolean = cfg.contests.toasts && active().isNotEmpty()

    override fun configuredOn(): Boolean = cfg.contests.toasts

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "contests")

    override fun readTransform(): HudTransform = cfg.contests.toastTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(contests = contests.copy(toastTransform = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().contests.toastTransform

    private class Card(val t: Toast, val lines: List<String>, val h: Int)

    private fun layout(preview: Boolean): List<Card> {
        val list = if (preview) PREVIEW else active()
        val choice = fontChoiceIndex()
        val font = MsdfFonts.regularFor(choice)
        return list.map { t ->
            val lines = wrap(t.text, W - PAD * 2 - 20, font, choice)
            Card(t, lines, PAD + LINE + 2 + lines.size * LINE + PAD)
        }
    }

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val cards = layout(preview)
        if (cards.isEmpty()) return 0 to 0
        return W to cards.sumOf { it.h } + 4 * (cards.size - 1)
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val choice = fontChoiceIndex()
        val shadow = textShadow()
        val now = clock()
        var y = 0
        for (c in layout(preview)) {
            val age = if (preview) 0L else now - c.t.at
            val fade = if (age > ContestsLayout.TOAST_MS - 500) ((ContestsLayout.TOAST_MS - age) / 500f).coerceIn(0f, 1f) else 1f
            fun a(color: Int) = (((color ushr 24) * fade).toInt() shl 24) or (color and 0xFFFFFF)
            rect(g, 0, y, W, y + c.h, a(0xF0181818.toInt()))
            SoulPanel.slice(g, 0, y, W, y + c.h, SoulPanel.SMALL, 0, y, 3, y + c.h, a(c.t.kind))
            val id = net.minecraft.resources.Identifier.fromNamespaceAndPath("soul", "textures/gui/icons/${c.t.icon}.png")
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, PAD + 2, y + PAD, 0f, 0f, 14, 14, 64, 64, 64, 64, a(c.t.kind))
            val tx = PAD + 22
            SoulText.draw(g, c.t.title, tx, y + PAD, TITLE, a(0xFFEEEEEE.toInt()), font = MsdfFonts.semiboldFor(choice), shadow = shadow, fontChoice = choice)
            var ly = y + PAD + LINE + 2
            for (l in c.lines) {
                SoulText.draw(g, l, tx, ly, BODY, a(0xFFAAAAAA.toInt()), font = MsdfFonts.regularFor(choice), shadow = shadow, fontChoice = choice)
                ly += LINE
            }
            y += c.h + 4
        }
    }

    private fun wrap(text: String, maxW: Int, font: com.soulreturns.config.gui.render.MsdfFont, choice: Int): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isNotEmpty() && SoulText.width(cand, BODY, font, fontChoice = choice) > maxW) {
                out += cur
                cur = word
            } else cur = cand
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun rect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, pipeline: RenderPipeline = SoulRenderPipelines.ROUND_SMALL) {
        g.guiRenderState.addGuiElement(RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null))
    }
}
