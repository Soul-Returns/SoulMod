package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.features.fishing.BobbinSpotter
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.Minecraft
import java.util.Locale

/**
 * Bobbin Time HUD — reads [BobbinSpotter.nearbyBobbers] each frame and renders a small panel.
 *
 * The spotter must register *before* this HUD so the count read here is from the current
 * tick, not the previous one. [com.soulreturns.Soul.registerFeatures] enforces the order.
 *
 * Migrated from legacy `GuiLayoutApi.updateTextBlock` to [SoulHud.register] per the P3
 * framework migration (`docs/ui-framework-roadmap.md`).
 */
object BobbinHud {
    private const val HUD_ID = "bobbin_time_counter"
    private const val ACCENT_CYAN = 0xFF00FFFF.toInt()

    /** Skyblock enchant id that enables this HUD when present on any armor piece. */
    private const val ENCHANT_ID = "ultimate_bobbin_time"

    /**
     * Bonus multiplier per enchant level — see SkyHanni `LegionBobbinOverlay.kt`. Final boost
     * = level × [BOOST_PER_LEVEL] × min(nearbyBobbers, [BOBBER_CAP]).
     */
    private const val BOOST_PER_LEVEL = 0.2
    private const val BOBBER_CAP = 5

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 180,
            height = 60,
            // Top-center default. Shares its anchor with Legion (only one can render at a
            // time — they're gated on mutually-exclusive armor enchants), so this slot is
            // safe to overload. `horizontalAnchor = Center` lets `dispatchAll` pivot on the
            // actual rendered width × effective scale, so the panel sits dead-center on any
            // resolution. `anchorY = 0.08` (≈ 8 % of screen height) leaves room above for
            // the vanilla boss bar.
            defaultAnchorX = 0.5,
            defaultAnchorY = 0.08,
            defaultHorizontalAnchor = HudHorizontalAnchor.Center,
            settingsCategory = "fishing",
            settingsSubcategory = "bobbinTime",
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        if (!cfg.fishing.bobbinTime.enableBobbinTimeCounter()) {
            Box {}
            return
        }
        val player = Minecraft.getInstance().player
        val enchantLevel = SkyblockItemUtils.highestArmorEnchantLevel(player, ENCHANT_ID)
        if (enchantLevel <= 0) {
            // HUD only renders when the player is actually wearing the Bobbin Time enchant.
            Box {}
            return
        }
        val bobbers = BobbinSpotter.nearbyBobbers
        val cappedBobbers = bobbers.coerceAtMost(BOBBER_CAP)
        val boostPercent = enchantLevel * BOOST_PER_LEVEL * cappedBobbers
        Surface(
            color = if (SoulHud.shouldDrawBackground(HUD_ID)) SoulTheme.colors.panel else 0x00000000,
        ) {
            Column(gap = 4f) {
                Text(
                    text = "Bobbin Time",
                    size = SoulTheme.typography.heading.size,
                    color = ACCENT_CYAN,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text =
                        String.format(
                            Locale.ROOT,
                            "Nearby bobbers: %d (%.2f%%)",
                            bobbers,
                            boostPercent,
                        ),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }
}
