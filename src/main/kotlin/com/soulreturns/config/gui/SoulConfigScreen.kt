package com.soulreturns.config.gui

import com.soulreturns.config.SoulConfigHolder
import io.wispforest.owo.config.ConfigWrapper
import io.wispforest.owo.config.Option
import io.wispforest.owo.config.annotation.RangeConstraint
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.CheckboxComponent
import io.wispforest.owo.ui.component.DiscreteSliderComponent
import io.wispforest.owo.ui.component.SliderComponent
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

class SoulConfigScreen : BaseOwoScreen<FlowLayout>(Text.translatable("text.config.soul.title")) {

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

        val title = UIComponents.label(Text.literal("§l${title.string}"))
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
        card.child(verticalDivider())

        contentColumn = UIContainers.verticalFlow(Sizing.expand(), Sizing.fill(100))
        contentColumn.surface(Theme.panelSurface)
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
        for (cat in categories) {
            val header = buildCategoryHeader(cat)
            categoryHeaderButtons[cat.id] = header
            sidebarList.child(header)
            if (expandedCategories.contains(cat.id)) {
                for (sub in cat.subcategories) {
                    val btn = sidebarButton(cat, sub)
                    sidebarButtons[cat.id to sub.subId] = btn
                    sidebarList.child(btn)
                }
            }
            sidebarList.child(spacer(4))
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
        val arrow = if (expanded) "▾" else "▸"
        val text = Text.literal("$arrow  §l${cat.displayName.string.uppercase()}")
        val btn = UIComponents.button(text) {
            toggleCategory(cat)
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(22))
        btn.renderer(categoryHeaderRenderer())
        btn.margins(Insets.of(2, 2, 0, 0))
        return btn
    }

    private fun sidebarButton(cat: CategoryEntry, sub: SubcategoryEntry): ButtonComponent {
        val btn = UIComponents.button(sub.displayName) {
            if (activeCategory != cat.id || activeSubcategory != sub.subId) {
                activeCategory = cat.id
                activeSubcategory = sub.subId
                refreshSidebarSelection()
                rebuildContent()
            }
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(22))
        btn.renderer(flatButtonRenderer(
            selected = cat.id == activeCategory && sub.subId == activeSubcategory
        ))
        btn.margins(Insets.of(0, 0, 14, 0))
        return btn
    }

    private fun refreshSidebarSelection() {
        for ((key, btn) in sidebarButtons) {
            val selected = key.first == activeCategory && key.second == activeSubcategory
            btn.renderer(flatButtonRenderer(selected = selected))
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
            UIComponents.label(Text.literal("§l${cat.displayName.string}"))
                .color(Theme.color(Theme.TEXT_DIM))
        )
        header.child(UIComponents.label(Text.literal("›")).color(Theme.color(Theme.TEXT_DIM)))
        header.child(
            UIComponents.label(Text.literal("§l${sub.displayName.string}"))
                .color(Theme.color(Theme.ACCENT))
        )
        contentColumn.child(header)
        contentColumn.child(horizontalDivider())

        val scrollBody = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        // Right padding leaves room for the scrollbar so rows don't visually clip.
        scrollBody.padding(Insets.of(12, 16, 16, 20))
        scrollBody.gap(4)
        scrollBody.horizontalAlignment(HorizontalAlignment.LEFT)

        val groupCard = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        groupCard.surface(Theme.panelInsetSurface)
        groupCard.padding(Insets.of(4))
        groupCard.gap(2)
        for (opt in sub.options) {
            groupCard.child(buildRow(opt))
        }
        scrollBody.child(groupCard)

        val scroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), scrollBody)
        scroll.scrollbarThiccness(4)
        contentColumn.child(scroll)
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
            is Boolean -> row.child(buildToggle(opt as Option<Boolean>))
            is Int, is Long, is Float, is Double -> row.child(buildNumeric(opt))
            is String -> row.child(buildTextBox(opt as Option<String>))
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
    private fun buildToggle(opt: Option<Boolean>): CheckboxComponent {
        val cb = UIComponents.checkbox(Text.empty())
        cb.checked(opt.value())
        cb.onChanged { newVal -> opt.set(newVal); save(); refreshResetSlot(opt) }
        return cb
    }

    private fun buildNumeric(opt: Option<*>): DiscreteSliderComponent {
        val field = wrapper.fieldForKey(opt.key())
        val rc = field?.getAnnotation(RangeConstraint::class.java)
        val min: Double = rc?.min?.toDouble() ?: 0.0
        val max: Double = rc?.max?.toDouble() ?: 100.0
        val decimals: Int = rc?.decimalPlaces ?: 0

        val slider = UIComponents.discreteSlider(Sizing.fixed(140), min, max)
        slider.decimalPlaces(decimals)
        slider.snap(decimals == 0)
        val current = (opt.value() as Number).toDouble()
        slider.setFromDiscreteValue(current)

        slider.onChanged().subscribe(SliderComponent.OnChanged { _ ->
            val v = slider.discreteValue()
            @Suppress("UNCHECKED_CAST")
            when (opt.value()) {
                is Int    -> (opt as Option<Int>).set(v.toInt())
                is Long   -> (opt as Option<Long>).set(v.toLong())
                is Float  -> (opt as Option<Float>).set(v.toFloat())
                is Double -> (opt as Option<Double>).set(v)
                else      -> {}
            }
        })
        slider.slideEnd().subscribe(SliderComponent.OnSlideEnd { save(); refreshResetSlot(opt) })
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
            ctx.drawText(tr, icon, tx, ty, if (button.isHovered) Theme.ACCENT else Theme.TEXT_DIM, false)
        })
        return btn
    }

    private fun refreshResetSlot(opt: Option<*>) {
        val slot = resetSlots[opt.key().path().joinToString(".")] ?: return
        slot.clearChildren()
        if (opt.value() != opt.defaultValue()) slot.child(resetIconButton(opt))
    }

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
        reload.verticalSizing(Sizing.fixed(22))
        reload.renderer(flatButtonRenderer(selected = false))

        val done = UIComponents.button(Text.literal("Done")) { close() }
        done.horizontalSizing(Sizing.fixed(80))
        done.verticalSizing(Sizing.fixed(22))
        done.renderer(flatButtonRenderer(selected = true))

        f.child(reload)
        f.child(done)
        return f
    }

    private fun categoryHeaderRenderer(): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.PANEL_HOVER else 0
            if (bg != 0) ctx.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg)
        }
    }

    private fun flatButtonRenderer(selected: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val hovered = button.isHovered
            val bg = when {
                selected -> Theme.PANEL_HOVER
                hovered  -> Theme.PANEL_HOVER
                else     -> Theme.PANEL
            }
            ctx.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg)
            if (selected) {
                // Left accent bar
                ctx.fill(button.x, button.y, button.x + 2, button.y + button.height, Theme.ACCENT)
            }
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
                val nameKey = "text.config.soul.group.$catId.$subId"
                SubcategoryEntry(
                    catId = catId,
                    subId = subId,
                    displayName = Text.translatable(nameKey),
                    options = options
                )
            }
            CategoryEntry(
                id = catId,
                displayName = Text.translatable("text.config.soul.category.$catId"),
                subcategories = subs
            )
        }
    }
}
