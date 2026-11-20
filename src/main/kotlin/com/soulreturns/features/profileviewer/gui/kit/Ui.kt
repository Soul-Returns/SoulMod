package com.soulreturns.features.profileviewer.gui.kit

import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import net.minecraft.world.item.ItemStack

/**
 * The builder a section writes its body with. Every call appends one [Node]; the block forms
 * (`card { }`, `grid(300) { }`, `tiles { }`) collect their children from a nested builder.
 *
 * Interactive state is per section: [value]/[seg] and [query]/[search] keys are namespaced by
 * [sectionId], so `"mode"` in Dungeons never meets `"mode"` in Mining. Click lambdas run on the
 * client thread on a click and may change state, open a section or start a lookup; building never
 * has side effects.
 */
class Ui(val sectionId: String, val state: ViewState) {
    val nodes = ArrayList<Node>()

    /** Appends a node built elsewhere (a feature-owned card, a [CustomNode]). */
    fun add(node: Node) {
        nodes += node
    }

    /** Builds [block] in a nested builder and returns its nodes (for a custom container). */
    fun collect(block: Ui.() -> Unit): List<Node> = Ui(sectionId, state).apply(block).nodes

    private fun key(k: String) = "$sectionId.$k"

    // ------------------------------------------------------------------ state

    /** The current value of a segmented control or other per-section setting. */
    fun value(key: String, default: String): String = state.get(key(key)) ?: default

    fun setValue(key: String, value: String) = state.set(key(key), value)

    fun flag(key: String): Boolean = state.flag(key(key))

    fun toggleFlag(key: String) = state.toggle(key(key))

    /** What the player typed into search field [key], trimmed and lower-cased ("" when empty). */
    fun query(key: String): String = state.text(key(key)).trim().lowercase()

    // ------------------------------------------------------------------ layout

    /** `.tiles` row of headline numbers. Only [tile] calls inside. */
    fun tiles(block: Ui.() -> Unit) {
        nodes += TilesNode(collect(block).filterIsInstance<TileNode>())
    }

    fun tile(
        label: String,
        value: String,
        sub: String? = null,
        bar: Float? = null,
        maxed: Boolean = false,
        color: Int = S.TEXT,
        locked: Boolean = false,
        tip: String? = null,
        onClick: (() -> Unit)? = null,
    ) {
        nodes += TileNode(label, Rich.of(value), sub?.let(Rich::of), bar, maxed, color, locked, tip, onClick)
    }

    /** A tile whose value mixes colors that have no `§` code (grade colors, level colors). */
    fun tile(label: String, value: Rich, sub: String? = null, bar: Float? = null, maxed: Boolean = false, locked: Boolean = false, tip: String? = null, onClick: (() -> Unit)? = null) {
        nodes += TileNode(label, value, sub?.let(Rich::of), bar, maxed, S.TEXT, locked, tip, onClick)
    }

    /** `.grid` with columns at least [min] wide; children may set `span` (cards take `span = `). */
    fun grid(min: Int, span: Int = 1, block: Ui.() -> Unit) {
        nodes += GridNode(min, collect(block), span = span)
    }

    /** Shortest-column masonry (`.mason`), at most three columns of at least [min]. */
    fun masonry(min: Int, block: Ui.() -> Unit) {
        nodes += GridNode(min, collect(block), masonry = true)
    }

    /** `.cols3`: each [column] call is one column of stacked nodes; columns wrap below 250 px. */
    fun columns(block: ColumnsBuilder.() -> Unit) {
        val b = ColumnsBuilder(this).apply(block)
        nodes += ColumnsNode(b.columns)
    }

    class ColumnsBuilder(private val ui: Ui) {
        val columns = ArrayList<List<Node>>()
        fun column(block: Ui.() -> Unit) {
            columns += ui.collect(block)
        }
    }

    /** Children left to right, centered; inline children (chips, links, segments) keep their width. */
    fun row(gap: Int = 8, block: Ui.() -> Unit) {
        nodes += RowNode(collect(block), gap)
    }

    /** Children top to bottom with [gap]. */
    fun stack(gap: Int = 6, span: Int = 1, block: Ui.() -> Unit) {
        nodes += StackNode(collect(block), gap, span)
    }

    /** A fixed-width column beside the rest (`.ov-top`'s figure); see [SplitNode.maxStretch]. */
    fun split(leftW: Int, left: Ui.() -> Unit, right: Ui.() -> Unit, minRightW: Int = 0, maxStretch: Float = Float.POSITIVE_INFINITY) {
        nodes += SplitNode(leftW, StackNode(collect(left)), StackNode(collect(right)), minRightW = minRightW, maxStretch = maxStretch)
    }

