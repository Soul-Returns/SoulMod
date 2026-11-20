package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.mining.mineshaft.MineshaftStatsSection
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileViewerState
import com.soulreturns.features.profileviewer.gui.SoulCards
import com.soulreturns.features.profileviewer.gui.kit.CardNode
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Node
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
     * Cards built from this computer's `stats.json` (ONLY YOU), shown for yourself only: Crystal
     * Nucleus, fishing, dragons, Metal Detector, RNG meter. Phase 3 adds them here, each one function
     * that appends a [CardNode.LOCAL] card and returns whether it had anything to show.
     */
    private val LOCAL_CARDS: List<(Ui, ProfileContext) -> Boolean> = emptyList()

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
            ui.card(null, kind = CardNode.MINE) {
                row(10) {
                    icon("pv_soul", 22, KitStyle.ACCENT_LIGHT)
                    stack(2) {
                        text("What Soul recorded for you", bold = true)
                        text("Hypixel doesn't track these. Cards marked ONLY YOU never leave your computer; the others reach other Soul users only while sharing is on.", KitStyle.SMALL, KitStyle.TEXT_DIM)
                    }
                }
                toggle("Share my Soul stats", ctx.shareSoulStats, big = true) { ctx.setShareSoulStats(!ctx.shareSoulStats) }
            }
        } else {
            ui.card(null, kind = CardNode.SHARED) {
                row(10) {
                    icon("pv_share", 22, KitStyle.ACCENT_LIGHT)
                    stack(2) {
                        text("${ctx.name} shares what Soul records", bold = true)
                        text("Collected by Soul while they play, not by Hypixel. They can turn sharing off at any time.", KitStyle.SMALL, KitStyle.TEXT_DIM)
                    }
                }
            }
        }
        ui.grid(300) {
            if (loading) {
                card("Glacite Mineshafts", kind = SoulCards.kind(ctx), span = Node.FULL) { skeleton(4) }
            }
            for ((key, payload) in ctx.soulNamespaces) {
                if (RENDERERS[key]?.invoke(this, ctx, payload) != true) SoulCards.generic(this, ctx, key, payload)
            }
            var local = false
            if (ctx.isSelf) for (card in LOCAL_CARDS) local = card(this, ctx) || local
            if (ctx.isSelf && !data && !loading && !local) {
                card(null, span = Node.FULL) { SoulCards.empty(this, ctx, compact = false) }
            }
        }
    }
}
