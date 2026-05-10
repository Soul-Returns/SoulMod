package com.soulreturns.config.gui

import com.soulreturns.Soul
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.components.SoulSlider
import com.soulreturns.config.gui.components.SoulToggle
import com.soulreturns.gui.GuiEditScreen
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
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import java.net.URI
import java.util.Locale

class SoulConfigScreen(initialSearch: String = "") : BaseOwoScreen<FlowLayout>(Component.translatable("text.config.soul/config.title")) {

    companion object {
        private val DISCORD_ICON: Identifier = Identifier.fromNamespaceAndPath("soul", "textures/gui/discord.png")
        private const val DISCORD_TEX_W = 528
        private const val DISCORD_TEX_H = 400

        private val GITHUB_ICON: Identifier = Identifier.fromNamespaceAndPath("soul", "textures/gui/github.png")
        private const val GITHUB_TEX_W = 294
        private const val GITHUB_TEX_H = 288
    }

    private val wrapper: ConfigWrapper<*> get() = SoulConfigHolder.INSTANCE

    private data class SubcategoryEntry(
        val catId: String,
        val subId: String,
        val displayName: Component,
        val options: List<Option<*>>
    )

    private data class CategoryEntry(
        val id: String,
        val displayName: Component,
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
            ),
            "highlights" to listOf(
                "Item Highlights" to setOf(
                    "highlightPestEquipment",
                    "usePestVest",
                    "highlightFarmingEquipment",
                    "highlightCustomItems"
                )
            )
        )
    )

    private data class LinkTarget(val label: String, val targetCat: String, val targetSub: String)

    /** Generic action row: a label paired with a button that runs an arbitrary callback. */
    private data class ActionRow(val label: String, val buttonText: String, val action: () -> Unit)

    /** Subcategories that include cross-navigation buttons to other config locations. */
    private val linkSections: Map<String, Map<String, List<LinkTarget>>> = mapOf(
        "farming" to mapOf(
            "pestFarming" to listOf(
                LinkTarget("Configure Pest Equipment Highlighting", "render", "highlights")
            )
        )
    )

    /** Subcategories that include action button rows. (cat, sub) → list of ActionRow. */
    private val actionRows: Map<String, Map<String, List<ActionRow>>> = mapOf(
        "dev" to mapOf(
            "config" to listOf(
                ActionRow(
                    label = "Reload Config from Disk",
                    buttonText = "Reload",
                    action = { wrapper.load(); rebuildContent() }
                )
            )
        )
    )

    /** Subcategories without backing config fields. Sidebar entries appear via translation keys. */
    private val virtualSubs: Map<String, List<String>> = mapOf(
        "farming" to listOf("pestFarming"),
        "dev" to listOf("config")
    )

    /** Explicit category sort order. Categories not listed here fall to the end in their original order. */
    private val categoryOrder: List<String> = listOf(
        "general", "render", "fishing", "mining", "farming", "profileViewer", "dev"
    )

    /** Option full-paths whose String value is a Minecraft key translation key (e.g. "key.keyboard.f6"). */
    private val keybindOptions: Set<String> = setOf(
        "dev.keybinds.copyOpenedGui",
        "dev.keybinds.copyItemUnderCursor",
        "dev.keybinds.copyHeldItem",
        "dev.keybinds.copyScoreboard",
        "dev.keybinds.copyTablist"
    )

    /** When non-null, the next key/mouse press binds this option instead of acting on the screen. */
    private var capturingKeybind: Option<String>? = null

    /** Option full-path → predicate. Option is hidden when predicate returns false. */
    private val optionVisibility: Map<String, () -> Boolean> = mapOf(
        "render.highlights.usePestVest" to { cfg.render.highlights.highlightPestEquipment() },
        "farming.seasonings.showMaxMilestone"  to { cfg.farming.seasonings.enableTracker() },
        "farming.seasonings.showNextMilestone" to { cfg.farming.seasonings.enableTracker() },
        "farming.seasonings.showFarmingTime"   to { cfg.farming.seasonings.enableTracker() },
        "farming.seasonings.showPerHour"       to { cfg.farming.seasonings.enableTracker() }
    )

    /** Boolean option full-paths whose change should rebuild content (because they gate other options' visibility). */
    private val rebuildOnChange: Set<String> = setOf(
        "render.highlights.highlightPestEquipment",
        "farming.seasonings.enableTracker"
    )

    private val categories: List<CategoryEntry> by lazy { collectCategories() }
    private var activeCategory: String = ""
    private var activeSubcategory: String = ""
    private var searchQuery: String = initialSearch
    private lateinit var contentColumn: FlowLayout
    private lateinit var contentBody: FlowLayout
    private lateinit var breadcrumbBar: FlowLayout
    private lateinit var searchBox: TextBoxComponent
    private lateinit var sidebarList: FlowLayout
    private val sidebarButtons = mutableMapOf<Pair<String, String>, ButtonComponent>()
    private val categoryHeaderButtons = mutableMapOf<String, ButtonComponent>()
    private val expandedCategories = mutableSetOf<String>()
    private val resetSlots = mutableMapOf<String, FlowLayout>()
    // Display text for each subcategory — populated on rebuild, never mutated, safe for renderers.
    private val subcategoryDisplayNames = mutableMapOf<Pair<String, String>, String>()

    init {
        activeCategory = categories.firstOrNull()?.id ?: ""
        activeSubcategory = categories.firstOrNull()?.subcategories?.firstOrNull()?.subId ?: ""
    }

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

        val titleRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        titleRow.verticalAlignment(VerticalAlignment.CENTER)
        titleRow.margins(Insets.of(2, 2, 4, 4))
        titleRow.child(
            UIComponents.label(Component.literal("Soul").withStyle { it.withColor(Theme.ACCENT) })
        )
        val versionContainer = UIContainers.horizontalFlow(Sizing.expand(), Sizing.content())
        versionContainer.horizontalAlignment(HorizontalAlignment.RIGHT)
        versionContainer.verticalAlignment(VerticalAlignment.CENTER)
        versionContainer.gap(4)
        versionContainer.child(githubButton())
        versionContainer.child(discordButton())
        versionContainer.child(
            UIComponents.label(Component.literal("v${Soul.version.substringBefore("+")}").withStyle { it.withColor(Theme.TEXT_DIM) })
        )
        titleRow.child(versionContainer)
        sidebarColumn.child(titleRow)
        sidebarColumn.child(separator())

        sidebarList = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        sidebarList.gap(2)
        sidebarList.horizontalAlignment(HorizontalAlignment.LEFT)

        val sidebarScrollLocal = UIContainers.verticalScroll(
            Sizing.fill(100), Sizing.expand(), sidebarList
        )
        sidebarScrollLocal.scrollbarThiccness(4)
        sidebarColumn.child(sidebarScrollLocal)
        sidebarColumn.child(separator())
        sidebarColumn.child(sidebarFooter())

        card.child(sidebarColumn)

        contentColumn = UIContainers.verticalFlow(Sizing.expand(), Sizing.fill(100))
        contentColumn.surface(Theme.contentSurface)
        contentColumn.padding(Insets.of(0))

        // Persistent header: breadcrumb (left, rebuilt on sub change) + search box (right, never recreated).
        val headerRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        headerRow.padding(Insets.of(16, 12, 20, 16))
        headerRow.verticalAlignment(VerticalAlignment.CENTER)
        headerRow.gap(8)

        breadcrumbBar = UIContainers.horizontalFlow(Sizing.expand(), Sizing.content())
        breadcrumbBar.verticalAlignment(VerticalAlignment.CENTER)
        breadcrumbBar.gap(8)
        headerRow.child(breadcrumbBar)

        val searchLabel = UIComponents.label(Component.translatable("text.config.soul/config.search"))
            .color(Theme.color(Theme.TEXT_DIM))
        headerRow.child(searchLabel)

        searchBox = UIComponents.textBox(Sizing.fixed(160), searchQuery)
        searchBox.onChanged().subscribe(TextBoxComponent.OnChanged { newVal ->
            if (newVal != searchQuery) {
                searchQuery = newVal
                applySearch()
            }
        })
        headerRow.child(searchBox)

        contentColumn.child(headerRow)
        contentColumn.child(horizontalDivider())

        contentBody = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fill(100))
        contentColumn.child(contentBody)

        card.child(contentColumn)

        root.child(card)
        root.child(footer())

        adjustActiveToFilter()
        rebuildSidebarList()
        rebuildContent()
    }

    private fun rebuildSidebarList() {
        sidebarList.clearChildren()
        sidebarButtons.clear()
        categoryHeaderButtons.clear()
        subcategoryDisplayNames.clear()

        val visible = filteredCategories()
        if (visible.isEmpty()) {
            val empty = UIComponents.label(
                Component.literal("No results").withStyle { it.withColor(Theme.TEXT_DIM) }
            )
            empty.margins(Insets.of(6, 0, 12, 0))
            sidebarList.child(empty)
            return
        }

        for (cat in visible) {
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
        val btn = UIComponents.button(Component.empty()) {
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
        val btn = UIComponents.button(Component.empty()) {
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
        rebuildBreadcrumb()
        rebuildContentBody()
    }

    private fun rebuildBreadcrumb() {
        breadcrumbBar.clearChildren()
        val visible = filteredCategories()
        val cat = visible.firstOrNull { it.id == activeCategory } ?: return
        val sub = cat.subcategories.firstOrNull { it.subId == activeSubcategory }
            ?: cat.subcategories.firstOrNull() ?: return
        breadcrumbBar.child(
            UIComponents.label(Component.literal(cat.displayName.string))
                .color(Theme.color(Theme.TEXT_DIM))
        )
        breadcrumbBar.child(
            UIComponents.label(Component.literal("›")).color(Theme.color(Theme.TEXT_DIM))
        )
        breadcrumbBar.child(
            UIComponents.label(Component.literal(sub.displayName.string))
                .color(Theme.color(Theme.TEXT))
        )
    }

    private fun rebuildContentBody() {
        contentBody.clearChildren()
        resetSlots.clear()

        val visible = filteredCategories()
        val cat = visible.firstOrNull { it.id == activeCategory }
        if (cat == null) {
            val empty = UIComponents.label(
                Component.literal("No matching options.").withStyle { it.withColor(Theme.TEXT_DIM) }
            )
            empty.margins(Insets.of(20))
            contentBody.child(empty)
            return
        }
        val rawSub = cat.subcategories.firstOrNull { it.subId == activeSubcategory }
            ?: cat.subcategories.firstOrNull() ?: return
        val sub = rawSub.copy(options = rawSub.options.filter { isOptionVisible(it) })

        // Persistent banner for the Dev category — sits above the scrolling content.
        if (activeCategory == "dev") {
            val warning = UIComponents.label(Component.translatable("text.config.soul/config.dev.warning"))
                .color(Theme.color(Theme.TEXT_DIM))
            warning.margins(Insets.of(12, 4, 16, 16))
            contentBody.child(warning)
        }

        val scrollBody = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        // Right padding leaves room for the scrollbar so rows don't visually clip.
        scrollBody.padding(Insets.of(12, 16, 16, 20))
        scrollBody.gap(8)
        scrollBody.horizontalAlignment(HorizontalAlignment.LEFT)

        val visibleFieldNames = sub.options.map { it.key().path().last() }.toSet()
        val sections = explicitSections[activeCategory]?.get(activeSubcategory)
        if (sections != null) {
            // Explicit section layout: split flat options into labeled groups by field name.
            val byField = sub.options.associateBy { it.key().path().last() }
            val placed = mutableSetOf<String>()
            for ((label, fieldNames) in sections) {
                val sectionOpts = fieldNames.mapNotNull { byField[it] }.also {
                    placed.addAll(fieldNames.filter { name -> name in visibleFieldNames })
                }
                if (sectionOpts.isEmpty()) continue
                addSection(scrollBody, label, sectionOpts)
            }
            // Any options not covered by an explicit section go into a trailing card,
            // labelled with the subcategory name as a fallback.
            val remaining = sub.options.filter { it.key().path().last() !in placed }
            if (remaining.isNotEmpty()) addSection(scrollBody, sub.displayName.string, remaining)
        } else {
            // Auto-group by 3rd path segment (e.g. render.overlays.field → group "overlays").
            // Flat options (depth ≤ 2) fall back to the subcategory's own display name.
            val grouped = LinkedHashMap<String?, MutableList<Option<*>>>()
            for (opt in sub.options) {
                val path = opt.key().path()
                val group = if (path.size >= 4) path[2] else null
                grouped.getOrPut(group) { mutableListOf() }.add(opt)
            }
            for ((groupId, opts) in grouped) {
                val displayName: String = if (groupId != null) {
                    val nameKey = "text.config.soul/config.group.${activeCategory}.${activeSubcategory}.$groupId"
                    val nameText = Component.translatable(nameKey)
                    if (nameText.string == nameKey) formatGroupId(groupId) else nameText.string
                } else {
                    sub.displayName.string
                }
                addSection(scrollBody, displayName, opts)
            }
        }

        // Append action-button rows (e.g. "Reload Config from Disk").
        val actions = actionRows[activeCategory]?.get(activeSubcategory)
        if (!actions.isNullOrEmpty()) {
            addActionSection(scrollBody, sub.displayName.string, actions)
        }

        // Append cross-navigation link buttons configured for this subcategory.
        val links = linkSections[activeCategory]?.get(activeSubcategory)
        if (!links.isNullOrEmpty()) {
            addLinkSection(scrollBody, sub.displayName.string, links)
        }

        val scroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), scrollBody)
        scroll.scrollbarThiccness(4)
        contentBody.child(scroll)
    }

    private fun addSection(parent: FlowLayout, label: String?, opts: List<Option<*>>) {
        if (label != null) {
            val lbl = UIComponents.label(Component.literal(label))
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

        // Visual hint that this option is gated by another option above (e.g. usePestVest under highlightPestEquipment).
        val isDependent = opt.key().path().joinToString(".") in optionVisibility
        if (isDependent) {
            val arrow = UIComponents.label(Component.literal("↳"))
                .color(Theme.color(Theme.TEXT_DIM))
            arrow.margins(Insets.of(0, 0, 8, 0))
            row.child(arrow)
        }

        val label = UIComponents.label(Component.translatable(opt.translationKey()))
            .color(Theme.color(Theme.TEXT))
        // Label expands to consume all leftover space, pushing the control to the row's end.
        label.horizontalSizing(Sizing.expand())

        val tooltipKey = opt.translationKey() + ".tooltip"
        val tooltipText = Component.translatable(tooltipKey)
        if (tooltipText.string != tooltipKey) {
            label.tooltip(tooltipText)
        }
        row.child(label)

        val pathKey = opt.key().path().joinToString(".")
        when {
            opt.value() is Boolean ->
                row.child(buildToggle(@Suppress("UNCHECKED_CAST") (opt as Option<Boolean>)))
            opt.value() is Int || opt.value() is Long || opt.value() is Float || opt.value() is Double ->
                row.child(buildNumeric(opt))
            opt.value() is String && pathKey in keybindOptions ->
                row.child(buildKeybindButton(@Suppress("UNCHECKED_CAST") (opt as Option<String>)))
            opt.value() is String ->
                row.child(buildTextBox(@Suppress("UNCHECKED_CAST") (opt as Option<String>)))
            else -> row.child(UIComponents.label(Component.literal(opt.value().toString())))
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
        val key = opt.key().path().joinToString(".")
        val toggle = SoulToggle(opt.value()) { newVal ->
            opt.set(newVal)
            save()
            refreshResetSlot(opt)
            if (key in rebuildOnChange) rebuildContentBody()
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

    private fun buildKeybindButton(opt: Option<String>): ButtonComponent {
        val isCapturing = capturingKeybind === opt
        val text = if (isCapturing) "> Press a key <" else keybindLabel(opt.value())
        val btn = UIComponents.button(Component.literal(text)) {
            // Click the same button while capturing → cancel capture.
            capturingKeybind = if (capturingKeybind === opt) null else opt
            rebuildContentBody()
        }
        btn.horizontalSizing(Sizing.fixed(110))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(actionButtonRenderer())
        return btn
    }

    /** Pretty-print a stored key name like `key.keyboard.f6` → `F6`. Empty string → `Not bound`. */
    private fun keybindLabel(stored: String): String {
        if (stored.isBlank()) return "Not bound"
        return try {
            InputConstants.getKey(stored).getDisplayName().string
        } catch (_: Throwable) {
            stored
        }
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        val capturing = capturingKeybind
        if (capturing != null) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                capturing.set("")
            } else {
                capturing.set(InputConstants.getKey(input).getName())
            }
            capturingKeybind = null
            save()
            rebuildContentBody()
            return true
        }
        return super.keyPressed(input)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        val capturing = capturingKeybind
        // The very click that *enters* capture mode is consumed by the button's onClick first; this
        // override only fires for clicks that *aren't* on the button — i.e. the user wants to bind a mouse btn.
        if (capturing != null) {
            val key = InputConstants.Type.MOUSE.getOrCreate(click.button())
            capturing.set(key.getName())
            capturingKeybind = null
            save()
            rebuildContentBody()
            return true
        }
        return super.mouseClicked(click, doubled)
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
        val btn = UIComponents.button(Component.empty()) {
            (opt as Option<Any>).set(default as Any)
            save()
            rebuildContent()
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fill(100))
        btn.tooltip(Component.literal("Reset to default: ${formatValue(default)}"))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.PANEL_HOVER else Theme.PANEL_INSET
            ctx.fill(button.x, button.y, button.x + button.width, button.y + button.height, bg)
            val tr = Minecraft.getInstance().font
            val icon = "↺"
            val tx = button.x + (button.width - tr.width(icon)) / 2
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(icon), tx, ty, if (button.isHovered) Theme.ACCENT else Theme.TEXT_DIM, false)
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

    // ---- search ----

    private fun applySearch() {
        adjustActiveToFilter()
        rebuildSidebarList()
        rebuildContent()
    }

    /**
     * Sync active selection and expansion state with the current filter.
     * - Picks a visible (cat, sub) if the previous active is hidden.
     * - While searching: expand all visible categories so matches are immediately discoverable.
     * - When search is cleared: collapse all but the active category.
     */
    private fun adjustActiveToFilter() {
        val visible = filteredCategories()
        if (visible.isEmpty()) {
            expandedCategories.clear()
            return
        }
        val cat = visible.firstOrNull { it.id == activeCategory } ?: visible.first()
        activeCategory = cat.id
        val sub = cat.subcategories.firstOrNull { it.subId == activeSubcategory }
            ?: cat.subcategories.firstOrNull()
        if (sub != null) activeSubcategory = sub.subId

        expandedCategories.clear()
        if (searchQuery.isNotBlank()) {
            visible.forEach { expandedCategories.add(it.id) }
        } else if (activeCategory.isNotEmpty()) {
            expandedCategories.add(activeCategory)
        }
    }

    /**
     * Returns categories filtered against [searchQuery]. Empty query returns all categories.
     * Match rules:
     *  - Category display-name match → keep entire category and all subs intact.
     *  - Subcategory display-name match → keep entire sub intact.
     *  - Otherwise → keep only options whose label or tooltip text contains the query.
     */
    private fun filteredCategories(): List<CategoryEntry> {
        val q = searchQuery.trim()
        if (q.isEmpty()) return categories
        return categories.mapNotNull { cat ->
            val catMatches = cat.displayName.string.contains(q, ignoreCase = true)
            val filteredSubs = cat.subcategories.mapNotNull { sub ->
                val subMatches = sub.displayName.string.contains(q, ignoreCase = true)
                if (catMatches || subMatches) {
                    sub
                } else {
                    val matchingOpts = sub.options.filter { optMatchesText(it, q) }
                    if (matchingOpts.isEmpty()) null
                    else SubcategoryEntry(sub.catId, sub.subId, sub.displayName, matchingOpts)
                }
            }
            if (filteredSubs.isEmpty()) null
            else CategoryEntry(cat.id, cat.displayName, filteredSubs)
        }
    }

    private fun optMatchesText(opt: Option<*>, q: String): Boolean {
        if (!isOptionVisible(opt)) return false
        val labelText = Component.translatable(opt.translationKey()).string
        if (labelText.contains(q, ignoreCase = true)) return true
        val tooltipKey = opt.translationKey() + ".tooltip"
        val tooltipText = Component.translatable(tooltipKey).string
        return tooltipText != tooltipKey && tooltipText.contains(q, ignoreCase = true)
    }

    private fun isOptionVisible(opt: Option<*>): Boolean {
        val key = opt.key().path().joinToString(".")
        val pred = optionVisibility[key] ?: return true
        return try { pred() } catch (_: Throwable) { true }
    }

    /** Render a labeled card containing one-or-more action rows (label + right-aligned button). */
    private fun addActionSection(parent: FlowLayout, label: String, rows: List<ActionRow>) {
        val lbl = UIComponents.label(Component.literal(label))
            .color(Theme.color(Theme.TEXT_DIM))
        lbl.margins(Insets.of(4, 0, 0, 6))
        parent.child(lbl)
        val card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        card.surface(Theme.panelInsetSurface)
        card.padding(Insets.of(8))
        card.gap(2)
        for (r in rows) card.child(actionRow(r))
        parent.child(card)
    }

    private fun actionRow(action: ActionRow): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.surface(Theme.rowSurface())
        row.padding(Insets.of(6, 6, 12, 12))
        row.gap(8)
        row.verticalAlignment(VerticalAlignment.CENTER)
        row.margins(Insets.of(1))

        val label = UIComponents.label(Component.literal(action.label))
            .color(Theme.color(Theme.TEXT))
        label.horizontalSizing(Sizing.expand())
        row.child(label)

        val btn = UIComponents.button(Component.literal(action.buttonText)) { action.action() }
        btn.horizontalSizing(Sizing.fixed(80))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(actionButtonRenderer())
        row.child(btn)
        return row
    }

    private fun actionButtonRenderer(): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.ACCENT else Theme.PANEL_HOVER
            DrawContextRenderer.roundedFill(
                ctx,
                button.x, button.y, button.x + button.width, button.y + button.height,
                bg, Theme.ITEM_RADIUS
            )
        }
    }

    private fun addLinkSection(parent: FlowLayout, label: String, links: List<LinkTarget>) {
        val lbl = UIComponents.label(Component.literal(label))
            .color(Theme.color(Theme.TEXT_DIM))
        lbl.margins(Insets.of(4, 0, 0, 6))
        parent.child(lbl)
        val container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        container.gap(4)
        for (link in links) container.child(linkRow(link))
        parent.child(container)
    }

    private fun linkRow(link: LinkTarget): ButtonComponent {
        val btn = UIComponents.button(Component.empty()) {
            navigateTo(link.targetCat, link.targetSub)
        }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(28))
        btn.margins(Insets.of(2))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.PANEL_HOVER else Theme.PANEL_INSET
            DrawContextRenderer.roundedFill(
                ctx,
                button.x, button.y, button.x + button.width, button.y + button.height,
                bg, Theme.ITEM_RADIUS
            )
            val tr = Minecraft.getInstance().font
            val text = "${link.label}  ›"
            val tx = button.x + 12
            val ty = button.y + (button.height - tr.lineHeight) / 2
            val color = if (button.isHovered) Theme.ACCENT else Theme.TEXT
            ctx.drawString(tr, Component.literal(text), tx, ty, color, false)
        })
        return btn
    }

    private fun navigateTo(catId: String, subId: String) {
        activeCategory = catId
        activeSubcategory = subId
        expandedCategories.add(catId)
        rebuildSidebarList()
        rebuildContent()
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

    private fun discordButton(): ButtonComponent = linkButton(
        DISCORD_ICON, DISCORD_TEX_W, DISCORD_TEX_H,
        14, 11,
        "https://discord.gg/Mn5dzEJEaJ",
        "Join the Discord"
    )

    private fun githubButton(): ButtonComponent = linkButton(
        GITHUB_ICON, GITHUB_TEX_W, GITHUB_TEX_H,
        11, 11,
        "https://github.com/Soul-Returns/SoulMod",
        "View on GitHub"
    )

    private fun linkButton(
        texture: Identifier,
        texW: Int, texH: Int,
        destW: Int, destH: Int,
        url: String,
        tooltip: String
    ): ButtonComponent {
        val btn = UIComponents.button(Component.empty()) {
            Util.getPlatform().openUri(URI.create(url))
        }
        btn.horizontalSizing(Sizing.fixed(destW))
        btn.verticalSizing(Sizing.fixed(destH))
        btn.tooltip(Component.literal(tooltip))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val tint = if (button.isHovered) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
            ctx.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                button.x, button.y,
                0f, 0f,
                button.width, button.height,
                texW, texH,
                texW, texH,
                tint
            )
        })
        return btn
    }

    private fun sidebarFooter(): FlowLayout {
        val container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        container.horizontalAlignment(HorizontalAlignment.CENTER)
        container.margins(Insets.of(2, 0, 0, 0))

        val moveGui = UIComponents.button(Component.literal("Move GUI")) {
            Minecraft.getInstance().setScreen(GuiEditScreen())
        }
        moveGui.horizontalSizing(Sizing.fill(100))
        moveGui.verticalSizing(Sizing.fixed(18))
        moveGui.renderer(footerButtonRenderer(accent = false))
        container.child(moveGui)

        return container
    }

    private fun footer(): FlowLayout {
        val f = UIContainers.horizontalFlow(Sizing.fill(85), Sizing.fixed(36))
        f.padding(Insets.of(6))
        f.gap(6)
        f.verticalAlignment(VerticalAlignment.CENTER)
        f.horizontalAlignment(HorizontalAlignment.RIGHT)

        val done = UIComponents.button(Component.literal("Done")) { onClose() }
        done.horizontalSizing(Sizing.fixed(80))
        done.verticalSizing(Sizing.fixed(24))
        done.renderer(footerButtonRenderer(accent = true))

        f.child(done)
        return f
    }

    private fun categoryHeaderRenderer(text: String, expanded: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val tr = Minecraft.getInstance().font
            if (button.isHovered) {
                DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS
                )
            }
            val arrow = if (expanded) "▾" else "▸"
            val label = "$arrow  $text"
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(label), button.x + 6, ty, Theme.TEXT, false)
        }
    }

    private fun sidebarItemRenderer(text: String, selected: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val tr = Minecraft.getInstance().font
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
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(text), button.x + 10, ty, textColor, false)
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
        // Inject subcategories that have no backing config fields (purely navigational).
        for ((catId, subIds) in virtualSubs) {
            val groups = byCat.getOrPut(catId) { LinkedHashMap() }
            for (subId in subIds) groups.getOrPut(subId) { mutableListOf() }
        }
        val maxOrderIdx = categoryOrder.size
        return byCat.entries.sortedBy { entry ->
            val idx = categoryOrder.indexOf(entry.key)
            if (idx < 0) maxOrderIdx else idx
        }.map { (catId, groups) ->
            val subs = groups.entries.map { (subId, options) ->
                val nameKey = "text.config.soul/config.group.$catId.$subId"
                SubcategoryEntry(
                    catId = catId,
                    subId = subId,
                    displayName = Component.translatable(nameKey),
                    options = options
                )
            }
            CategoryEntry(
                id = catId,
                displayName = Component.translatable("text.config.soul/config.category.$catId"),
                subcategories = subs
            )
        }
    }
}
