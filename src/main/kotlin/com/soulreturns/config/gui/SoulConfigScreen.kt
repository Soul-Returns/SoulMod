package com.soulreturns.config.gui

import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.config.gui.components.SoulSlider
import com.soulreturns.config.gui.components.SoulToggle
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.config.ConfigWrapper
import io.wispforest.owo.config.Option
import io.wispforest.owo.config.annotation.RangeConstraint
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import io.wispforest.owo.ui.core.OwoUIGraphics
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.Locale

class SoulConfigScreen : BaseOwoScreen<FlowLayout>(Text.translatable("text.config.soul/config.title")) {

    private val wrapper: ConfigWrapper<*> get() = SoulConfigHolder.INSTANCE

    private data class SubcategoryEntry(
        val catId: String,
        val subId: String,
        val displayName: Text,
        val options: List<Option<*>>
    )

    private data class CategoryEntry(
        val id: String,
        val displayName: Text,
        val subcategories: List<SubcategoryEntry>
    )

    /**
     * Explicit section layout for subcategories whose config fields are flat
     * (depth-2 paths, e.g. render.hideHeldItemTooltip) and can't be auto-grouped
     * by path segment. Structure: cat → sub → ordered list of (label, field names).
     * Options not listed in any section are collected into a trailing unlabelled card.
     */
    private val explicitSections: Map<String, Map<String, List<Pair<String, Set<String>>>>> = mapOf(
        "render" to mapOf(
            "misc" to listOf(
                "Tooltips"         to setOf("hideHeldItemTooltip", "showSkyblockIdInTooltip"),
                "Player Rendering" to setOf("oldSneakHeight")
            )
        )
    )

    private val categories: List<CategoryEntry> by lazy { collectCategories() }
    private var activeCategory: String = categories.firstOrNull()?.id ?: ""
    private var activeSubcategory: String =
        categories.firstOrNull()?.subcategories?.firstOrNull()?.subId ?: ""
    private lateinit var contentColumn: FlowLayout
    private lateinit var sidebarList: FlowLayout
    private val sidebarButtons = mutableMapOf<Pair<String, String>, ButtonComponent>()
    private val categoryHeaderButtons = mutableMapOf<String, ButtonComponent>()
    private val expandedCategories = mutableSetOf<String>()
    private val resetSlots = mutableMapOf<String, FlowLayout>()
    // Display text for each subcategory — populated on rebuild, never mutated, safe for renderers.
    private val subcategoryDisplayNames = mutableMapOf<Pair<String, String>, String>()

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { hSize, vSize -> UIContainers.verticalFlow(hSize, vSize) }

    override fun build(root: FlowLayout) {
        root.surface(Theme.backgroundSurface)
        root.padding(Insets.of(0))
        root.horizontalAlignment(HorizontalAlignment.CENTER)
        root.verticalAlignment(VerticalAlignment.CENTER)

        // Outer card holding sidebar + content
        val card = UIContainers.horizontalFlow(Sizing.fill(85), Sizing.fill(85))
        card.surface(Theme.panelSurface)
        card.padding(Insets.of(0))

        val sidebarColumn = UIContainers.verticalFlow(Sizing.fixed(180), Sizing.fill(100))
        sidebarColumn.surface(Theme.sidebarSurface)
        sidebarColumn.padding(Insets.of(12, 12, 8, 8))
        sidebarColumn.gap(2)
        sidebarColumn.horizontalAlignment(HorizontalAlignment.LEFT)

        val title = UIComponents.label(Text.literal(title.string))
            .color(Theme.color(Theme.ACCENT))
            .margins(Insets.of(4, 14, 4, 4))
        sidebarColumn.child(title)
        sidebarColumn.child(separator())

        sidebarList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        sidebarList.gap(2)
        sidebarList.horizontalAlignment(HorizontalAlignment.LEFT)

        val sidebarScrollLocal = UIContainers.verticalScroll(
            Sizing.fill(100), Sizing.expand(), sidebarList
        )
        sidebarScrollLocal.scrollbarThiccness(4)
        sidebarColumn.child(sidebarScrollLocal)

        // Default: expand the active category so the user immediately sees their subcategories.
        if (activeCategory.isNotEmpty()) expandedCategories.add(activeCategory)
        rebuildSidebarList()

        card.child(sidebarColumn)

        contentColumn = UIContainers.verticalFlow(Sizing.expand(), Sizing.fill(100))
        contentColumn.surface(Theme.contentSurface)
        contentColumn.padding(Insets.of(0))

        card.child(contentColumn)

        root.child(card)
        root.child(footer())

        rebuildContent()
    }

