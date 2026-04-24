package com.soulreturns.profileviewer.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.profileviewer.api.MojangApi
import com.soulreturns.profileviewer.model.SkyblockProfile
import com.soulreturns.profileviewer.model.SkyblockProfilesResponse
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.UUID

class ProfileViewerScreen(
    private val name: String,
    uuid: UUID,
    private val response: SkyblockProfilesResponse,
    initial: SkyblockProfile,
) : BaseOwoScreen<FlowLayout>(Text.literal("Soul Profile Viewer — $name")) {

    private var current: SkyblockProfile = initial
    private val uuidUndashed = MojangApi.toUndashed(uuid)
    private var activeTab = "dungeons"
    private lateinit var bodyContainer: FlowLayout
    private var profileNameLabel: LabelComponent? = null

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { hSize, vSize -> UIContainers.verticalFlow(hSize, vSize) }

    override fun build(root: FlowLayout) {
        root.surface(Theme.backgroundSurface)
        root.horizontalAlignment(HorizontalAlignment.CENTER)
        root.verticalAlignment(VerticalAlignment.CENTER)

        val card = UIContainers.verticalFlow(Sizing.fill(85), Sizing.fill(90))
        card.surface(Theme.panelSurface)
        card.padding(Insets.of(0))

        card.child(buildHeader())
        card.child(horizontalDivider())
        card.child(buildTabBar())
        card.child(horizontalDivider())

        val scrollBody = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        scrollBody.padding(Insets.of(12, 16, 16, 16))
        scrollBody.gap(8)
        bodyContainer = scrollBody

        val scroll = UIContainers.verticalScroll(Sizing.fill(100), Sizing.fill(100), scrollBody)
        scroll.scrollbarThiccness(4)
        card.child(scroll)

        root.child(card)
        rebuildBody()
    }

    private fun buildHeader(): FlowLayout {
        val header = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        header.padding(Insets.of(14, 16, 14, 20))
        header.verticalAlignment(VerticalAlignment.CENTER)
        header.gap(8)

        header.child(
            UIComponents.label(Text.literal(name))
                .color(Theme.color(Theme.TEXT))
        )

        if (response.profiles.size > 1) {
            header.child(verticalSeparator())
            header.child(cycleButton("◀") { cycleProfile(-1) })
            val lbl = UIComponents.label(Text.literal(profileDisplay(current)))
                .color(Theme.color(Theme.TEXT_DIM))
            profileNameLabel = lbl
            header.child(lbl)
            header.child(cycleButton("▶") { cycleProfile(1) })
        }

        return header
    }

    private fun buildTabBar(): FlowLayout {
        val bar = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        bar.padding(Insets.of(6, 16, 4, 16))
        bar.gap(4)
        bar.verticalAlignment(VerticalAlignment.CENTER)
        bar.child(tabButton("Dungeons", "dungeons"))
        return bar
    }

    private fun tabButton(label: String, id: String): ButtonComponent {
        val btn = UIComponents.button(Text.empty()) {
            if (activeTab != id) { activeTab = id; rebuildBody() }
        }
        btn.horizontalSizing(Sizing.fixed(90))
        btn.verticalSizing(Sizing.fixed(26))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val selected = activeTab == id
            when {
                selected -> DrawContextRenderer.roundedFill(
                    ctx, button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.ACCENT, Theme.ITEM_RADIUS
                )
                button.isHovered -> DrawContextRenderer.roundedFill(
                    ctx, button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS
                )
            }
            val tr = MinecraftClient.getInstance().textRenderer
            val tx = button.x + (button.width - tr.getWidth(label)) / 2
            val ty = button.y + (button.height - tr.fontHeight) / 2
            ctx.drawText(tr, Text.literal(label), tx, ty, if (selected) Theme.TEXT else Theme.TEXT_DIM, false)
        })
        return btn
    }

    private fun cycleProfile(delta: Int) {
        val idx = response.profiles.indexOf(current).coerceAtLeast(0)
        val size = response.profiles.size
        current = response.profiles[((idx + delta) % size + size) % size]
        profileNameLabel?.text(Text.literal(profileDisplay(current)))
        rebuildBody()
    }

    private fun rebuildBody() {
        bodyContainer.clearChildren()
        val member = current.memberFor(uuidUndashed)
        if (member == null) {
            bodyContainer.child(
                UIComponents.label(Text.literal("No data for this player on profile ${current.cuteName}."))
                    .color(Theme.color(Theme.TEXT_DIM))
            )
            return
        }
        bodyContainer.child(DungeonsTab.build(member))
    }

    private fun profileDisplay(p: SkyblockProfile): String {
        val mode = p.gameMode?.takeIf { it.isNotBlank() && it != "normal" }
        val active = if (p.selected) " (active)" else ""
        val modeTag = mode?.let { " [$it]" } ?: ""
        return "${p.cuteName}$modeTag$active"
    }

    private fun cycleButton(icon: String, action: () -> Unit): ButtonComponent {
        val btn = UIComponents.button(Text.empty()) { action() }
        btn.horizontalSizing(Sizing.fixed(20))
        btn.verticalSizing(Sizing.fixed(20))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.PANEL_HOVER else Theme.PANEL_INSET
            DrawContextRenderer.roundedFill(ctx, button.x, button.y, button.x + button.width, button.y + button.height, bg, Theme.ITEM_RADIUS)
            val tr = MinecraftClient.getInstance().textRenderer
            val tx = button.x + (button.width - tr.getWidth(icon)) / 2
            val ty = button.y + (button.height - tr.fontHeight) / 2
            ctx.drawText(tr, Text.literal(icon), tx, ty, Theme.TEXT_DIM, false)
        })
        return btn
    }

    private fun verticalSeparator(): FlowLayout {
        val s = UIContainers.verticalFlow(Sizing.fixed(1), Sizing.fixed(14))
        s.surface(Surface.flat(Theme.SEPARATOR))
        s.margins(Insets.horizontal(4))
        return s
    }

    private fun horizontalDivider(): FlowLayout {
        val d = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(1))
        d.surface(Surface.flat(Theme.SEPARATOR))
        return d
    }
}