    fun space(h: Int) {
        nodes += SpaceNode(h)
    }

    /** A flexible gap inside [row]. */
    fun grow() {
        nodes += SpaceNode(0, grow = true)
    }

    // ------------------------------------------------------------------ cards

    /**
     * A card. [kind] is one of [CardNode.PLAIN] / `SHARED` / `MINE` / `LOCAL` / `EMPTY` / `GENERIC` /
     * `LATER`; the Soul kinds add their chips. [span]: grid columns, or [Node.FULL]. [tools]: inline
     * controls at the header's right (a [seg], a [search]). [meta]: a link or a dim note before them.
     * [padX] / [padTop] / [padBottom] override `.c`'s padding for cards the mock pads differently (`.hero`).
     */
    fun card(
        title: String?,
        kind: Int = CardNode.PLAIN,
        sub: String? = null,
        chips: List<Chip> = emptyList(),
        span: Int = 1,
        tip: String? = null,
        meta: (Ui.() -> Unit)? = null,
        tools: (Ui.() -> Unit)? = null,
        gap: Int = 6,
        padX: Int = S.CARD_PAD_X,
        padTop: Int = S.CARD_PAD_TOP,
        padBottom: Int = S.CARD_PAD_BOTTOM,
        body: Ui.() -> Unit,
    ) {
        nodes += CardNode(
            title = title,
            body = collect(body),
            kind = kind,
            chips = chips,
            sub = sub?.let(Rich::of),
            meta = meta?.let { collect(it).firstOrNull() },
            tools = tools?.let { collect(it) } ?: emptyList(),
            titleTip = tip,
            bodyGap = gap,
            span = span,
            padX = padX,
            padTop = padTop,
            padBottom = padBottom,
        )
    }

    /** A grey "not in this build yet" card with a reason (`.c.later`). */
    fun later(title: String, text: String, span: Int = 1) {
        nodes += CardNode(title, listOf(TextNode(Rich.of(text), S.SMALL, S.TEXT_DIM, lineH = LH_SMALL)), CardNode.LATER, listOf(Chip.plain("COMING LATER")), span = span)
    }

    // ------------------------------------------------------------------ content

    fun text(text: String, size: Float = S.BODY, color: Int = S.TEXT, bold: Boolean = false, wrap: Boolean = true, align: Int = -1, tip: String? = null) {
        val lh = if (size >= S.TITLE) 20 else if (size >= S.HEADING) 16 else if (size >= S.BODY) LH_BODY else if (size >= S.SMALL) LH_SMALL else LH_CAPTION
        nodes += TextNode(Rich.of(text), size, color, bold, wrap, lh, align, tip?.let { listOf(it) })
    }

    /** `.foot`: a faint footnote. */
    fun foot(text: String) {
        nodes += TextNode(Rich.of(text), S.TINY, S.TEXT_FAINT, lineH = LH_CAPTION)
    }

    /** An uppercase group label (`.cosh`). */
    fun label(text: String) {
        nodes += LabelNode(text)
    }

    fun link(label: String, onClick: () -> Unit) {
        nodes += LinkNode(label, onClick = onClick)
    }

    fun chips(vararg chips: Chip?) {
        nodes += ChipsNode(chips.filterNotNull())
    }

    fun icon(name: String, size: Int = 14, color: Int = S.TEXT_DIM) {
        nodes += IconNode(name, size, color)
    }

    fun face(uuidUndashed: String, size: Int) {
        nodes += FaceNode(uuidUndashed, size)
    }

    /** `.kvs`; [cols] 2 for `.kvs.cols`. */
    fun kvs(cols: Int = 1, block: KvsBuilder.() -> Unit) {
        nodes += KvsNode(KvsBuilder().apply(block).rows, cols)
    }

    class KvsBuilder {
        val rows = ArrayList<Kv>()

        /** One row; [value] may carry `§` colors (`"12 §8(5.0%)"`). */
        fun kv(label: String, value: String, color: Int = S.TEXT, tip: String? = null) {
            rows += Kv(Rich.of(label), Rich.of(value), color, tip)
        }

        fun kv(label: String, value: Rich, tip: String? = null) {
            rows += Kv(Rich.of(label), value, S.TEXT, tip)
        }
    }

    fun bar(fraction: Float, maxed: Boolean = false) {
        nodes += BarNode(fraction, maxed)
    }