    private fun rebuildSidebarList() {
        sidebarList.clearChildren()
        sidebarButtons.clear()
        categoryHeaderButtons.clear()
        subcategoryDisplayNames.clear()
        for (cat in categories) {
            val header = buildCategoryHeader(cat)
            categoryHeaderButtons[cat.id] = header
            sidebarList.child(header)
            if (expandedCategories.contains(cat.id)) {
                for (sub in cat.subcategories) {
                    subcategoryDisplayNames[cat.id to sub.subId] = sub.displayName.string
                    val btn = sidebarButton(cat, sub)
                    sidebarButtons[cat.id to sub.subId] = btn
                    sidebarList.child(btn)
                }
            } else {
                // Pre-populate names even for collapsed entries so refreshSidebarSelection works.
                for (sub in cat.subcategories) {
                    subcategoryDisplayNames[cat.id to sub.subId] = sub.displayName.string
                }
            }
            sidebarList.child(spacer(2))
        }
    }

    /** Toggle expand/collapse without clearing the whole list (preserves scroll position). */
    private fun toggleCategory(cat: CategoryEntry) {
        val oldHeader = categoryHeaderButtons[cat.id] ?: return
        val headerIdx = sidebarList.children().indexOf(oldHeader)
        if (headerIdx < 0) return

        if (expandedCategories.contains(cat.id)) {
            // Collapse: remove subcategory buttons that follow the header.
            expandedCategories.remove(cat.id)
            for (sub in cat.subcategories) {
                sidebarButtons.remove(cat.id to sub.subId)?.let { sidebarList.removeChild(it) }
            }
        } else {
            // Expand: insert subcategory buttons right after the header.
            expandedCategories.add(cat.id)
            for ((i, sub) in cat.subcategories.withIndex()) {
                subcategoryDisplayNames[cat.id to sub.subId] = sub.displayName.string
                val btn = sidebarButton(cat, sub)
                sidebarButtons[cat.id to sub.subId] = btn
                sidebarList.child(headerIdx + 1 + i, btn)
            }
        }

        // Replace header in-place to update the chevron arrow.
        val newHeader = buildCategoryHeader(cat)
        categoryHeaderButtons[cat.id] = newHeader
        sidebarList.child(headerIdx, newHeader)
        sidebarList.removeChild(oldHeader)
    }

    private fun buildCategoryHeader(cat: CategoryEntry): ButtonComponent {
        val expanded = expandedCategories.contains(cat.id)
        val label = cat.displayName.string.uppercase()
        val btn = UIComponents.button(Text.empty()) {
            toggleCategory(cat)
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(18))
        btn.renderer(categoryHeaderRenderer(label, expanded))
        btn.margins(Insets.of(3, 1, 0, 0))
        return btn
    }

    private fun sidebarButton(cat: CategoryEntry, sub: SubcategoryEntry): ButtonComponent {
        val displayText = sub.displayName.string
        val btn = UIComponents.button(Text.empty()) {
            if (activeCategory != cat.id || activeSubcategory != sub.subId) {
                activeCategory = cat.id
                activeSubcategory = sub.subId
                refreshSidebarSelection()
                rebuildContent()
            }
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(sidebarItemRenderer(displayText, cat.id == activeCategory && sub.subId == activeSubcategory))
        btn.margins(Insets.of(1, 1, 0, 2))
        return btn
    }

    private fun refreshSidebarSelection() {
        for ((key, btn) in sidebarButtons) {
            val selected = key.first == activeCategory && key.second == activeSubcategory
            val displayText = subcategoryDisplayNames[key] ?: ""
            btn.renderer(sidebarItemRenderer(displayText, selected))
        }
    }

    private fun spacer(h: Int): FlowLayout {
        return UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(h))
    }

