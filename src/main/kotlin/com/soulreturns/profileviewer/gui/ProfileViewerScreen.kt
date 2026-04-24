package com.soulreturns.profileviewer.gui

import com.soulreturns.profileviewer.api.MojangApi
import com.soulreturns.profileviewer.model.SkyblockProfile
import com.soulreturns.profileviewer.model.SkyblockProfilesResponse
import io.wispforest.owo.ui.base.BaseOwoScreen
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
    private lateinit var bodyContainer: FlowLayout
    private lateinit var profileLabel: LabelComponent

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { hSize, vSize ->
            UIContainers.verticalFlow(hSize, vSize)
        }

    override fun build(root: FlowLayout) {
        root.gap(8)
        root.padding(Insets.of(12))
        root.surface(Surface.VANILLA_TRANSLUCENT)
        root.horizontalAlignment(HorizontalAlignment.CENTER)
        root.verticalAlignment(VerticalAlignment.TOP)

        // Header: player name + profile selector
        val header = UIContainers.horizontalFlow(Sizing.fill(90), Sizing.content())
        header.gap(8)
        header.padding(Insets.of(6))
        header.surface(Surface.DARK_PANEL)
        header.verticalAlignment(VerticalAlignment.CENTER)
        header.horizontalAlignment(HorizontalAlignment.CENTER)

        header.child(UIComponents.label(Text.literal("§l$name")))
        header.child(UIComponents.label(Text.literal("§7|")))

        header.child(
            UIComponents.button(Text.literal("◀")) { cycleProfile(-1) }
                .sizing(Sizing.fixed(20), Sizing.fixed(20))
        )
        profileLabel = UIComponents.label(Text.literal(profileDisplay(current)))
        header.child(profileLabel)
        header.child(
            UIComponents.button(Text.literal("▶")) { cycleProfile(1) }
                .sizing(Sizing.fixed(20), Sizing.fixed(20))
        )

        root.child(header)

        // Tab strip — only Dungeons for now
        val tabs = UIContainers.horizontalFlow(Sizing.fill(90), Sizing.content())
        tabs.gap(4)
        tabs.horizontalAlignment(HorizontalAlignment.CENTER)
        tabs.child(
            UIComponents.button(Text.literal("Dungeons")) { /* only tab */ }
                .sizing(Sizing.fixed(80), Sizing.fixed(20))
        )
        root.child(tabs)

        // Body: scrollable container that we replace contents of when profile changes
        bodyContainer = UIContainers.verticalFlow(Sizing.fill(90), Sizing.fill(70))
        bodyContainer.gap(6)
        bodyContainer.padding(Insets.of(8))
        bodyContainer.surface(Surface.DARK_PANEL)
        bodyContainer.horizontalAlignment(HorizontalAlignment.CENTER)

        root.child(UIContainers.verticalScroll(Sizing.fill(95), Sizing.fill(75), bodyContainer))

        rebuildBody()
    }

    private fun cycleProfile(delta: Int) {
        if (response.profiles.size < 2) return
        val idx = response.profiles.indexOf(current).coerceAtLeast(0)
        val size = response.profiles.size
        val next = response.profiles[((idx + delta) % size + size) % size]
        current = next
        profileLabel.text(Text.literal(profileDisplay(current)))
        rebuildBody()
    }

    private fun rebuildBody() {
        bodyContainer.clearChildren()
        val member = current.memberFor(uuidUndashed)
        if (member == null) {
            bodyContainer.child(
                UIComponents.label(Text.literal("§cNo data for this player on profile §f${current.cuteName}§c."))
            )
            return
        }
        bodyContainer.child(DungeonsTab.build(member))
    }

    private fun profileDisplay(p: SkyblockProfile): String {
        val mode = p.gameMode?.takeIf { it.isNotBlank() && it != "normal" }
        val tag = if (p.selected) " §a(active)" else ""
        val modeTag = mode?.let { " §7[$it]" } ?: ""
        return "§f${p.cuteName}$modeTag$tag"
    }
}