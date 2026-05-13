package com.soulreturns.config.gui

import com.mojang.blaze3d.platform.InputConstants
import com.soulreturns.Soul
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.gui.GuiEditScreen
import com.soulreturns.ui.config.components.ConfigRenderers
import com.soulreturns.ui.config.components.SocialIcons
import com.soulreturns.ui.config.model.CategoriesCollector
import com.soulreturns.ui.config.model.CategoryEntry
import com.soulreturns.ui.config.model.ConfigScreenContext
import com.soulreturns.ui.config.model.SubcategoryEntry
import com.soulreturns.ui.config.registry.ConfigSections
import com.soulreturns.ui.config.rows.RowBuilders
import com.soulreturns.ui.config.search.ConfigSearchFilter
import com.soulreturns.ui.theme.Theme
import io.wispforest.owo.config.ConfigWrapper
import io.wispforest.owo.config.Option
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
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class SoulConfigScreen(initialSearch: String = "") :
    BaseOwoScreen<FlowLayout>(
        Component.translatable("text.config.soul/config.title")
    ),
    ConfigScreenContext {
    private val wrapper: ConfigWrapper<*> get() = SoulConfigHolder.INSTANCE

    /** When non-null, the next key/mouse press binds this option instead of acting on the screen. */
    private var capturingKeybind: Option<String>? = null

    override fun requestKeybindCapture(opt: Option<String>) {
        // Click the same button while capturing → cancel; otherwise start capturing this opt.
        capturingKeybind = if (capturingKeybind === opt) null else opt
    }

    override fun isCapturing(opt: Option<String>): Boolean = capturingKeybind === opt

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

    // Display text for each subcategory — populated on rebuild, never mutated, safe for renderers.
    private val subcategoryDisplayNames = mutableMapOf<Pair<String, String>, String>()

    /** Row/section builders, scoped to this screen instance. Lazy so [wrapper]'s getter is ready. */
    private val rows: RowBuilders by lazy { RowBuilders(this, wrapper) }

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

        val sidebarScrollLocal =
            UIContainers.verticalScroll(
                Sizing.fill(100),
                Sizing.expand(),
                sidebarList
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

        val searchLabel =
            UIComponents.label(Component.translatable("text.config.soul/config.search"))
                .color(Theme.color(Theme.TEXT_DIM))
        headerRow.child(searchLabel)

        searchBox = UIComponents.textBox(Sizing.fixed(160), searchQuery)
        searchBox.onChanged().subscribe(
            TextBoxComponent.OnChanged { newVal ->
                if (newVal != searchQuery) {
                    searchQuery = newVal
                    applySearch()
                }
            }
        )
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
            val empty =
                UIComponents.label(
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
        val btn =
            UIComponents.button(Component.empty()) {
                toggleCategory(cat)
            }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(18))
        btn.renderer(ConfigRenderers.categoryHeader(label, expanded))
        btn.margins(Insets.of(3, 1, 0, 0))
        return btn
    }

    private fun sidebarButton(
        cat: CategoryEntry,
        sub: SubcategoryEntry
    ): ButtonComponent {
        val displayText = sub.displayName.string
        val btn =
            UIComponents.button(Component.empty()) {
                if (activeCategory != cat.id || activeSubcategory != sub.subId) {
                    activeCategory = cat.id
                    activeSubcategory = sub.subId
                    refreshSidebarSelection()
                    rebuildContent()
                }
            }
        btn.horizontalSizing(Sizing.fill(100))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(ConfigRenderers.sidebarItem(displayText, cat.id == activeCategory && sub.subId == activeSubcategory))
        btn.margins(Insets.of(1, 1, 0, 2))
        return btn
    }

    private fun refreshSidebarSelection() {
        for ((key, btn) in sidebarButtons) {
            val selected = key.first == activeCategory && key.second == activeSubcategory
            val displayText = subcategoryDisplayNames[key] ?: ""
            btn.renderer(ConfigRenderers.sidebarItem(displayText, selected))
        }
    }

    private fun spacer(h: Int): FlowLayout {
        return UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(h))
    }

    override fun rebuildContent() {
        rebuildBreadcrumb()
        rebuildContentBody()
    }

    private fun rebuildBreadcrumb() {
        breadcrumbBar.clearChildren()
        val visible = filteredCategories()
        val cat = visible.firstOrNull { it.id == activeCategory } ?: return
        val sub =
            cat.subcategories.firstOrNull { it.subId == activeSubcategory }
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

    override fun rebuildContentBody() {
        contentBody.clearChildren()
        rows.resetTracking()

        val visible = filteredCategories()
        val cat = visible.firstOrNull { it.id == activeCategory }
        if (cat == null) {
            val empty =
                UIComponents.label(
                    Component.literal("No matching options.").withStyle { it.withColor(Theme.TEXT_DIM) }
                )
            empty.margins(Insets.of(20))
            contentBody.child(empty)
            return
        }
        val rawSub =
            cat.subcategories.firstOrNull { it.subId == activeSubcategory }
                ?: cat.subcategories.firstOrNull() ?: return
        val sub = rawSub.copy(options = rawSub.options.filter { isOptionVisible(it) })

        // Persistent banner for the Dev category — sits above the scrolling content.
        if (activeCategory == "dev") {
            val warning =
                UIComponents.label(Component.translatable("text.config.soul/config.dev.warning"))
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
        val sections = ConfigSections.explicitSections[activeCategory]?.get(activeSubcategory)
        if (sections != null) {
            // Explicit section layout: split flat options into labeled groups by field name.
            val byField = sub.options.associateBy { it.key().path().last() }
            val placed = mutableSetOf<String>()
            for ((label, fieldNames) in sections) {
                val sectionOpts =
                    fieldNames.mapNotNull { byField[it] }.also {
                        placed.addAll(fieldNames.filter { name -> name in visibleFieldNames })
                    }
                if (sectionOpts.isEmpty()) continue
                rows.addOptionSection(scrollBody, label, sectionOpts)
            }
            // Any options not covered by an explicit section go into a trailing card,
            // labelled with the subcategory name as a fallback.
            val remaining = sub.options.filter { it.key().path().last() !in placed }
            if (remaining.isNotEmpty()) rows.addOptionSection(scrollBody, sub.displayName.string, remaining)
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
                val displayName: String =
                    if (groupId != null) {
                        val nameKey = "text.config.soul/config.group.$activeCategory.$activeSubcategory.$groupId"
                        val nameText = Component.translatable(nameKey)
                        if (nameText.string == nameKey) formatGroupId(groupId) else nameText.string
                    } else {
                        sub.displayName.string
                    }
                rows.addOptionSection(scrollBody, displayName, opts)
            }
        }

        // Append action-button rows (e.g. "Reload Config from Disk").
        val actions = ConfigSections.actionRows[activeCategory]?.get(activeSubcategory)
        if (!actions.isNullOrEmpty()) {
            rows.addActionSection(scrollBody, sub.displayName.string, actions)
        }

        // Append cross-navigation link buttons configured for this subcategory.
        val links = ConfigSections.linkSections[activeCategory]?.get(activeSubcategory)
        if (!links.isNullOrEmpty()) {
            rows.addLinkSection(scrollBody, sub.displayName.string, links)
        }

        val scroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), scrollBody)
        scroll.scrollbarThiccness(4)
        contentBody.child(scroll)
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

    override fun mouseClicked(
        click: MouseButtonEvent,
        doubled: Boolean
    ): Boolean {
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

    override fun save() {
        try {
            wrapper.save()
        } catch (_: Throwable) {
        }
    }

    override fun reloadConfig() {
        try {
            wrapper.load()
        } catch (_: Throwable) {
        }
        rebuildContent()
    }

    /**
     * Called when the screen is closed / replaced. owo-config writes each option change to
     * disk immediately as the user toggles, so by the time we get here the file is up to date.
     * Signal the cloud sync engine so the new state pushes to the backend within ~2 s
     * instead of waiting for the next periodic 60 s reconcile.
     */
    override fun removed() {
        super.removed()
        try {
            com.soulreturns.platform.sync.SyncEngine.notifyChanged(
                com.soulreturns.platform.sync.SyncKind.CONFIG
            )
        } catch (_: Throwable) {
            // SyncEngine not initialised / disabled — periodic scan will pick it up.
        }
    }

    private fun formatGroupId(id: String): String =
        // "playerRendering" → "Player Rendering", "tooltips" → "Tooltips"
        id.replace(Regex("([A-Z])"), " $1")
            .replaceFirstChar { it.uppercase() }
            .trim()

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
        val sub =
            cat.subcategories.firstOrNull { it.subId == activeSubcategory }
                ?: cat.subcategories.firstOrNull()
        if (sub != null) activeSubcategory = sub.subId

        expandedCategories.clear()
        if (searchQuery.isNotBlank()) {
            visible.forEach { expandedCategories.add(it.id) }
        } else if (activeCategory.isNotEmpty()) {
            expandedCategories.add(activeCategory)
        }
    }

    /** Search filtering delegated to [ConfigSearchFilter] — pure function over the categories list. */
    private fun filteredCategories(): List<CategoryEntry> = ConfigSearchFilter.filter(categories, searchQuery)

    /** Visibility predicate delegated to [ConfigSections]; same logic, single source of truth. */
    private fun isOptionVisible(opt: Option<*>): Boolean = ConfigSections.isOptionVisible(opt)

    override fun navigateTo(
        catId: String,
        subId: String
    ) {
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

    private fun discordButton(): ButtonComponent = SocialIcons.discord()

    private fun githubButton(): ButtonComponent = SocialIcons.github()

    private fun sidebarFooter(): FlowLayout {
        val container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        container.horizontalAlignment(HorizontalAlignment.CENTER)
        container.margins(Insets.of(2, 0, 0, 0))

        val moveGui =
            UIComponents.button(Component.literal("Move GUI")) {
                Minecraft.getInstance().setScreen(GuiEditScreen())
            }
        moveGui.horizontalSizing(Sizing.fill(100))
        moveGui.verticalSizing(Sizing.fixed(18))
        moveGui.renderer(ConfigRenderers.footerButton(accent = false))
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
        done.renderer(ConfigRenderers.footerButton(accent = true))

        f.child(done)
        return f
    }

    // ---- model collection ----

    /** Reads the owo-config wrapper into normalized [CategoryEntry]s — pure delegation. */
    private fun collectCategories(): List<CategoryEntry> = CategoriesCollector.collect(wrapper)
}
