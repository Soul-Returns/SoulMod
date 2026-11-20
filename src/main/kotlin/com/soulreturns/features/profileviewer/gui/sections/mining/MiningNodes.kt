package com.soulreturns.features.profileviewer.gui.sections.mining

import com.soulreturns.data.items.ItemIcons
import com.soulreturns.data.hypixel.HotmNodeDef
import com.soulreturns.data.hypixel.HotmNodeState
import com.soulreturns.data.hypixel.HotmProgress
import com.soulreturns.data.hypixel.HotmTree
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Inline
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.framed
import com.soulreturns.features.profileviewer.gui.kit.slot
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The Heart of the Mountain grid as the menu lays it out (mock `.htree`): tier 10 on top, seven columns on
 * a 28 px pitch, the tier column on the left, prerequisite connectors under the nodes. [states] and
 * [specs] are by node id; stacks are made when painted.
 */
class HotmTreeNode(
    private val tree: HotmTree,
    private val p: HotmProgress,
    private val states: Map<String, HotmNodeState>,
    private val specs: Map<String, HotmLore.Spec>,
) : Node(), Inline {
    private val rows = tree.maxTier
    private val cols = max(1, tree.nodes.maxOfOrNull { it.col } ?: 1)
    private val gridW = LABEL_W + cols * PITCH - (PITCH - SLOT)
    private val gridH = rows * PITCH - (PITCH - SLOT)

    override fun inlineWidth(m: KitMeasure): Int = gridW + MARGIN
    override fun measure(m: KitMeasure, w: Int): Int = gridH + MARGIN * 2

    private fun nx(ox: Int, def: HotmNodeDef) = ox + LABEL_W + (def.col - 1) * PITCH
    private fun ny(oy: Int, tier: Int) = oy + (rows - tier) * PITCH

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val ox = x + MARGIN
        val oy = y + MARGIN
        for (tier in rows downTo 1) {
            val ly = ny(oy, tier)
            val on = tier <= p.tier
            val next = tier == p.tier + 1
            c.rect(ox, ly, ox + SLOT, ly + SLOT, if (on) TIER_ON_BG else if (next) TIER_NEXT_BG else TIER_OFF_BG, Corner.SMALL)
            val label = tier.toString()
            val lw = c.width(label, LABEL_SIZE, true)
            c.text(label, ox + (SLOT - lw) / 2, ly, ly + SLOT, if (on) TIER_ON else if (next) TIER_NEXT else TIER_OFF, LABEL_SIZE, true)
            if (c.hovered(ox, ly, ox + SLOT, ly + SLOT)) c.tooltip(ox, ly, ox + SLOT, ly + SLOT, HotmLore.tierTip(tree, p, tier), "hotm:tier:$tier")
        }
        for (child in tree.nodes) {
            val parent = child.requires?.let { tree.node(it) } ?: continue
            val ax = nx(ox, child)
            val ay = ny(oy, child.tier)
            val bx = nx(ox, parent)
            val by = ny(oy, parent.tier)
            val color = if (p.edgeOn(child)) EDGE_ON else EDGE_OFF
            if (ay == by) {
                c.rect(min(ax, bx) + 10, ay + 9, min(ax, bx) + 10 + abs(ax - bx), ay + 11, color, Corner.SQUARE)
            } else {
                c.rect(ax + 9, min(ay, by) + 10, ax + 11, min(ay, by) + 10 + abs(ay - by), color, Corner.SQUARE)
            }
        }
        for (def in tree.nodes) {
            val st = states[def.id] ?: continue
            val spec = specs[def.id] ?: continue
            node(c, nx(ox, def), ny(oy, def.tier), st, spec)
        }
    }

    /** One `.hn`: the slot (selected = accent outline, not unlocked = darker), the item, its level, the veils. */
    private fun node(c: KitCanvas, x: Int, y: Int, st: HotmNodeState, spec: HotmLore.Spec) {
        val stack = HotmItems.stack(spec)
        val hover = c.hovered(x, y, x + SLOT, y + SLOT)
        if (st.selected) c.framed(x, y, x + SLOT, y + SLOT, SEL_BG, S.ACCENT, Corner.SQUARE)
        else if (hover) c.framed(x, y, x + SLOT, y + SLOT, S.SLOT_HOVER_BG, S.SLOT_HOVER_EDGE, Corner.SQUARE)
        else c.framed(x, y, x + SLOT, y + SLOT, if (st.level == 0) LOCKED_BG else S.SLOT_BG, S.SLOT_EDGE, Corner.SQUARE)
        c.item(stack, x + 2, y + 2, 16, count = false)
        // `.hn.off .mc-item { opacity: .35 }`: items cannot be drawn translucent, so a veil goes on top.
        if (st.disabled) c.rect(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, OFF_VEIL, Corner.SQUARE)
        if (spec.count > 1) {
            // On a dark chip in the slot's corner, smaller from three digits on (Mole's 182), so it never melts into the item.
            val label = spec.count.toString()
            val size = if (label.length >= 3) COUNT_SIZE_SMALL else COUNT_SIZE
            val lw = c.width(label, size, true)
            c.rect(x + SLOT - lw - 2, y + 12, x + SLOT + 1, y + SLOT + 1, COUNT_BG, Corner.SMALL)
            c.text(label, x + SLOT - lw, y + 12, y + SLOT + 1, 0xFFFFFFFF.toInt(), size, true)
        }
        // `.hn.tlock { opacity: .32 }` over the card background.
        if (!st.open) c.rect(x, y, x + SLOT, y + SLOT, TLOCK_VEIL, Corner.SQUARE)
        if (hover) c.itemTooltip(stack)
    }

    companion object {
        /** Px between node origins (20 px slot + 8 px gap), the tier label column, the outer margin (mock `.htree`). */
        const val PITCH = 28
        const val SLOT = 20
        const val LABEL_W = 30
        const val MARGIN = 2

        private val LABEL_SIZE = S.px(10f)
        private val COUNT_SIZE = S.px(9f)
        private val COUNT_SIZE_SMALL = S.px(8f)
        private val COUNT_BG = 0xE0141414.toInt()
        private val TIER_OFF_BG = 0xFF202020.toInt()
        private val TIER_OFF = 0xFF555555.toInt()
        private val TIER_ON_BG = 0xFF1C2A1C.toInt()
        private val TIER_ON = 0xFF55FF55.toInt()
        private val TIER_NEXT_BG = 0xFF2A2A18.toInt()
        private val TIER_NEXT = 0xFFFFFF55.toInt()
        private val EDGE_OFF = 0xFF2C2C2C.toInt()
        private val EDGE_ON = 0xFF3F8F4F.toInt()
        private val SEL_BG = 0xFF1C2331.toInt()
        private val LOCKED_BG = 0xFF222222.toInt()
        private val OFF_VEIL = 0xA62B2B2B.toInt()
        private val TLOCK_VEIL = 0xAD1E1E1E.toInt()
    }
}

