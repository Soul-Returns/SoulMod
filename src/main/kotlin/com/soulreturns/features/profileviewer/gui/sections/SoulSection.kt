package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.mining.mineshaft.MineshaftStatsSection
import com.soulreturns.features.profileviewer.gui.LocalSoulCards
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileViewerState
import com.soulreturns.features.profileviewer.gui.SoulCards
import com.soulreturns.features.profileviewer.gui.kit.CardNode
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.IconNode
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.RowNode
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.TextNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import kotlinx.serialization.json.JsonObject

/**
 * What Soul recorded that Hypixel does not track (mock `soulSec`): every shared soul-stats namespace,
 * your own local trackers, and for yourself the "Share my Soul stats" switch.
 *
 * A namespace with a designed card is drawn by its feature ([RENDERERS]); any other is drawn
 * generically ([SoulCards.generic]), so a backend namespace that ships before a mod release still shows.
 */
object SoulSection : ProfileSection {
    override val id = "soul"
    override val title = "Soul"
    override val group = ProfileSection.GROUP_SOUL
    override val icon = "pv_soul"
    override val subtitle = "What Soul recorded, which Hypixel doesn't track"

    /** Namespaces with their own card, living with their feature. False = could not read it (generic takes over). */
    private val RENDERERS: Map<String, (Ui, ProfileContext, JsonObject) -> Boolean> = mapOf(
        "mineshaft" to { ui, ctx, payload -> MineshaftStatsSection.card(ui, ctx, payload, full = true) },
    )

    /**
     * Cards built from this computer's `stats.json` (ONLY YOU), yourself only, in the mock's order. Each
     * appends a [CardNode.LOCAL] card (an empty state when its bucket is empty) and returns whether it had data.
     */
    private val LOCAL_CARDS: List<(Ui, ProfileContext) -> Boolean> = listOf(
        { ui, ctx -> LocalSoulCards.nucleus(ui, ctx) },
        { ui, ctx -> LocalSoulCards.fishing(ui, ctx) },
        { ui, ctx -> LocalSoulCards.dragons(ui, ctx) },
        { ui, ctx -> LocalSoulCards.detector(ui, ctx) },
        { ui, ctx -> LocalSoulCards.rng(ui, ctx) },
    )

    /** `.in-t span`. */
    private val INTRO_TEXT = 0xFF9AA3B5.toInt()

    override fun muted(ctx: ProfileContext): Boolean = ctx.soulState != ProfileViewerState.SOUL_DATA

    override fun build(ctx: ProfileContext, ui: Ui) {
        val state = ctx.soulState
        val loading = state == ProfileViewerState.SOUL_LOADING
        val data = state == ProfileViewerState.SOUL_DATA
        if (!ctx.isSelf && !data && !loading) {
            ui.space(40)
            SoulCards.empty(ui, ctx, compact = false, maxW = 360)
            return
        }
        if (ctx.isSelf) {
            intro(ui, CardNode.MINE, "pv_soul", "What Soul recorded for you") {
                add(
                    ChipTextNode(
                        "Hypixel doesn't track these. Cards marked",
                        Chip.onlyYou(),
                        "never leave your computer; the others reach other Soul users only while sharing is on.",
                    ),
                )
                space(6)
                toggle("Share my Soul stats", ctx.shareSoulStats, big = true) { ctx.setShareSoulStats(!ctx.shareSoulStats) }
            }
        } else {
            intro(ui, CardNode.SHARED, "pv_share", "${ctx.name} shares what Soul records") {
                add(TextNode(Rich.of("Collected by Soul while they play, not by Hypixel. They can turn sharing off at any time."), KitStyle.SMALL, INTRO_TEXT, lineH = INTRO_LH))
            }
        }
        ui.grid(300) {
            if (loading) {
                card("Glacite Mineshafts", kind = SoulCards.kind(ctx), span = Node.FULL) { skeleton(4) }
            }
            for ((key, payload) in ctx.soulNamespaces) {
                if (RENDERERS[key]?.invoke(this, ctx, payload) != true) SoulCards.generic(this, ctx, key, payload)
            }
            if (ctx.isSelf) {
                var local = false
                val cards = collect { for (card in LOCAL_CARDS) local = card(this, ctx) || local }
                // With nothing recorded anywhere, the one empty state below says it instead of five.
                if (local || data || loading) cards.forEach(::add)
                if (!data && !loading && !local) card(null, span = Node.FULL) { SoulCards.empty(this, ctx, compact = false) }
            }
        }
    }

    /** `.c.soul.intro`: a 22 px icon, then the bold title over [body] in the text column (`.in-t`). */
    private fun intro(ui: Ui, kind: Int, icon: String, title: String, body: Ui.() -> Unit) {
        ui.card(null, kind = kind, padX = 14, padTop = 11, padBottom = 12) {
            add(
                RowNode(
                    listOf(
                        IconNode(icon, 22, KitStyle.ACCENT_LIGHT),
                        StackNode(listOf(TextNode(Rich.of(title), KitStyle.HEADING, KitStyle.TEXT, bold = true, lineH = 16)) + collect(body), 2),
                    ),
                    gap = 10,
                    alignTop = true,
                ),
            )
        }
    }

    private const val INTRO_LH = 14

    /** Intro text with [chip] inline between [before] and [after]; words wrap around the chip as one unit. */
    private class ChipTextNode(before: String, val chip: Chip, after: String) : Node() {
        private val words: List<String?> = before.split(' ').filter { it.isNotEmpty() } + listOf(null) + after.split(' ').filter { it.isNotEmpty() }

        private class Piece(val text: String?, val x: Int, val line: Int)

        private fun layout(m: KitMeasure, w: Int): List<Piece> {
            val space = m.width(" ", KitStyle.SMALL)
            val out = ArrayList<Piece>(words.size)
            var x = 0
            var line = 0
            for (word in words) {
                val gap = if (word == null) space + CHIP_MARGIN else space
                val ww = if (word == null) chip.width(m) else m.width(word, KitStyle.SMALL)
                if (x > 0 && x + gap + ww > w) {
                    line++
                    x = 0
                }
                val px = if (x == 0) 0 else x + gap
                out += Piece(word, px, line)
                x = px + ww
            }
            return out
        }

        override fun measure(m: KitMeasure, w: Int): Int = ((layout(m, w).lastOrNull()?.line ?: -1) + 1) * INTRO_LH

        override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
            val run = StringBuilder()
            var runX = 0
            var runLine = 0
            fun flush() {
                if (run.isEmpty()) return
                val ly = y + runLine * INTRO_LH
                c.text(run.toString(), x + runX, ly, ly + INTRO_LH, INTRO_TEXT, KitStyle.SMALL)
                run.setLength(0)
            }
            for (p in layout(c, w)) {
                if (p.text == null) {
                    flush()
                    chip.paint(c, x + p.x, y + p.line * INTRO_LH + INTRO_LH / 2 - 1)
                    continue
                }
                if (run.isNotEmpty() && p.line != runLine) flush()
                if (run.isEmpty()) {
                    runX = p.x
                    runLine = p.line
                } else {
                    run.append(' ')
                }
                run.append(p.text)
            }
            flush()
        }

        private companion object {
            const val CHIP_MARGIN = 5
        }
    }
}
