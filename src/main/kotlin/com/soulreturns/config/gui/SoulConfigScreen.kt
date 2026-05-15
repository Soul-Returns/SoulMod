package com.soulreturns.config.gui

import com.soulreturns.Soul
import com.soulreturns.config.SoulConfigHolder
import com.soulreturns.gui.GuiEditScreen
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.HorizontalAlignment
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.boxShadow
import com.soulreturns.ui.composer.clickable
import com.soulreturns.ui.composer.fillMaxSize
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.config.model.ActionRowSpec
import com.soulreturns.ui.config.model.CategoriesCollector
import com.soulreturns.ui.config.model.CategoryEntry
import com.soulreturns.ui.config.model.ConfigScreenContext
import com.soulreturns.ui.config.model.LinkTarget
import com.soulreturns.ui.config.model.SubcategoryEntry
import com.soulreturns.ui.config.registry.ConfigSections
import com.soulreturns.ui.config.search.ConfigSearchFilter
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.ScrollableList
import com.soulreturns.ui.foundation.Slider
import com.soulreturns.ui.foundation.Spacer
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.foundation.TextField
import com.soulreturns.ui.foundation.Toggle
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.runtime.SoulScreen
import com.soulreturns.ui.theme.SoulTheme
import io.wispforest.owo.config.ConfigWrapper
import io.wispforest.owo.config.Option
import io.wispforest.owo.config.annotation.RangeConstraint
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * Soul mod's settings screen — rebuilt on the Soul UI framework (P4.3 migration).
 *
 * **MVP scope** (this iteration):
 *  - Card-on-page layout with sidebar (categories + subs) and content body.
 *  - Toggle / numeric slider / text field / generic-label option rows.
 *  - Per-option reset button (small `↺` chip when the value differs from default).
 *  - Breadcrumb header + Done footer.
 *
 * **Not yet** (follow-up passes — see P4.3 sub-tasks): search filtering, keybind capture
 * mode, action / link rows, sectioned option grouping, ↳ dependent-indent glyph, dev-category
 * banner, sidebar Soul-logo + version + social-icons strip.
 *
 * Implements [ConfigScreenContext] so anything we keep from the old owo-ui helpers still has
 * a stable surface. Most callbacks reduce to no-ops because state changes auto-show on the
 * next frame (declarative model — no manual rebuild).
 */