    /** `.cl`: a labelled level with a bar. */
    fun levelRow(label: String, value: String, fraction: Float, maxed: Boolean, selected: Boolean = false, chip: Chip? = null, valueColor: Int? = null, tip: String? = null) {
        nodes += LevelRowNode(Rich.of(label), value, fraction, maxed, selected, chip, valueColor, tip)
    }

    /** `.sl-top`: a big number with its bar and caption. */
    fun bigStat(value: String, fraction: Float, maxed: Boolean, caption: String, color: Int? = null) {
        nodes += BigStatNode(value, fraction, maxed, Rich.of(caption), color)
    }

    /** `.tiers`: equal labelled boxes. */
    fun boxes(boxes: List<Box>) {
        nodes += BoxesNode(boxes)
    }

    /** `.mini2`: two columns of small labelled bars. */
    fun minis(items: List<Mini>, cols: Int = 2) {
        nodes += MiniGridNode(items, cols)
    }

    /** `.tbl`; [padRight] is the cell padding (`.tbl td` 6, 0 for header-less grid rows like `.sk`). */
    fun table(cols: List<Col>, rows: List<TableRow>, rowH: Int = S.TD_H, gap: Int = 0, separators: Boolean = true, padRight: Int = S.TD_PAD_R) {
        nodes += TableNode(cols, rows, rowH = rowH, gap = gap, padRight = padRight, separators = separators)
    }

    /** `.bars`; [twoColumns] for `.bars.two`. */
    fun bars(rows: List<BarRow>, twoColumns: Boolean = false) {
        nodes += BarListNode(rows, twoColumns)
    }

    fun histogram(values: List<Double>, axisLeft: String? = null, axisRight: String? = null, tip: String? = null) {
        nodes += HistogramNode(values, axisLeft, axisRight, tip)
    }

    fun skeleton(lines: Int = 3) {
        nodes += SkeletonNode(lines)
    }

    // ------------------------------------------------------------------ items

    fun slot(stack: ItemStack?, size: Int = S.SLOT, mark: Int = Mark.NONE, count: Boolean = true) {
        nodes += SlotNode(stack, size, mark, count)
    }

    /** A container grid; [cols] 0 = as many as fit. */
    fun slots(stacks: List<ItemStack?>, cols: Int = 9, size: Int = S.SLOT, marks: IntArray? = null) {
        nodes += SlotGridNode(stacks, cols, size, marks)
    }

    fun itemLine(
        stack: ItemStack?,
        title: String,
        sub: String? = null,
        right: String? = null,
        chips: List<Chip> = emptyList(),
        fraction: Float? = null,
        maxed: Boolean = false,
        framed: Boolean = false,
        active: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        nodes += ItemLineNode(stack, Rich.of(title), sub?.let(Rich::of), right?.let(Rich::of), chips = chips, fraction = fraction, maxed = maxed, framed = framed, active = active, onClick = onClick)
    }

    // ------------------------------------------------------------------ controls

    /** A segmented control over [options] (value to label); returns nothing, read [value]. */
    fun seg(key: String, default: String, options: List<Pair<String, String>>) {
        nodes += SegNode(key(key), value(key, default), options)
    }

    /** A search field; read it with [query]. [count] shows the hit count at its right. */
    fun search(key: String, placeholder: String, width: Int = S.FIELD_W, count: String? = null) {
        nodes += SearchFieldNode(key(key), placeholder, width, count)
    }

    fun toggle(label: String, on: Boolean, big: Boolean = false, onClick: () -> Unit) {
        nodes += ToggleNode(label, on, big, onClick)
    }

    // ------------------------------------------------------------------ notices

    /** `.empty`: icon, title and one line. */
    fun empty(icon: String, title: String, text: String = "", maxW: Int = Int.MAX_VALUE) {
        nodes += EmptyNode(icon, title, text, maxW)
    }

    /**
     * The API-toggle-off notice. [toggle] is the in-game name ("Inventory API"), [what] finishes
     * "to show …". Words it for yourself vs another player.
     */
    fun apiOff(playerName: String, isSelf: Boolean, toggle: String, what: String) {
        val title = "${if (isSelf) "Your" else "$playerName's"} $toggle is off"
        val text = "${if (isSelf) "Turn it on" else "They can turn it on"} in SkyBlock Menu › Settings › API Settings to show $what."
        nodes += ApiOffNode(title, text)
    }

    /** `.soulnote`: a Soul-tinted strip, SOUL chip first unless [chip] is null. */
    fun note(text: String, chip: Chip? = Chip.soul()) {
        nodes += NoteNode(Rich.of(text), chip)
    }
}