/**
 * `.hotm`: the tree beside the summary (`flex-wrap`, gap 14 / 24); the summary is `flex: 1 1 260px`,
 * at most 420 wide, and drops below the tree when both do not fit.
 */
class HotmLayoutNode(private val tree: HotmTreeNode, private val side: Node) : Node() {
    private fun beside(m: KitMeasure, w: Int) = w >= tree.inlineWidth(m) + GAP_X + SIDE_MIN

    private fun sideW(m: KitMeasure, w: Int) = if (beside(m, w)) min(SIDE_MAX, w - tree.inlineWidth(m) - GAP_X) else min(SIDE_MAX, w)

    override fun measure(m: KitMeasure, w: Int): Int {
        val th = tree.height(m, tree.inlineWidth(m))
        val sh = side.height(m, sideW(m, w))
        return if (beside(m, w)) max(th, sh) else th + GAP_Y + sh
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val tw = tree.inlineWidth(c)
        tree.paint(c, x, y, tw, tree.height(c, tw))
        val sw = sideW(c, w)
        val sh = side.height(c, sw)
        if (beside(c, w)) side.paint(c, x + tw + GAP_X, y, sw, sh)
        else side.paint(c, x, y + tree.height(c, tw) + GAP_Y, sw, sh)
    }

    private companion object {
        const val GAP_X = 24
        const val GAP_Y = 14
        const val SIDE_MIN = 260
        const val SIDE_MAX = 420
    }
}

