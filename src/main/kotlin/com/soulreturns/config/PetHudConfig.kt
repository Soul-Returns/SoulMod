package com.soulreturns.config

import com.soulreturns.ui.hud.movable.HudAnchor
import com.soulreturns.ui.hud.movable.HudLink
import com.soulreturns.ui.hud.movable.HudPlace
import com.soulreturns.ui.hud.movable.HudTargets
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.VanillaIds
import kotlinx.serialization.Serializable

/**
 * The Pet HUD ([com.soulreturns.features.pets.PetHud], spec `mocks/pet-hud/`). Every element is its own
 * toggle; the name always shows. Cycle values are the Ints in [com.soulreturns.features.pets.PetHudFormats].
 */
@Serializable
data class PetHudConfig(
    /** Show the active pet: icon, name in its rarity color, level and XP. */
    val enabled: Boolean = false,
    /** 0 Card (default), 1 Compact line. */
    val style: Int = 0,
    /** Without a pet: 0 show "No pet" (default), 1 hide. */
    val noPet: Int = 0,
    // Simple elements
    val icon: Boolean = true,
    val level: Boolean = true,
    val xpBar: Boolean = true,
    val percent: Boolean = true,
    /** Card only. */
    val heldItem: Boolean = true,
    val xpPerHour: Boolean = true,
    // Advanced elements
    /** XP numbers (cur / need). Card only. */
    val xpNumbers: Boolean = true,
    val timeToLevel: Boolean = true,
    /** Overflow XP at max level. Card only. */
    val overflow: Boolean = true,
    val rarityName: Boolean = true,
    val skinStar: Boolean = true,
    /** Progress to max level. Card only. */
    val progressToMax: Boolean = false,
    val badges: Boolean = true,
    val animations: Boolean = true,
    /** 0 short (`196.2k`), 1 full (`196,209`). Card only. */
    val numberFormat: Int = 0,
    /** XP bar color: 0 Fixed ([barColor]), 1 the pet's rarity. */
    val barMode: Int = 0,
    /** Fixed bar color, Soul blue; chroma sweeps the rainbow along the bar. */
    val barColor: ChromaColor = ChromaColor(0xFF3B82F6.toInt()),
    /** Mark the summoned pet in the Pets menu (its lore has `Click to despawn!`). Not part of the HUD. */
    val highlightActive: Boolean = false,
    /** Mark color for [highlightActive]: a teal no SkyBlock rarity uses (pet names are rarity colored). */
    val highlightColor: ChromaColor = ChromaColor(0xFF00C8A0.toInt()),
    /** Card behind the HUD (Move GUI toggle). */
    val background: Boolean = true,
    /** Card fill (alpha = opacity; chroma supported). */
    val backgroundColor: ChromaColor = ChromaColor(0x90000000.toInt()),
    /** Move-GUI transform. Default: right of the hotbar, bottom edges aligned, 6 px away. */
    val hud: HudTransform =
        HudTransform(
            hAnchor = HudAnchor.CENTER,
            vAnchor = HudAnchor.END,
            offsetX = 160f,
            link = HudLink(
                HudTargets.vanilla(VanillaIds.HOTBAR),
                h = HudPlace.OUT_END,
                v = HudPlace.END,
                offsetX = 6f,
                follow = true,
                auto = false,
            ),
        ),
)
