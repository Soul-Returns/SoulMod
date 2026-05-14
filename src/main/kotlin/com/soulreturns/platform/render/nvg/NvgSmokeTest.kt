package com.soulreturns.platform.render.nvg

import com.soulreturns.config.cfg
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.ScrollableList
import com.soulreturns.ui.foundation.Slider
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Tabs
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.foundation.Toggle
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import com.soulreturns.util.SoulLogger
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.roundToInt

/**
 * Temporary scaffolding — registers a SoulHud that exercises every framework primitive.
 * Open an inventory or other container screen to interact (input only fires on those).
 *
 * Hidden unless `cfg.dev.debug.debugMode` is on — the entire HUD's `enabled` flag flips
 * with the debug toggle so it doesn't leak into normal play.
 *
 * Remove once real consumers exist (HUD migrations in P3).
 */
object NvgSmokeTest {
    private val logger = SoulLogger("Soul/SmokeTest")
    private const val HUD_ID = "smoke_test"

    @Volatile private var clickCounter: Int = 0

    @Volatile private var toggleValue: Boolean = false

    @Volatile private var sliderValue: Float = 0.5f

    @Volatile private var selectedTab: Int = 0

    @Volatile private var scrollOffset: Float = 0f

    private val listItems = (1..20).map { "Item #$it — sample row" }

    /** Called once from `Soul.registerFeatures()` so the HUD layout slot exists at startup. */
    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 320,
            height = 320,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.02,
        ) {
            // The composable rebuilds every frame — state changes show up immediately.
            // Reads `cfg.dev.debug.debugMode` so the surface only paints in debug mode;
            // the HUD's `enabled` flag could do the same, but composing-to-empty is cheaper
            // than toggling layout state.
            if (!cfg.dev.debug.debugMode()) {
                // Compose an invisible 1×1 placeholder so the root has exactly one child.
                Column { Text("", color = 0x00000000) }
                return@register
            }
            Surface(modifier = SoulModifier.Empty.fillMaxWidth()) {
                Column(gap = 8f, modifier = SoulModifier.Empty.fillMaxWidth()) {
                    Text(
                        text = "Soul UI framework",
                        size = SoulTheme.typography.heading.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.heading.font,
                    )
                    Tabs(
                        options = listOf("Session", "Total", "Festival"),
                        selectedIndex = selectedTab,
                        onSelect = { selectedTab = it },
                        modifier = SoulModifier.Empty.fillMaxWidth(),
                    )
                    Row(
                        modifier = SoulModifier.Empty.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Enable feature", color = SoulTheme.colors.textDim)
                        Toggle(value = toggleValue, onChange = { toggleValue = it })
                    }
                    Row(
                        modifier = SoulModifier.Empty.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        gap = 8f,
                    ) {
                        Text(
                            "Volume ${(sliderValue * 100f).roundToInt()}",
                            color = SoulTheme.colors.textDim,
                        )
                        Slider(
                            value = sliderValue,
                            onChange = { sliderValue = it },
                            modifier = SoulModifier.Empty.width(140f),
                        )
                    }
                    Row(
                        modifier = SoulModifier.Empty.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Clicks", color = SoulTheme.colors.textDim)
                        Text("$clickCounter", color = SoulTheme.colors.accent)
                    }
                    Row(
                        modifier = SoulModifier.Empty.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        gap = 6f,
                    ) {
                        Button(label = "Reset", onClick = { clickCounter = 0 })
                        Button(
                            label = "Click me",
                            onClick = {
                                clickCounter++
                                logger.info("Click me (now $clickCounter)")
                            },
                            accent = true,
                        )
                    }
                    Text(
                        "Scrollable (wheel over the list):",
                        size = SoulTheme.typography.caption.size,
                        color = SoulTheme.colors.textFaint,
                    )
                    ScrollableList(
                        scrollOffset = scrollOffset,
                        onScroll = { newOffset -> scrollOffset = newOffset },
                        modifier =
                            SoulModifier.Empty
                                .fillMaxWidth()
                                .height(80f)
                                .padding(4f),
                        gap = 2f,
                    ) {
                        listItems.forEach { item ->
                            Text(item, color = SoulTheme.colors.text)
                        }
                    }
                }
            }
        }
    }

    /** Legacy entrypoint kept for compatibility with the previous render hook — now a no-op. */
    @Suppress("UNUSED_PARAMETER")
    fun render(context: GuiGraphics) {
        // SoulHud.dispatchAll now handles rendering. Kept to preserve the existing call
        // site in SoulGuiHudAdapter during the transitional commit; safe to delete on next
        // refactor pass.
    }
}
