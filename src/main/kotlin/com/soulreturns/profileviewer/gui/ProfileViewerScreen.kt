package com.soulreturns.profileviewer.gui

import com.soulreturns.profileviewer.gui.tabs.DungeonsTab
import com.soulreturns.profileviewer.model.SkyblockProfile
import com.soulreturns.profileviewer.model.SkyblockProfilesResponse
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

class ProfileViewerScreen(
    private val playerName: String,
    private val playerUuid: UUID,
    private val response: SkyblockProfilesResponse,
    initial: SkyblockProfile,
) : Screen(Text.literal("Soul Profile Viewer")) {

    private var currentProfile: SkyblockProfile = initial
    private val playerUuidUndashed: String = playerUuid.toString().replace("-", "")

    private val tabs: List<ProfileViewerTab> = listOf(
        DungeonsTab { currentProfile to playerUuidUndashed },
    )
    private var activeTabIdx: Int = 0

    private var prevProfileButton: ButtonWidget? = null
    private var nextProfileButton: ButtonWidget? = null
    private val tabButtons = mutableListOf<ButtonWidget>()

    override fun init() {
        tabButtons.clear()
        // Profile chevron buttons appear in the header.
        val arrowsY = 26
        prevProfileButton = ButtonWidget.builder(Text.literal("<")) {
            switchProfile(-1)
        }.dimensions(20, arrowsY, 20, 20).build().also { addDrawableChild(it) }

        nextProfileButton = ButtonWidget.builder(Text.literal(">")) {
            switchProfile(+1)
        }.dimensions(width - 40, arrowsY, 20, 20).build().also { addDrawableChild(it) }

        // Tab strip buttons under the header.
        val tabY = 56
        var x = 12
        tabs.forEachIndexed { idx, tab ->
            val btn = ButtonWidget.builder(tab.label) {
                activeTabIdx = idx
            }.dimensions(x, tabY, 90, 20).build()
            addDrawableChild(btn)
            tabButtons.add(btn)
            x += 94
        }
        updateProfileButtons()
    }

    private fun switchProfile(delta: Int) {
        if (response.profiles.isEmpty()) return
        val idx = response.profiles.indexOf(currentProfile).coerceAtLeast(0)
        val newIdx = ((idx + delta) % response.profiles.size + response.profiles.size) % response.profiles.size
        currentProfile = response.profiles[newIdx]
        updateProfileButtons()
    }

    private fun updateProfileButtons() {
        val multi = response.profiles.size > 1
        prevProfileButton?.active = multi
        nextProfileButton?.active = multi
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        // Header background
        context.fill(0, 0, width, 50, 0xC0101015.toInt())
        context.fill(0, 50, width, 51, 0xFF2A2A33.toInt())

        // Title (player name)
        context.drawText(textRenderer, Text.literal(playerName).formatted(Formatting.WHITE), 16, 8, 0xFFFFFFFF.toInt(), true)

        // Profile name + game mode + selected indicator
        val gm = currentProfile.gameMode?.let { " §8[§e${it.replaceFirstChar { c -> c.uppercase() }}§8]" } ?: ""
        val sel = if (currentProfile.selected) " §a✓" else ""
        val profileLine = "§7Profile: §f${currentProfile.cuteName}$gm$sel  §8(${response.profiles.size} total)"
        context.drawText(textRenderer, Text.literal(profileLine), 16, 30, 0xFFAAAAAA.toInt(), true)

        // Highlight active tab.
        tabButtons.forEachIndexed { i, btn ->
            if (i == activeTabIdx) {
                context.fill(btn.x - 1, btn.y + btn.height, btn.x + btn.width + 1, btn.y + btn.height + 2, 0xFFFFAA00.toInt())
            }
        }

        // Active tab body.
        val bodyY = 80
        val area = TabArea(8, bodyY, width - 16, height - bodyY - 8)
        tabs.getOrNull(activeTabIdx)?.render(context, mouseX, mouseY, delta, area)
    }

    override fun shouldPause(): Boolean = false
}
