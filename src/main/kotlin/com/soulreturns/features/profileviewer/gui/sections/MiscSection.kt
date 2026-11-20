package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.EssenceAmount
import com.soulreturns.data.hypixel.auctionStats
import com.soulreturns.data.hypixel.bankBalance
import com.soulreturns.data.hypixel.activeMemberCount
import com.soulreturns.data.hypixel.essenceAmounts
import com.soulreturns.data.hypixel.experimentBests
import com.soulreturns.data.hypixel.fairySouls
import com.soulreturns.data.hypixel.firstJoinMs
import com.soulreturns.data.hypixel.giftStats
import com.soulreturns.data.hypixel.highestCriticalHit
import com.soulreturns.data.hypixel.lifetimeDeaths
import com.soulreturns.data.hypixel.lifetimeKills
import com.soulreturns.data.hypixel.mythologicalKills
import com.soulreturns.data.hypixel.personalBank
import com.soulreturns.data.hypixel.purse
import com.soulreturns.data.hypixel.soulflowAmount
import com.soulreturns.data.items.ItemIcons
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.CustomNode
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitLayout
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui

/**
 * Coins, essence, combat, auctions, experiments and everything else (mock `miscSec`). Every value the
 * response lacks shows a dash; a profile without the Banking API shows "Banking API off".
 */
object MiscSection : ProfileSection {
    override val id = "misc"
    override val title = "Misc"
    override val group = ProfileSection.GROUP_OTHER
    override val icon = "pv_dots"
    override val subtitle = "Coins, essence and everything else"

    private const val DASH = "—"

    /** Mock `coins`: exact below 10k, abbreviated above. */
    private fun coins(v: Double): String = if (v >= 1e4) Format.short(v) else Format.num(v)

    private fun coin(v: Double?): Rich = if (v == null) Rich.of(DASH) else Rich.colored(Format.num(v), S.COIN)

    private fun coinShort(v: Long): Rich = Rich.colored(Format.short(v), S.COIN)

    private fun num(v: Long?): String = v?.let { Format.num(it) } ?: DASH

    override fun build(ctx: ProfileContext, ui: Ui) {
        val m = ctx.member
        val bank = ctx.profile.bankBalance
        val coop = ctx.profile.activeMemberCount > 1
        val essence = m.essenceAmounts
        val auctions = m.auctionStats
        val gifts = m.giftStats
        val exp = m.experimentBests
        ui.grid(260) {
            card("Coins") {
                kvs {
                    kv("Purse", coin(m.purse))
                    if (bank == null) {
                        kv("Bank", "Banking API off", S.TEXT_FAINT)
                    } else if (coop) {
                        kv("Co-op bank", coin(bank))
                        kv("Personal bank", coin(m.personalBank))
                    } else {
                        kv("Bank", coin(bank))
                    }
                }
            }
            val worth = essence?.sumOf { it.amount * PriceCache.value("ESSENCE_${it.type}", PriceSource.BAZAAR_INSTANT_SELL) } ?: 0L
            card("Essence", sub = if (worth > 0) "worth ${coins(worth.toDouble())}" else null) {
                if (essence == null) text("Not reported for this profile.", color = S.TEXT_DIM) else add(essenceGrid(essence))
            }
            card("Combat") {
                kvs {
                    kv("Kills", num(m.lifetimeKills))
                    kv("Deaths", num(m.lifetimeDeaths))
                    kv("Highest critical hit", m.highestCriticalHit?.let { Format.num(it) } ?: DASH)
                    kv("Mythological kills", num(m.mythologicalKills))
                }
            }
            card("Auctions") {
                kvs {
                    kv("Won", num(auctions?.won))
                    kv("Created", num(auctions?.created))
                    kv("Coins spent", if (auctions == null) Rich.of(DASH) else coinShort(auctions.coinsSpent))
                    kv("Coins earned", if (auctions == null) Rich.of(DASH) else coinShort(auctions.coinsEarned))
                }
            }
            card("Experimentation Table") {
                kvs {
                    kv("Superpairs", exp?.superpairsName ?: DASH)
                    kv("Chronomatron best", exp?.chronomatronBest?.let { "$it rounds" } ?: DASH)
                    kv("Ultrasequencer best", exp?.ultrasequencerBest?.let { "$it rounds" } ?: DASH)
                }
            }
            card("Everything else") {
                kvs {
                    kv("Fairy souls", m.fairySouls?.let { Format.num(it) } ?: DASH)
                    kv("Gifts given / received", gifts?.let { "${Format.num(it.first)} / ${Format.num(it.second)}" } ?: DASH)
                    kv("Soulflow", num(m.soulflowAmount))
                    kv("First joined", m.firstJoinMs?.let { Format.date(it) } ?: DASH)
                }
            }
        }
    }

    // `.ess`: four equal columns 6 apart; each cell has a dim label over a bold value (padding 4 6), the essence's
    // icon right of the value.
    private const val ESS_COLS = 4
    private const val ESS_GAP = 6
    private const val ESS_CELL_H = 34
    private const val ESS_ICON = 12

    private fun essenceGrid(list: List<EssenceAmount>): Node =
        CustomNode({ _, _ ->
            val rows = (list.size + ESS_COLS - 1) / ESS_COLS
            if (rows == 0) 0 else rows * ESS_CELL_H + (rows - 1) * ESS_GAP
        }) { c, x, y, w, _ ->
            val tracks = KitLayout.trackX(w, ESS_COLS, ESS_GAP)
            for ((i, e) in list.withIndex()) {
                val col = i % ESS_COLS
                val cx = x + tracks[col]
                val cw = KitLayout.spanWidth(tracks, col, 1, ESS_GAP)
                val cy = y + (i / ESS_COLS) * (ESS_CELL_H + ESS_GAP)
                if (cy + ESS_CELL_H < c.viewTop || cy > c.viewBottom) continue
                c.rect(cx, cy, cx + cw, cy + ESS_CELL_H, S.TIER_BG, Corner.SMALL)
                c.text(e.name, cx + 6, cy + 4, cy + 16, S.TEXT_DIM, S.TINY)
                c.text(Format.short(e.amount), cx + 6, cy + 16, cy + 30, S.TEXT, S.BODY, true)
                ItemIcons.icon("ESSENCE_${e.type}")?.let { c.item(it, cx + cw - 6 - ESS_ICON, cy + 16 + (14 - ESS_ICON) / 2, ESS_ICON, count = false) }
            }
        }
}
