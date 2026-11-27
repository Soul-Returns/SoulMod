package com.soulreturns.ui.hud.movable

import com.soulreturns.config.cfg
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.numbers.StyledFormat
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam

/**
 * Where vanilla draws its HUD at the current GUI scale, mirroring `Hud`/`ChatComponent`/
 * `BossHealthOverlay`, converted to Soul px. HUDs attach to these elements and the Move GUI snaps to
 * them, so a link to the hotbar holds at any MC GUI scale against any Soul scale.
 */
object VanillaHudLayout {
    /** Width of the action bar slot in GUI px (its text varies; the slot keeps links and snapping stable). */
    private const val ACTION_BAR_W = 220

    /**
     * GUI px between the bottom of vanilla's action bar line and the bottom of the screen (`Hud` centers
     * the message at `guiH - 68` and draws it 4 px up, 9 px tall: `guiH - 72 .. guiH - 63`).
     */
    const val ACTION_BAR_LIFT = 63

    /** Soul px from the bottom of the screen up to vanilla's action bar line, as a bottom-anchored offset (negative). */
    fun actionBarOffsetY(factor: Float): Float = -ACTION_BAR_LIFT / factor

    /**
     * Every element in Soul px ([factor] = `SoulUi.factor()`, GUI px per Soul px). [guiW]/[guiH] are
     * the GUI-scaled window size.
     */
    fun elements(guiW: Int, guiH: Int, factor: Float): Map<String, VanillaElement> {
        val mc = Minecraft.getInstance()
        val opts = mc.options
        // Chat: drawn under scale(chatScale) + translate(4, 0) with a 4 px inset, bottom 40 px up.
        val chatScale = opts.chatScale().get()
        val chatW = (ChatComponent.getWidth(opts.chatWidth().get()) + 12 * chatScale).toInt()
        val chatH = (ChatComponent.getHeight(opts.chatHeightUnfocused().get()) * chatScale).toInt()
        val cx = guiW / 2
        // Hidden while Soul's scoreboard replaces it: no link target, pick or snap guide (like the action bar).
        val scoreboard =
            if (com.soulreturns.features.scoreboard.Scoreboard.hidesVanilla()) null else sidebarObjective(mc)?.let { sidebarRect(mc, it, guiW, guiH) }
        val bossShown = mc.gui.hud.bossOverlay.events.isNotEmpty()
        return build(
            guiW,
            guiH,
            factor,
            chat = intArrayOf(0, guiH - 40 - chatH, chatW, guiH - 40),
            scoreboard = scoreboard,
            bossShown = bossShown,
            cx = cx,
            actionBarHidden = cfg.general.soulActionBar,
        )
    }

    /** The pure half of [elements] (rects in GUI px → Soul px, hidden/collapsed states). */
    fun build(
        guiW: Int,
        guiH: Int,
        factor: Float,
        chat: IntArray,
        scoreboard: IntArray?,
        bossShown: Boolean,
        cx: Int = guiW / 2,
        actionBarHidden: Boolean = false,
    ): Map<String, VanillaElement> {
        fun soul(r: IntArray): FloatArray = floatArrayOf(r[0] / factor, r[1] / factor, r[2] / factor, r[3] / factor)
        val bossBar = intArrayOf(cx - 91, 3, cx + 91, 17)
        val out = LinkedHashMap<String, VanillaElement>()
        fun add(id: String, rect: IntArray, hidden: Boolean = false, collapsed: IntArray = rect) {
            out[id] = VanillaElement(id, VanillaIds.name(id), soul(rect), hidden, soul(collapsed))
        }
        add(VanillaIds.HOTBAR, intArrayOf(cx - 91, guiH - 22, cx + 91, guiH))
        add(VanillaIds.STATUS, intArrayOf(cx - 91, guiH - 39, cx + 91, guiH - 22))
        // Vanilla centers the message at (guiW / 2, guiH - 68) and draws it 4 px up: one text line.
        // Hidden while Soul draws the action bar (vanilla's is replaced): no link target, pick or snap guide.
        val actionBar = intArrayOf(cx - ACTION_BAR_W / 2, guiH - ACTION_BAR_LIFT - 9, cx + ACTION_BAR_W / 2, guiH - ACTION_BAR_LIFT)
        add(VanillaIds.ACTION_BAR, actionBar, hidden = actionBarHidden, collapsed = intArrayOf(cx, actionBar[1], cx, actionBar[3]))
        add(VanillaIds.CHAT, chat)
        // Hidden: a zero-size box at its pin (right edge, vertical middle / top center).
        val sbCollapsed = intArrayOf(guiW - 1, guiH / 2, guiW - 1, guiH / 2)
        add(VanillaIds.SCOREBOARD, scoreboard ?: sbCollapsed, hidden = scoreboard == null, collapsed = sbCollapsed)
        add(VanillaIds.BOSS_BAR, bossBar, hidden = !bossShown, collapsed = intArrayOf(cx - 91, 0, cx + 91, 0))
        return out
    }

    private fun sidebarObjective(mc: Minecraft): Objective? {
        val level = mc.level ?: return null
        val board = level.scoreboard
        val team = mc.player?.let { board.getPlayersTeam(it.scoreboardName) }
        val color = team?.color?.orElse(null)
        return color?.let { board.getDisplayObjective(it.displaySlot()) } ?: board.getDisplayObjective(DisplaySlot.SIDEBAR)
    }

    /** `Hud.displayScoreboardSidebar`'s geometry: 15 lines max, bottom a third below the middle. */
    private fun sidebarRect(mc: Minecraft, objective: Objective, guiW: Int, guiH: Int): IntArray {
        val font = mc.font
        val board = objective.scoreboard
        val format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)
        val entries =
            board.listPlayerScores(objective)
                .filter { !it.isHidden }
                .sortedWith(compareByDescending<PlayerScoreEntry> { it.value() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() })
                .take(15)
        val spacer = font.width(": ")
        var width = font.width(objective.displayName)
        for (entry in entries) {
            val name = PlayerTeam.formatNameForTeam(board.getPlayersTeam(entry.owner()), entry.ownerName())
            val scoreW = font.width(entry.formatValue(format))
            width = maxOf(width, font.width(name) + if (scoreW > 0) spacer + scoreW else 0)
        }
        val listH = entries.size * font.lineHeight
        val bottom = guiH / 2 + listH / 3
        val left = guiW - width - 3
        return intArrayOf(left - 2, bottom - listH - font.lineHeight - 1, guiW - 1, bottom)
    }
}