    private fun rebuildContent() {
        contentColumn.clearChildren()
        resetSlots.clear()

        val cat = categories.firstOrNull { it.id == activeCategory } ?: return
        val sub = cat.subcategories.firstOrNull { it.subId == activeSubcategory }
            ?: cat.subcategories.firstOrNull() ?: return

        val header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        header.padding(Insets.of(16, 12, 20, 20))
        header.verticalAlignment(VerticalAlignment.CENTER)
        header.gap(8)
        header.child(
            UIComponents.label(Text.literal(cat.displayName.string))
                .color(Theme.color(Theme.TEXT_DIM))
        )
        header.child(UIComponents.label(Text.literal("›")).color(Theme.color(Theme.TEXT_DIM)))
        header.child(
            UIComponents.label(Text.literal(sub.displayName.string))
                .color(Theme.color(Theme.TEXT))
        )
        contentColumn.child(header)
        contentColumn.child(horizontalDivider())

        val scrollBody = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        // Right padding leaves room for the scrollbar so rows don't visually clip.
        scrollBody.padding(Insets.of(12, 16, 16, 20))
        scrollBody.gap(8)
        scrollBody.horizontalAlignment(HorizontalAlignment.LEFT)

        val sections = explicitSections[activeCategory]?.get(activeSubcategory)
        if (sections != null) {
            // Explicit section layout: split flat options into labeled groups by field name.
            val byField = sub.options.associateBy { it.key().path().last() }
            val placed = mutableSetOf<String>()
            for ((label, fieldNames) in sections) {
                val sectionOpts = fieldNames.mapNotNull { byField[it] }.also {
                    placed.addAll(fieldNames)
                }
                if (sectionOpts.isEmpty()) continue
                addSection(scrollBody, label, sectionOpts)
            }
            // Any options not covered by an explicit section go into a trailing unlabelled card.
            val remaining = sub.options.filter { it.key().path().last() !in placed }
            if (remaining.isNotEmpty()) addSection(scrollBody, null, remaining)
        } else {
            // Auto-group by 3rd path segment (e.g. render.overlays.field → group "overlays").
            // Flat options (depth ≤ 2) fall into null → labelled with the subcategory name.
            val grouped = LinkedHashMap<String?, MutableList<Option<*>>>()
            for (opt in sub.options) {
                val path = opt.key().path()
                val group = if (path.size >= 4) path[2] else null
                grouped.getOrPut(group) { mutableListOf() }.add(opt)
            }
            for ((groupId, opts) in grouped) {
                val displayName: String? = if (groupId != null) {
                    val nameKey = "text.config.soul/config.group.${activeCategory}.${activeSubcategory}.$groupId"
                    val nameText = Text.translatable(nameKey)
                    if (nameText.string == nameKey) formatGroupId(groupId) else nameText.string
                } else {
                    null
                }
                addSection(scrollBody, displayName, opts)
            }
        }

        val scroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), scrollBody)
        scroll.scrollbarThiccness(4)
        contentColumn.child(scroll)
    }

    private fun addSection(parent: FlowLayout, label: String?, opts: List<Option<*>>) {
        if (label != null) {
            val lbl = UIComponents.label(Text.literal(label))
                .color(Theme.color(Theme.TEXT_DIM))
            lbl.margins(Insets.of(4, 0, 0, 6))
            parent.child(lbl)
        }
        val card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        card.surface(Theme.panelInsetSurface)
        card.padding(Insets.of(8))
        card.gap(2)
        for (opt in opts) card.child(buildRow(opt))
        parent.child(card)
    }

    private fun buildRow(opt: Option<*>): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.surface(Theme.rowSurface())
        row.padding(Insets.of(6, 6, 12, 12))
        row.gap(8)
        row.verticalAlignment(VerticalAlignment.CENTER)
        row.margins(Insets.of(1))

        val label = UIComponents.label(Text.translatable(opt.translationKey()))
            .color(Theme.color(Theme.TEXT))
        // Label expands to consume all leftover space, pushing the control to the row's end.
        label.horizontalSizing(Sizing.expand())

        val tooltipKey = opt.translationKey() + ".tooltip"
        val tooltipText = Text.translatable(tooltipKey)
        if (tooltipText.string != tooltipKey) {
            label.tooltip(tooltipText)
        }
        row.child(label)

        when (opt.value()) {
            is Boolean -> row.child(buildToggle(@Suppress("UNCHECKED_CAST") (opt as Option<Boolean>)))
            is Int, is Long, is Float, is Double -> row.child(buildNumeric(opt))
            is String -> row.child(buildTextBox(@Suppress("UNCHECKED_CAST") (opt as Option<String>)))
            else -> row.child(UIComponents.label(Text.literal(opt.value().toString())))
        }
        // Fixed-width slot; button is added/removed dynamically to avoid phantom hover.
        val slot = UIContainers.horizontalFlow(Sizing.fixed(18), Sizing.fixed(16))
        val optKey = opt.key().path().joinToString(".")
        resetSlots[optKey] = slot
        if (opt.value() != opt.defaultValue()) slot.child(resetIconButton(opt))
        row.child(slot)
        return row
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildToggle(opt: Option<Boolean>): SoulToggle {
        val toggle = SoulToggle(opt.value()) { newVal ->
            opt.set(newVal)
            save()
            refreshResetSlot(opt)
        }
        return toggle
    }

    private fun buildNumeric(opt: Option<*>): SoulSlider {
        val field = wrapper.fieldForKey(opt.key())
        val rc = field?.getAnnotation(RangeConstraint::class.java)
        val min: Double = rc?.min?.toDouble() ?: 0.0
        val max: Double = rc?.max?.toDouble() ?: 100.0
        val decimals: Int = rc?.decimalPlaces ?: 0

        val slider = SoulSlider(min, max, (opt.value() as Number).toDouble(), decimals)
        slider.onChanged { v ->
            @Suppress("UNCHECKED_CAST")
            when (opt.value()) {
                is Int    -> (opt as Option<Int>).set(v.toInt())
                is Long   -> (opt as Option<Long>).set(v.toLong())
                is Float  -> (opt as Option<Float>).set(v.toFloat())
                is Double -> (opt as Option<Double>).set(v)
                else      -> {}
            }
        }
        slider.onSlideEnd { save(); refreshResetSlot(opt) }
        return slider
    }

    private fun buildTextBox(opt: Option<String>): TextBoxComponent {
        val tb = UIComponents.textBox(Sizing.fixed(160), opt.value())
        tb.onChanged().subscribe(TextBoxComponent.OnChanged { newVal ->
            opt.set(newVal); save()
        })
        return tb
    }

    private fun save() {
        try { wrapper.save() } catch (_: Throwable) {}
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetIconButton(opt: Option<*>): ButtonComponent {
        val default = opt.defaultValue()
        val btn = UIComponents.button(Text.empty()) {
            (opt as Option<Any>).set(default as Any)
            save()
            rebuildContent()
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fill(100))
        btn.tooltip(Text.literal("Reset to default: ${formatValue(default)}"))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.PANEL_HOVER else Theme.PANEL_INSET
            ctx.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg)
            val tr = MinecraftClient.getInstance().textRenderer
            val icon = "↺"
            val tx = button.x + (button.width - tr.getWidth(icon)) / 2
            val ty = button.y + (button.height - tr.fontHeight) / 2
            ctx.drawText(tr, Text.literal(icon), tx, ty, if (button.isHovered) Theme.ACCENT else Theme.TEXT_DIM, false)
        })
        return btn
    }

    private fun refreshResetSlot(opt: Option<*>) {
        val slot = resetSlots[opt.key().path().joinToString(".")] ?: return
        slot.clearChildren()
        if (opt.value() != opt.defaultValue()) slot.child(resetIconButton(opt))
    }

    private fun formatGroupId(id: String): String =
        // "playerRendering" → "Player Rendering", "tooltips" → "Tooltips"
        id.replace(Regex("([A-Z])"), " $1")
            .replaceFirstChar { it.uppercase() }
            .trim()

    private fun formatValue(value: Any?): String = when (value) {
        is Float  -> String.format(Locale.ROOT, "%.2f", value)
        is Double -> String.format(Locale.ROOT, "%.2f", value)
        else      -> value?.toString() ?: "null"
    }

    // ---- helpers ----

    private fun separator(): FlowLayout {
        val s = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(1))
        s.surface(Surface.flat(Theme.SEPARATOR))
        s.margins(Insets.vertical(6))
        return s
    }

    private fun horizontalDivider(): FlowLayout {
        val d = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(1))
        d.surface(Surface.flat(Theme.SEPARATOR))
        return d
    }

    private fun verticalDivider(): FlowLayout {
        val d = UIContainers.verticalFlow(Sizing.fixed(1), Sizing.fill(100))
        d.surface(Surface.flat(Theme.SEPARATOR))
        return d
    }

    private fun footer(): FlowLayout {
        val f = UIContainers.horizontalFlow(Sizing.fill(85), Sizing.fixed(36))
        f.padding(Insets.of(6))
        f.gap(6)
        f.verticalAlignment(VerticalAlignment.CENTER)
        f.horizontalAlignment(HorizontalAlignment.RIGHT)

        val reload = UIComponents.button(Text.literal("Reload")) {
            wrapper.load()
            rebuildContent()
        }
        reload.horizontalSizing(Sizing.fixed(80))
        reload.verticalSizing(Sizing.fixed(24))
        reload.renderer(footerButtonRenderer(accent = false))

        val done = UIComponents.button(Text.literal("Done")) { close() }
        done.horizontalSizing(Sizing.fixed(80))
        done.verticalSizing(Sizing.fixed(24))
        done.renderer(footerButtonRenderer(accent = true))

        f.child(reload)
        f.child(done)
        return f
    }

    private fun categoryHeaderRenderer(text: String, expanded: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val tr = MinecraftClient.getInstance().textRenderer
            if (button.isHovered) {
                DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS
                )
            }
            val arrow = if (expanded) "▾" else "▸"
            val label = "$arrow  $text"
            val ty = button.y + (button.height - tr.fontHeight) / 2
            ctx.drawText(tr, Text.literal(label), button.x + 6, ty, Theme.TEXT_FAINT, false)
        }
    }

    private fun sidebarItemRenderer(text: String, selected: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val tr = MinecraftClient.getInstance().textRenderer
            when {
                selected -> DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.ACCENT, Theme.ITEM_RADIUS
                )
                button.isHovered -> DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS
                )
            }
            val textColor = if (selected) Theme.TEXT else Theme.TEXT_DIM
            val ty = button.y + (button.height - tr.fontHeight) / 2
            ctx.drawText(tr, Text.literal(text), button.x + 10, ty, textColor, false)
        }
    }

    private fun footerButtonRenderer(accent: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val bg = when {
                accent && button.isHovered -> Theme.ACCENT_DIM
                accent                     -> Theme.ACCENT
                button.isHovered           -> Theme.PANEL_HOVER
                else                       -> Theme.PANEL_INSET
            }
            DrawContextRenderer.roundedFill(
                ctx,
                button.x, button.y, button.x + button.width, button.y + button.height,
                bg, Theme.ITEM_RADIUS
            )
        }
    }

    // ---- model collection ----

    private fun collectCategories(): List<CategoryEntry> {
        // path[0] = category, path[1..n-2] = nested groups, path[n-1] = leaf field.
        val byCat = LinkedHashMap<String, LinkedHashMap<String, MutableList<Option<*>>>>()
        wrapper.forEachOption { opt ->
            val path = opt.key().path()
            if (path.isEmpty()) return@forEachOption
            val cat = path[0]
            val sub = if (path.size >= 3) path[1] else "misc"
            val groups = byCat.getOrPut(cat) { LinkedHashMap() }
            groups.getOrPut(sub) { mutableListOf() }.add(opt)
        }
        return byCat.entries.map { (catId, groups) ->
            val subs = groups.entries.map { (subId, options) ->
                val nameKey = "text.config.soul/config.group.$catId.$subId"
                SubcategoryEntry(
                    catId = catId,
                    subId = subId,
                    displayName = Text.translatable(nameKey),
                    options = options
                )
            }
            CategoryEntry(
                id = catId,
                displayName = Text.translatable("text.config.soul/config.category.$catId"),
                subcategories = subs
            )
        }
    }
}