class SoulConfigScreen(private val initialSearch: String = "") :
    SoulScreen(Component.translatable("text.config.soul/config.title")),
    ConfigScreenContext {
    private companion object {
        // Fixed height for every option / action / link row so all widget types (toggle 18px,
        // slider 14px, text field 18px, button 27px) center inside the same vertical slot —
        // visual cadence stays consistent across mixed rows in a section card.
        const val ROW_HEIGHT = 32f
    }

    private val wrapper: ConfigWrapper<*> get() = SoulConfigHolder.INSTANCE

    private val categories: List<CategoryEntry> by lazy { CategoriesCollector.collect(wrapper) }

    @Volatile private var activeCategory: String = ""

    @Volatile private var activeSubcategory: String = ""

    @Volatile private var searchQuery: String = initialSearch

    @Volatile private var sidebarScroll: Float = 0f

    @Volatile private var bodyScroll: Float = 0f

    @Volatile private var capturingKeybind: Option<String>? = null

    /** Set of category ids whose sub-list is expanded in the sidebar. Mutable; per-frame read. */
    private val expandedCategories = mutableSetOf<String>()

    init {
        // Pick the first visible (category, subcategory) so we don't open onto an empty body.
        val first = categories.firstOrNull()
        activeCategory = first?.id ?: ""
        activeSubcategory = first?.subcategories?.firstOrNull()?.subId ?: ""
        if (activeCategory.isNotEmpty()) expandedCategories.add(activeCategory)
    }

    override fun shouldCloseOnEsc(): Boolean = true

    override fun blurBackground(): Boolean = true

    override fun mouseClicked(
        click: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        // Pre-empt the click if we're in keybind-capture mode and the user clicks anywhere
        // *outside* the keybind button (the button itself handles its own click via SoulInput).
        // For now this also handles "rebind to a mouse button" — accept the click as a binding.
        val capturing = capturingKeybind
        if (capturing != null) {
            // If the click landed on a SoulInput hit region (i.e. the keybind button itself),
            // its onClick already toggled capture off. Otherwise treat as mouse-button binding.
            val key = com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(click.button())
            capturing.set(key.getName())
            capturingKeybind = null
            save()
            return true
        }
        return super.mouseClicked(click, doubleClick)
    }

    override fun keyPressed(keyEvent: net.minecraft.client.input.KeyEvent): Boolean {
        val capturing = capturingKeybind
        if (capturing != null) {
            if (keyEvent.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                capturing.set("")
            } else {
                capturing.set(com.mojang.blaze3d.platform.InputConstants.getKey(keyEvent).getName())
            }
            capturingKeybind = null
            save()
            return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun removed() {
        super.removed()
        // Mirror the legacy screen behavior: when the user closes settings, kick the cloud
        // sync engine so the new config state pushes to the backend in ~2 s instead of waiting
        // for the next periodic 60 s reconcile.
        try {
            com.soulreturns.platform.sync.SyncEngine.notifyChanged(
                com.soulreturns.platform.sync.SyncKind.CONFIG,
            )
        } catch (_: Throwable) {
            // SyncEngine not initialised / disabled — periodic scan will eventually pick it up.
        }
    }

    @SoulComposable
    override fun Content() {
        // Card-on-page sizing. Clamp so ultra-wide doesn't stretch the card into unusable
        // dimensions and tiny windows still get a readable layout.
        val cardW = (width * 0.9f).coerceIn(640f, 1100f)
        val cardH = (height * 0.88f).coerceIn(400f, 720f)
        val sidebarW = 200f
        val topNavH = 44f
        val footerH = 40f
        val contentRowH = (cardH - topNavH).coerceAtLeast(160f)

        // Subtle translucent dark overlay over Minecraft's blurred game backdrop, so the
        // page around the card feels darker / focuses attention on the settings.
        Column(
            modifier = SoulModifier.Empty.fillMaxSize().background(color = 0x66000000, radius = 0f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
            gap = 6f,
        ) {
            // Card: rounded `panel` shell. Inner darker zones use half-rounded backgrounds
            // so they match the card's outer corners where they touch the card's edge and
            // stay square elsewhere — no clipping notches into the rounded corners.
            Box(
                modifier =
                    SoulModifier.Empty
                        .width(cardW)
                        .height(cardH)
                        .background(color = SoulTheme.colors.panel, radius = SoulTheme.dimens.radiusMedium),
            ) {
                Column(modifier = SoulModifier.Empty.fillMaxSize()) {
                    TopNav(cardW = cardW, topNavH = topNavH, sidebarW = sidebarW)
                    ContentRow(cardW = cardW, height = contentRowH, sidebarW = sidebarW)
                }
            }

            // Done button strip beneath the card.
            Row(
                modifier = SoulModifier.Empty.width(cardW).height(footerH),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = VerticalAlignment.Center,
                gap = 8f,
            ) {
                Button(
                    label = "Done",
                    onClick = { onClose() },
                    accent = true,
                    key = "config.done",
                )
            }
        }
    }

    // ──────────────────────────────────── top nav ────────────────────────────────────────

    @SoulComposable
    private fun TopNav(
        cardW: Float,
        topNavH: Float,
        sidebarW: Float,
    ) {
        Row(
            modifier =
                SoulModifier.Empty
                    .width(cardW)
                    .height(topNavH)
                    .boxShadow(
                        blur = 6f,
                        spread = 0f,
                        side = com.soulreturns.ui.composer.ShadowSide.Bottom,
                        color = 0xFF111111.toInt(),
                    )
                    .background(
                        color = 0xFF111111.toInt(),
                        radius = SoulTheme.dimens.radiusMedium,
                        rounding = com.soulreturns.ui.composer.CornerRounding.Top,
                    ),
        ) {
            // Title section — same width as the sidebar so its vertical splitter aligns
            // with the sidebar/body divider in the content row below.
            Row(
                modifier =
                    SoulModifier.Empty
                        .width(sidebarW)
                        .height(topNavH)
                        .padding(left = 16f, right = 12f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = VerticalAlignment.Center,
            ) {
                Text(
                    text = "Soul",
                    size = SoulTheme.typography.title.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.title.font,
                )
                Text(
                    text = "v${Soul.version.substringBefore("+")}",
                    size = SoulTheme.typography.caption.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.caption.font,
                )
            }
            VDivider(height = topNavH)
            // Header section — breadcrumb on the left, search field on the right.
            Row(
                modifier =
                    SoulModifier.Empty
                        .width((cardW - sidebarW - 1f).coerceAtLeast(200f))
                        .height(topNavH)
                        .padding(left = 18f, right = 20f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = VerticalAlignment.Center,
                gap = 8f,
            ) {
                Breadcrumb()
                SearchField()
            }
        }
    }

    @SoulComposable
    private fun ContentRow(
        cardW: Float,
        height: Float,
        sidebarW: Float,
    ) {
        // No line between sidebar and body — the sidebar's right-side shadow handles the
        // visual separation. The vertical line splitter only lives inside the top nav.
        Row(modifier = SoulModifier.Empty.width(cardW).height(height)) {
            Sidebar(width = sidebarW, height = height)
            ContentPane(
                width = (cardW - sidebarW).coerceAtLeast(360f),
                height = height,
            )
        }
    }

    // ────────────────────────────────────── sidebar ──────────────────────────────────────

    @SoulComposable
    private fun Sidebar(
        width: Float,
        height: Float,
    ) {
        val footerH = 36f
        val listH = (height - footerH).coerceAtLeast(80f)

        // Sidebar shell paints one uniform dark bg (rounded bottom-left to match the card).
        // The inner list/footer Boxes are transparent — they exist only as separate hit-
        // region containers so the Move GUI button is isolated from the scrollable list's
        // hover/click regions. Visually the sidebar looks like one solid dark column with
        // the Move GUI button floating at the bottom.
        Column(
            modifier =
                SoulModifier.Empty
                    .width(width)
                    .height(height)
                    .boxShadow(
                        blur = 6f,
                        spread = 0f,
                        side = com.soulreturns.ui.composer.ShadowSide.Right,
                        color = 0xFF161616.toInt(),
                    )
                    .background(
                        color = 0xFF161616.toInt(),
                        radius = SoulTheme.dimens.radiusMedium,
                        rounding = com.soulreturns.ui.composer.CornerRounding.BottomLeft,
                    ),
        ) {
            // Scrolling list zone — transparent container with its own padding. Bottom
            // padding is 0 so the gap above the Move GUI button equals the footer's top
            // padding only (otherwise this zone's bottom padding would double-stack).
            Box(
                modifier =
                    SoulModifier.Empty
                        .fillMaxWidth()
                        .height(listH)
                        .padding(top = 10f, bottom = 0f, left = 10f, right = 6f),
            ) {
                ScrollableList(
                    scrollOffset = sidebarScroll,
                    onScroll = { sidebarScroll = it },
                    modifier = SoulModifier.Empty.fillMaxWidth().height(listH - 10f),
                    gap = 2f,
                    scrollStep = 18f,
                    key = "config.sidebar.scroll",
                ) {
                    SidebarList()
                }
            }
            // Footer zone — transparent container; the Move GUI button "floats" on the
            // sidebar's shared dark bg with uniform margin on all four sides.
            Box(
                modifier =
                    SoulModifier.Empty
                        .fillMaxWidth()
                        .height(footerH)
                        .padding(all = 6f),
            ) {
                Button(
                    label = "Move GUI",
                    onClick = { Minecraft.getInstance().setScreen(GuiEditScreen()) },
                    modifier = SoulModifier.Empty.fillMaxWidth(),
                    key = "config.movegui",
                )
            }
        }
    }

    @SoulComposable
    private fun SidebarList() {
        val visible = filteredCategories()
        if (visible.isEmpty()) {
            Text(
                text = "No results",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
            return
        }
        // Search expands everything so matches stay discoverable; clear search collapses
        // to the active category only.
        if (searchQuery.isNotBlank()) {
            expandedCategories.clear()
            visible.forEach { expandedCategories.add(it.id) }
        }
        Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 2f) {
            for (cat in visible) {
                CategoryHeader(cat)
                if (expandedCategories.contains(cat.id)) {
                    for (sub in cat.subcategories) {
                        SubItem(cat, sub)
                    }
                }
                Spacer(modifier = SoulModifier.Empty.height(2f))
            }
        }
    }

    @SoulComposable
    private fun CategoryHeader(cat: CategoryEntry) {
        val key = "config.cathead.${cat.id}"
        val expanded = expandedCategories.contains(cat.id)
        // Active = this category contains the currently-selected subcategory. The active
        // category gets a darker rounded pill so it reads as the "framed" current section
        // in the sidebar — matching the design pattern of the reference (where the active
        // category sits inside its own surface holding the indented child list).
        val isActive = cat.id == activeCategory
        val chevron = if (expanded) "−" else "+"
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(28f)
                    .let {
                        if (isActive && expanded) {
                            it.background(color = 0xFF0E0E0E.toInt(), radius = SoulTheme.dimens.radiusSmall)
                        } else {
                            it
                        }
                    }
                    .clickable(key) {
                        if (expanded) expandedCategories.remove(cat.id) else expandedCategories.add(cat.id)
                    },
        ) {
            Row(
                modifier = SoulModifier.Empty.fillMaxSize().padding(left = 10f, right = 10f),
                verticalAlignment = VerticalAlignment.Center,
                horizontalArrangement = Arrangement.SpaceBetween,
                gap = 6f,
            ) {
                Text(
                    text = cat.displayName.string,
                    size = SoulTheme.typography.body.size,
                    color = if (isActive) SoulTheme.colors.text else SoulTheme.colors.textDim,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text = chevron,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }

    @SoulComposable
    private fun SubItem(
        cat: CategoryEntry,
        sub: SubcategoryEntry,
    ) {
        val key = "config.sub.${cat.id}.${sub.subId}"
        val isActive = activeCategory == cat.id && activeSubcategory == sub.subId
        val isHovered = SoulInput.isHovered(key)
        val pillBg =
            when {
                isActive -> SoulTheme.colors.accent
                isHovered -> SoulTheme.colors.panelHover
                else -> 0
            }
        val labelColor =
            when {
                isActive -> 0xFFFFFFFFu.toInt()
                isHovered -> SoulTheme.colors.text
                else -> SoulTheme.colors.textDim
            }
        // Full-width pill. Visual "child of category" indent comes from the inner text
        // padding (18px), not from leaving sidebar-edge whitespace.
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(24f)
                    .let { if (pillBg != 0) it.background(color = pillBg, radius = SoulTheme.dimens.radiusSmall) else it }
                    .clickable(key) {
                        if (activeCategory != cat.id || activeSubcategory != sub.subId) {
                            activeCategory = cat.id
                            activeSubcategory = sub.subId
                            bodyScroll = 0f
                        }
                    },
        ) {
            Row(
                modifier = SoulModifier.Empty.fillMaxSize().padding(left = 18f, right = 10f),
                verticalAlignment = VerticalAlignment.Center,
            ) {
                Text(
                    text = sub.displayName.string,
                    size = SoulTheme.typography.body.size,
                    color = labelColor,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }

    // ────────────────────────────────────── content pane ─────────────────────────────────

    @SoulComposable
    private fun ContentPane(
        width: Float,
        height: Float,
    ) {
        // Scrollable body — breadcrumb + search now live in the full-width top nav above.
        ScrollableList(
            scrollOffset = bodyScroll,
            onScroll = { bodyScroll = it },
            modifier =
                SoulModifier.Empty
                    .width(width)
                    .height(height)
                    .padding(top = 12f, right = 18f, bottom = 16f, left = 16f),
            gap = 8f,
            scrollStep = 18f,
            key = "config.body.scroll",
        ) {
            Body()
        }
    }

    @SoulComposable
    private fun Breadcrumb() {
        val cat = filteredCategories().firstOrNull { it.id == activeCategory }
        val sub = cat?.subcategories?.firstOrNull { it.subId == activeSubcategory }
        if (cat == null || sub == null) {
            Text(
                text = "Settings",
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.heading.font,
            )
            return
        }
        Row(gap = 8f, verticalAlignment = VerticalAlignment.Center) {
            Text(
                text = cat.displayName.string,
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.heading.font,
            )
            Text(
                text = "›",
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.heading.font,
            )
            Text(
                text = sub.displayName.string,
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.heading.font,
            )
        }
    }

    @SoulComposable
    private fun SearchField() {
        TextField(
            value = searchQuery,
            onChange = { newVal ->
                if (newVal != searchQuery) {
                    searchQuery = newVal
                    // If the active sub got hidden by the new filter, snap to the first visible one.
                    val visible = ConfigSearchFilter.filter(categories, searchQuery)
                    if (visible.none { it.id == activeCategory }) {
                        val first = visible.firstOrNull()
                        activeCategory = first?.id ?: ""
                        activeSubcategory = first?.subcategories?.firstOrNull()?.subId ?: ""
                    }
                    bodyScroll = 0f
                }
            },
            placeholder = "Search…",
            modifier = SoulModifier.Empty.width(180f),
            key = "config.search",
        )
    }

    @SoulComposable
    private fun Body() {
        val visible = filteredCategories()
        val cat = visible.firstOrNull { it.id == activeCategory }
        if (cat == null) {
            DimText("No matching options.")
            return
        }
        val sub = cat.subcategories.firstOrNull { it.subId == activeSubcategory }
            ?: cat.subcategories.firstOrNull()
        if (sub == null) {
            DimText("No options in this category.")
            return
        }
        val options = sub.options.filter { ConfigSections.isOptionVisible(it) }
        val actions = ConfigSections.actionRows[activeCategory]?.get(activeSubcategory)
        val links = ConfigSections.linkSections[activeCategory]?.get(activeSubcategory)
        val isEmpty = options.isEmpty() && actions.isNullOrEmpty() && links.isNullOrEmpty()
        if (isEmpty) {
            DimText("No options to display.")
            return
        }

        Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 12f) {
            if (activeCategory == "dev") DevBanner()
            BuildSections(sub, options)
            if (!actions.isNullOrEmpty()) ActionSection(sub.displayName.string, actions)
            if (!links.isNullOrEmpty()) LinkSection(sub.displayName.string, links)
        }
    }

    @SoulComposable
    private fun BuildSections(
        sub: SubcategoryEntry,
        options: List<Option<*>>,
    ) {
        // Either group flat options into explicit named sections (when the registry declares
        // them) or auto-group by the 3rd path segment for nested option families. Mirrors
        // the legacy screen exactly so existing translation keys + section labels still work.
        val visibleFieldNames = options.map { it.key().path().last() }.toSet()
        val explicit = ConfigSections.explicitSections[activeCategory]?.get(activeSubcategory)
        if (explicit != null) {
            val byField = options.associateBy { it.key().path().last() }
            val placed = mutableSetOf<String>()
            for ((label, fieldNames) in explicit) {
                val sectionOpts = fieldNames.mapNotNull { byField[it] }
                placed.addAll(fieldNames.filter { name -> name in visibleFieldNames })
                if (sectionOpts.isEmpty()) continue
                OptionSection(label, sectionOpts)
            }
            val remaining = options.filter { it.key().path().last() !in placed }
            if (remaining.isNotEmpty()) OptionSection(sub.displayName.string, remaining)
        } else {
            val grouped = LinkedHashMap<String?, MutableList<Option<*>>>()
            for (opt in options) {
                val path = opt.key().path()
                val group = if (path.size >= 4) path[2] else null
                grouped.getOrPut(group) { mutableListOf() }.add(opt)
            }
            for ((groupId, opts) in grouped) {
                val displayName =
                    if (groupId != null) {
                        val key = "text.config.soul/config.group.$activeCategory.$activeSubcategory.$groupId"
                        val translated = Component.translatable(key)
                        if (translated.string == key) formatGroupId(groupId) else translated.string
                    } else {
                        sub.displayName.string
                    }
                OptionSection(displayName, opts)
            }
        }
    }

    @SoulComposable
    private fun OptionSection(
        label: String,
        options: List<Option<*>>,
    ) {
        SectionLabel(label)
        // Inset card grouping the rows. Toggle / slider widget tracks paint `panelHover`
        // (slightly brighter than `panelInset`) so they stay visible against this bg.
        Surface(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            color = SoulTheme.colors.panelInset,
            radius = SoulTheme.dimens.radiusMedium,
            padding = 8f,
        ) {
            Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 2f) {
                for (opt in options) OptionRow(opt)
            }
        }
    }

    @SoulComposable
    private fun ActionSection(
        label: String,
        actions: List<ActionRowSpec>,
    ) {
        SectionLabel(label)
        Surface(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            color = SoulTheme.colors.panelInset,
            radius = SoulTheme.dimens.radiusMedium,
            padding = 8f,
        ) {
            Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 2f) {
                for (action in actions) ActionRow(action)
            }
        }
    }

    @SoulComposable
    private fun LinkSection(
        label: String,
        links: List<LinkTarget>,
    ) {
        SectionLabel(label)
        // Same Surface as option/action sections so a link row is visually a sibling row in
        // the same kind of card, not a standalone full-width chip.
        Surface(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            color = SoulTheme.colors.panelInset,
            radius = SoulTheme.dimens.radiusMedium,
            padding = 8f,
        ) {
            Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 2f) {
                for (link in links) LinkRow(link)
            }
        }
    }

    @SoulComposable
    private fun SectionLabel(label: String) {
        Text(
            text = label,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textDim,
            font = SoulTheme.typography.body.font,
            modifier = SoulModifier.Empty.padding(left = 4f, bottom = 4f),
        )
    }

    @SoulComposable
    private fun ActionRow(action: ActionRowSpec) {
        Row(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .padding(left = 8f, right = 6f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = VerticalAlignment.Center,
            gap = 8f,
        ) {
            Text(
                text = action.label,
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.body.font,
            )
            Button(
                label = action.buttonText,
                onClick = { action.action(this) },
                key = "config.action.${action.label}",
            )
        }
    }

    @SoulComposable
    private fun LinkRow(link: LinkTarget) {
        val key = "config.link.${link.targetCat}.${link.targetSub}"
        val hovered = SoulInput.isHovered(key)
        // Transparent by default — sits on the section's `panelInset` card like option rows.
        // Hover paints a translucent overlay so the row reads as interactive.
        val textColor = if (hovered) SoulTheme.colors.accent else SoulTheme.colors.text
        val arrowColor = if (hovered) SoulTheme.colors.accent else SoulTheme.colors.textDim
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .let { if (hovered) it.background(SoulTheme.colors.panelHover, SoulTheme.dimens.radiusSmall) else it }
                    .clickable(key) { navigateTo(link.targetCat, link.targetSub) },
        ) {
            Row(
                modifier = SoulModifier.Empty.fillMaxSize().padding(left = 8f, right = 6f),
                verticalAlignment = VerticalAlignment.Center,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = link.label,
                    size = SoulTheme.typography.body.size,
                    color = textColor,
                    font = SoulTheme.typography.body.font,
                )
                Text(
                    text = "›",
                    size = SoulTheme.typography.body.size,
                    color = arrowColor,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }

    @SoulComposable
    private fun DevBanner() {
        Text(
            text = Component.translatable("text.config.soul/config.dev.warning").string,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textDim,
            font = SoulTheme.typography.body.font,
            modifier = SoulModifier.Empty.padding(top = 4f, bottom = 4f, left = 4f),
        )
    }

    @SoulComposable
    private fun DimText(text: String) {
        Text(
            text = text,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textDim,
            font = SoulTheme.typography.body.font,
        )
    }

    // ─────────────────────────────── option rows ─────────────────────────────────────────

    @SoulComposable
    private fun OptionRow(opt: Option<*>) {
        val pathKey = opt.key().path().joinToString(".")
        val isDependent = pathKey in ConfigSections.optionVisibility
        val depth = if (isDependent) (ConfigSections.optionDepth[pathKey] ?: 1) else 0
        Row(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(ROW_HEIGHT)
                    .padding(left = 8f, right = 6f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = VerticalAlignment.Center,
            gap = 8f,
        ) {
            // Label cluster on the left — optional `↳` prefix when this option is gated by
            // a parent toggle, plus extra indent for chained dependents (e.g. logRealtime is
            // gated by logBackend which is itself gated by debugMode → depth 2 → 16px more).
            Row(gap = 6f, verticalAlignment = VerticalAlignment.Center) {
                if (isDependent) {
                    val extraLeft = (depth - 1) * 16f
                    if (extraLeft > 0f) Spacer(modifier = SoulModifier.Empty.width(extraLeft))
                    Text(
                        text = "↳",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.body.font,
                    )
                }
                Text(
                    text = Component.translatable(opt.translationKey()).string,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.body.font,
                )
            }
            // Controls clustered on the right (value text where useful, the editor, reset chip).
            Row(gap = 8f, verticalAlignment = VerticalAlignment.Center) {
                OptionControl(opt, pathKey)
                ResetSlot(opt, pathKey)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @SoulComposable
    private fun OptionControl(
        opt: Option<*>,
        pathKey: String,
    ) {
        when (val v = opt.value()) {
            is Boolean -> {
                Toggle(
                    value = v,
                    onChange = { newVal ->
                        (opt as Option<Boolean>).set(newVal)
                        save()
                    },
                    key = "config.toggle.$pathKey",
                )
            }
            is Int -> NumericSlider(opt as Option<Number>, pathKey, integer = true)
            is Long -> NumericSlider(opt as Option<Number>, pathKey, integer = true)
            is Float, is Double -> NumericSlider(opt as Option<Number>, pathKey, integer = false)
            is String -> {
                if (pathKey in ConfigSections.keybindOptions) {
                    KeybindButton(opt as Option<String>, pathKey)
                } else {
                    TextField(
                        value = v,
                        onChange = { newVal ->
                            (opt as Option<String>).set(newVal)
                            save()
                        },
                        modifier = SoulModifier.Empty.width(160f),
                        key = "config.text.$pathKey",
                    )
                }
            }
            else ->
                Text(
                    text = v.toString(),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
        }
    }

    @Suppress("UNCHECKED_CAST")
    @SoulComposable
    private fun NumericSlider(
        opt: Option<Number>,
        pathKey: String,
        integer: Boolean,
    ) {
        val field = wrapper.fieldForKey(opt.key())
        val rc = field?.getAnnotation(RangeConstraint::class.java)
        val min = (rc?.min ?: 0.0).toFloat()
        val max = (rc?.max ?: 100.0).toFloat()
        val decimals = if (integer) 0 else (rc?.decimalPlaces ?: 2)
        val current = opt.value().toFloat()

        Row(gap = 8f, verticalAlignment = VerticalAlignment.Center) {
            Text(
                text = formatNumber(current.toDouble(), decimals),
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
            Slider(
                value = current,
                onChange = { newVal ->
                    when (opt.value()) {
                        is Int -> (opt as Option<Int>).set(newVal.toInt())
                        is Long -> (opt as Option<Long>).set(newVal.toLong())
                        is Float -> (opt as Option<Float>).set(newVal)
                        is Double -> (opt as Option<Double>).set(newVal.toDouble())
                        else -> {}
                    }
                    save()
                },
                min = min,
                max = max,
                modifier = SoulModifier.Empty.width(140f),
                key = "config.slider.$pathKey",
            )
        }
    }

    @SoulComposable
    private fun KeybindButton(
        opt: Option<String>,
        pathKey: String,
    ) {
        val capturing = capturingKeybind === opt
        val label = if (capturing) "> Press a key <" else keybindLabel(opt.value())
        Button(
            label = label,
            onClick = {
                capturingKeybind = if (capturing) null else opt
            },
            key = "config.keybind.$pathKey",
        )
    }

    @SoulComposable
    private fun ResetSlot(
        opt: Option<*>,
        pathKey: String,
    ) {
        // Fixed-width slot so rows line up whether the reset chip is present or not.
        Box(modifier = SoulModifier.Empty.width(20f).height(16f)) {
            if (opt.value() != opt.defaultValue()) ResetButton(opt, pathKey)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @SoulComposable
    private fun ResetButton(
        opt: Option<*>,
        pathKey: String,
    ) {
        val key = "config.reset.$pathKey"
        val hovered = SoulInput.isHovered(key)
        val bg = if (hovered) SoulTheme.colors.panelHover else SoulTheme.colors.panelInset
        val textColor = if (hovered) SoulTheme.colors.accent else SoulTheme.colors.textDim
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxSize()
                    .background(color = bg, radius = SoulTheme.dimens.radiusSmall)
                    .clickable(key) {
                        val default = opt.defaultValue()
                        (opt as Option<Any>).set(default as Any)
                        save()
                    },
        ) {
            Column(
                modifier = SoulModifier.Empty.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Text(
                    text = "↺",
                    size = SoulTheme.typography.body.size,
                    color = textColor,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }

    // ─────────────────────────────── dividers / helpers ──────────────────────────────────

    @SoulComposable
    private fun HDivider() {
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(1f)
                    .background(color = SoulTheme.colors.separator, radius = 0f),
        ) { Spacer() }
    }

    @SoulComposable
    private fun VDivider(height: Float) {
        Box(
            modifier =
                SoulModifier.Empty
                    .width(1f)
                    .height(height)
                    .background(color = SoulTheme.colors.separator, radius = 0f),
        ) { Spacer() }
    }

    private fun formatGroupId(id: String): String =
        // "playerRendering" → "Player Rendering", "tooltips" → "Tooltips"
        id.replace(Regex("([A-Z])"), " $1")
            .replaceFirstChar { it.uppercase() }
            .trim()

    private fun keybindLabel(stored: String): String {
        if (stored.isBlank()) return "Not bound"
        return try {
            com.mojang.blaze3d.platform.InputConstants.getKey(stored).getDisplayName().string
        } catch (_: Throwable) {
            stored
        }
    }

    private fun formatNumber(
        v: Double,
        decimals: Int,
    ): String =
        if (decimals <= 0) {
            v.toInt().toString()
        } else {
            String.format(Locale.ROOT, "%.${decimals}f", v)
        }

    private fun filteredCategories(): List<CategoryEntry> = ConfigSearchFilter.filter(categories, searchQuery)

    // ─────────────────────────────── ConfigScreenContext ─────────────────────────────────

    override fun save() {
        try {
            wrapper.save()
        } catch (_: Throwable) {
            // owo-config writes can throw if the file path is read-only or the disk is full;
            // we surface nothing because the user already sees the UI go stale, and the
            // SoulLogger error from owo is already in the logs.
        }
    }

    override fun rebuildContentBody() {
        // No-op — declarative model, next frame picks up state changes.
    }

    override fun rebuildContent() {
        // No-op — same as above.
    }

    override fun navigateTo(
        catId: String,
        subId: String,
    ) {
        activeCategory = catId
        activeSubcategory = subId
        expandedCategories.add(catId)
        bodyScroll = 0f
    }

    override fun reloadConfig() {
        try {
            wrapper.load()
        } catch (_: Throwable) {
        }
    }

    override fun requestKeybindCapture(opt: Option<String>) {
        capturingKeybind = if (capturingKeybind === opt) null else opt
    }

    override fun isCapturing(opt: Option<String>): Boolean = capturingKeybind === opt
}