/** `.hs-ab`: the selected pickaxe ability as its menu item, or a faint line without one. */
class AbilityNode(private val spec: HotmLore.Spec?, private val name: String?) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 32

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.rect(x, y, x + w, y + h, BG, Corner.SMALL)
        if (spec == null || name == null) {
            c.text("No pickaxe ability yet", x + 8, y, y + h, S.TEXT_FAINT, S.BODY)
            return
        }
        c.slot(HotmItems.stack(spec), x + 8, y + 6, S.SLOT)
        c.text(name, x + 36, y + 3, y + 17, S.TEXT, S.BODY, true)
        c.text("Selected pickaxe ability", x + 36, y + 17, y + 29, S.TEXT_DIM, S.CAPTION)
    }

    private companion object {
        val BG = 0xFF1A1A1A.toInt()
    }
}

/** One `.hp` row: the powder's name in its color, what the player holds, and total / spent at the right. */
class PowderRow(val name: String, val color: Int, val value: String, val detail: String?)

/** `.hpw`: the three powders. */
class PowdersNode(private val rows: List<PowderRow>) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = if (rows.isEmpty()) 0 else rows.size * ROW_H + (rows.size - 1) * GAP

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var ry = y
        for (r in rows) {
            c.text(r.name, x, ry, ry + ROW_H, r.color, S.BODY)
            c.text(r.value, x + NAME_W + 8, ry, ry + ROW_H, S.TEXT, VALUE_SIZE, true)
            if (r.detail != null) {
                val left = x + NAME_W + 8 + c.width(r.value, VALUE_SIZE, true) + 8
                val text = Rich.of(r.detail).ellipsize(c, S.CAPTION, x + w - left)
                val dw = text.width(c, S.CAPTION)
                text.draw(c, x + w - dw, ry, ry + ROW_H, S.CAPTION, DETAIL)
            }
            ry += ROW_H + GAP
        }
    }

    private companion object {
        const val ROW_H = 16
        const val GAP = 4
        const val NAME_W = 70
        val VALUE_SIZE = S.px(12f)
        val DETAIL = 0xFF777777.toInt()
    }
}

/** `.hs-leg`: what the menu's icons mean, each a small slot with its label; wraps. */
class LegendNode(private val entries: List<Pair<HotmLore.Spec, String>>) : Node() {
    private fun entryW(m: KitMeasure, label: String) = ICON + 4 + m.width(label, S.CAPTION)

    /** Entry x offsets and rows at width [w]. */
    private fun place(m: KitMeasure, w: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var cx = 0
        var row = 0
        for ((_, label) in entries) {
            val ew = entryW(m, label)
            if (cx > 0 && cx + ew > w) {
                cx = 0
                row++
            }
            out += cx to row
            cx += ew + GAP_X
        }
        return out
    }

    override fun measure(m: KitMeasure, w: Int): Int {
        val rows = (place(m, w).maxOfOrNull { it.second } ?: -1) + 1
        return if (rows == 0) 0 else rows * ICON + (rows - 1) * GAP_Y
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        for ((i, at) in place(c, w).withIndex()) {
            val (spec, label) = entries[i]
            val ex = x + at.first
            val ey = y + at.second * (ICON + GAP_Y)
            c.slot(HotmItems.stack(spec), ex, ey, ICON)
            c.text(label, ex + ICON + 4, ey, ey + ICON, S.TEXT_DIM, S.CAPTION)
        }
    }

    private companion object {
        const val ICON = 16
        const val GAP_X = 12
        const val GAP_Y = 4
    }
}

/** One `.fg` Forge slot: [name] null = empty; [right] the time left or "Ready" ([ready] green); [id] the output's SkyBlock id, for its icon. */
class ForgeRow(val slot: Int, val name: String?, val right: String?, val ready: Boolean, val id: String? = null)

