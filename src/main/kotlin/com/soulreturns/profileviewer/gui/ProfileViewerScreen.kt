package com.soulreturns.profileviewer.gui

import com.soulreturns.profileviewer.api.MojangApi
import com.soulreturns.profileviewer.model.SkyblockProfile
import com.soulreturns.profileviewer.model.SkyblockProfilesResponse
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.HorizontalAlignment
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.clickable
import com.soulreturns.ui.composer.fillMaxSize
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.ScrollableList
import com.soulreturns.ui.foundation.Spacer
import com.soulreturns.ui.foundation.Tabs
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.runtime.SoulScreen
import com.soulreturns.ui.theme.SoulTheme
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Profile viewer screen — rebuilt on the Soul UI framework (P4.2 migration).
 *
 * Layout: centered card on a dark page backdrop. The card holds, top-to-bottom:
 *   - Header: player name + (when there are multiple profiles) ◀ / current-profile / ▶ pager.
 *   - Tab strip — currently a single tab (Dungeons) so the bar is functional but minimal.
 *   - Scrollable body — the active tab's content. Internal padding handled by [ScrollableList].
 *
 * State is held as `@Volatile` fields and re-read every frame by [Content] — no manual
 * `rebuildBody()` call needed; switching profiles or tabs just mutates state and the next
 * frame picks up the new composition.
 */
class ProfileViewerScreen(
    private val name: String,
    uuid: UUID,
    private val response: SkyblockProfilesResponse,
    initial: SkyblockProfile,
) : SoulScreen(Component.literal("Soul Profile Viewer — $name")) {
    private val uuidUndashed = MojangApi.toUndashed(uuid)

    @Volatile private var current: SkyblockProfile = initial

    @Volatile private var activeTabIndex: Int = 0

    @Volatile private var bodyScrollOffset: Float = 0f

    private val tabLabels = listOf("Dungeons")

    override fun shouldCloseOnEsc(): Boolean = true

    override fun blurBackground(): Boolean = true

    @SoulComposable
    override fun Content() {
        // Card sizing — clamp to a readable band so the UI doesn't stretch to absurd widths
        // on ultrawide screens or collapse on tiny ones. Heights are similarly capped so the
        // scrollable body always has a sensible viewport.
        val cardW = (width * 0.85f).coerceIn(360f, 760f)
        val cardH = (height * 0.9f).coerceIn(320f, 560f)
        val headerH = 40f
        val tabBarH = 32f
        val dividerH = 1f
        val bodyH = (cardH - headerH - tabBarH - dividerH * 2f).coerceAtLeast(120f)

        Column(
            modifier = SoulModifier.Empty.fillMaxSize().background(SoulTheme.colors.background),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Box(
                modifier =
                    SoulModifier.Empty
                        .width(cardW)
                        .height(cardH)
                        .background(color = SoulTheme.colors.panel, radius = SoulTheme.dimens.radiusMedium),
            ) {
                Column(modifier = SoulModifier.Empty.fillMaxSize()) {
                    Header(headerH)
                    HDivider()
                    TabBar(tabBarH)
                    HDivider()
                    ScrollableList(
                        scrollOffset = bodyScrollOffset,
                        onScroll = { bodyScrollOffset = it },
                        modifier =
                            SoulModifier.Empty
                                .fillMaxWidth()
                                .height(bodyH)
                                .padding(top = 12f, right = 16f, bottom = 16f, left = 16f),
                        gap = 8f,
                        key = "spv.body",
                    ) {
                        Body()
                    }
                }
            }
        }
    }

    @SoulComposable
    private fun Header(h: Float) {
        Row(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(h)
                    .padding(top = 14f, right = 16f, bottom = 14f, left = 20f),
            verticalAlignment = VerticalAlignment.Center,
            gap = 8f,
        ) {
            Text(
                text = name,
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.heading.font,
            )
            if (response.profiles.size > 1) {
                VSeparator()
                IconButton(label = "<", key = "spv.prev") { cycleProfile(-1) }
                Text(
                    text = profileDisplay(current),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
                IconButton(label = ">", key = "spv.next") { cycleProfile(1) }
            }
        }
    }

    @SoulComposable
    private fun TabBar(h: Float) {
        Row(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(h)
                    .padding(top = 6f, right = 16f, bottom = 4f, left = 16f),
            verticalAlignment = VerticalAlignment.Center,
            gap = 4f,
        ) {
            Tabs(
                options = tabLabels,
                selectedIndex = activeTabIndex,
                onSelect = { idx ->
                    if (idx != activeTabIndex) {
                        activeTabIndex = idx
                        bodyScrollOffset = 0f
                    }
                },
                keyPrefix = "spv.tabs",
            )
        }
    }

    @SoulComposable
    private fun Body() {
        val member = current.memberFor(uuidUndashed)
        if (member == null) {
            Text(
                text = "No data for this player on profile ${current.cuteName}.",
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.textDim,
                font = SoulTheme.typography.body.font,
            )
            return
        }
        when (activeTabIndex) {
            0 -> DungeonsTabContent(member)
            else ->
                Text(
                    text = "Unknown tab",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
        }
    }

    @SoulComposable
    private fun IconButton(
        label: String,
        key: Any,
        onClick: () -> Unit,
    ) {
        // Card background uses `panel`; the regular Button does too, so cycle arrows would
        // blend in. Use `panelInset` (darker) for resting state + `panelHover` on hover so
        // the chip stands out against the card.
        val hovered = SoulInput.isHovered(key)
        val bg = if (hovered) SoulTheme.colors.panelHover else SoulTheme.colors.panelInset
        Box(
            modifier =
                SoulModifier.Empty
                    .width(22f)
                    .height(20f)
                    .background(color = bg, radius = SoulTheme.dimens.radiusSmall)
                    .clickable(key, onClick),
        ) {
            Column(
                modifier = SoulModifier.Empty.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                Text(
                    text = label,
                    size = SoulTheme.typography.heading.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.heading.font,
                )
            }
        }
    }

    @SoulComposable
    private fun VSeparator() {
        Box(
            modifier =
                SoulModifier.Empty
                    .width(1f)
                    .height(14f)
                    .background(color = SoulTheme.colors.separator, radius = 0f),
        ) { Spacer() }
    }

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

    private fun cycleProfile(delta: Int) {
        val idx = response.profiles.indexOf(current).coerceAtLeast(0)
        val size = response.profiles.size
        current = response.profiles[((idx + delta) % size + size) % size]
        bodyScrollOffset = 0f
    }

    private fun profileDisplay(p: SkyblockProfile): String {
        val mode = p.gameMode?.takeIf { it.isNotBlank() && it != "normal" }
        val active = if (p.selected) " (active)" else ""
        val modeTag = mode?.let { " [$it]" } ?: ""
        return "${p.cuteName}$modeTag$active"
    }
}