/** `.fgs`: the Forge slots, one 20 px row each: slot number, the output's icon (when known) and name, time left. */
class ForgeRowsNode(private val rows: List<ForgeRow>) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = if (rows.isEmpty()) 0 else rows.size * ROW_H + (rows.size - 1) * GAP

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var ry = y
        for (r in rows) {
            val by = ry + (ROW_H - BOX) / 2
            c.rect(x, by, x + BOX, by + BOX, BOX_BG, Corner.SMALL)
            val n = r.slot.toString()
            c.text(n, x + (BOX - c.width(n, S.LABEL)) / 2, by, by + BOX, S.TEXT_DIM, S.LABEL)
            val icon = r.id?.let { ItemIcons.icon(it) }
            icon?.let { c.item(it, x + BOX + 6, ry + (ROW_H - ICON) / 2, ICON, count = false) }
            val tx = x + BOX + (if (icon != null) 6 + ICON + 4 else 8)
            if (r.name == null) {
                c.text("Empty", tx, ry, ry + ROW_H, S.TEXT_FAINT, S.SMALL)
            } else {
                val rw = r.right?.let { c.width(it, S.SMALL, true) } ?: 0
                r.right?.let { c.text(it, x + w - rw, ry, ry + ROW_H, if (r.ready) S.GOOD else S.TEXT, S.SMALL, true) }
                Rich.of(r.name).ellipsize(c, S.SMALL, x + w - rw - 8 - tx).draw(c, tx, ry, ry + ROW_H, S.SMALL, S.TEXT)
            }
            ry += ROW_H + GAP
        }
    }

    private companion object {
        const val ROW_H = 20
        const val GAP = 2
        const val BOX = 14
        const val ICON = 16
        val BOX_BG = 0xFF262626.toInt()
    }
}

/** One `.cry` Nucleus crystal: name, gem color, state word; [missing] dims it. */
class CrystalChip(val name: String, val color: Int, val state: String, val missing: Boolean)

/** `.crys`: the five Crystal Nucleus crystals as small pills that wrap. */
class CrystalsNode(private val chips: List<CrystalChip>) : Node() {
    private fun chipW(m: KitMeasure, ch: CrystalChip) = 6 + DOT + 5 + m.width(ch.name, S.SMALL) + 5 + m.width(ch.state, S.TINY) + 8

    private fun place(m: KitMeasure, w: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var cx = 0
        var row = 0
        for (ch in chips) {
            val cw = chipW(m, ch)
            if (cx > 0 && cx + cw > w) {
                cx = 0
                row++
            }
            out += cx to row
            cx += cw + GAP
        }
        return out
    }

    override fun measure(m: KitMeasure, w: Int): Int {
        val rows = (place(m, w).maxOfOrNull { it.second } ?: -1) + 1
        return if (rows == 0) 0 else rows * CHIP_H + (rows - 1) * GAP
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        for ((i, at) in place(c, w).withIndex()) {
            val ch = chips[i]
            val cx = x + at.first
            val cy = y + at.second * (CHIP_H + GAP)
            val cw = chipW(c, ch)
            c.rect(cx, cy, cx + cw, cy + CHIP_H, BG, Corner.SMALL)
            val dy = cy + (CHIP_H - DOT) / 2
            c.rect(cx + 6, dy, cx + 6 + DOT, dy + DOT, if (ch.missing) MISSING_DOT else ch.color, Corner.SQUARE)
            val nx = cx + 6 + DOT + 5
            c.text(ch.name, nx, cy, cy + CHIP_H, if (ch.missing) MISSING_TEXT else S.TEXT, S.SMALL)
            c.text(ch.state, nx + c.width(ch.name, S.SMALL) + 5, cy, cy + CHIP_H, if (ch.missing) S.TEXT_FAINT else S.TEXT_DIM, S.TINY)
        }
    }

    private companion object {
        const val CHIP_H = 20
        const val DOT = 7
        const val GAP = 5
        val BG = 0xFF252525.toInt()
        val MISSING_DOT = 0xFF444444.toInt()

        /** `.cry.not_found { opacity: .45 }` on #eee over #252525. */
        val MISSING_TEXT = 0xFF7D7D7D.toInt()
    }
}
