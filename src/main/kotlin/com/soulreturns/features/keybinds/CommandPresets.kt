package com.soulreturns.features.keybinds

import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.ui.hud.movable.HudMoveScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Hypixel menus (plus Soul's own screens) offered as ready-made command keybinds. Always listed, unbound by default; only the
 * key and on/off state are stored ([com.soulreturns.config.CommandKeybindsConfig.presets]), keyed by
 * [id]. Never rename an id: stored bindings and share codes refer to it.
 */
class CommandPreset(
    val id: String,
    val name: String,
    /** Without the leading slash, like [com.soulreturns.config.CommandBinding.command]. */
    val command: String,
    val purpose: String,
    /**
     * Set for a Soul screen: the press opens it directly (parent = the open screen) instead of
     * sending [command], and also fires over inventories and menus ([CommandKeybinds.opensOver]).
     */
    val screen: ((parent: Screen?) -> Screen)? = null,
    private val item: () -> Item,
) {
    val icon: ItemStack by lazy { ItemStack(item()) }
}

object CommandPresets {
    val all: List<CommandPreset> = listOf(
        CommandPreset("storage", "Storage", "storage", "Opens your Ender Chest pages and backpacks.") { Items.CHEST },
        CommandPreset("loadouts", "Loadouts", "loadouts", "Opens your saved loadouts.") { Items.ARMOR_STAND },
        CommandPreset("stats", "Stats", "stats", "For fast equipping anything.") { Items.WRITABLE_BOOK },
        CommandPreset("wardrobe", "Wardrobe", "wardrobe", "Opens your wardrobe to swap armor sets.") { Items.LEATHER_CHESTPLATE },
        CommandPreset("trades", "Trades", "trades", "Opens the Trades menu.") { Items.EMERALD },
        CommandPreset("pets", "Pets", "pets", "Opens your pets menu.") { Items.BONE },
        CommandPreset("sacks", "Sacks", "sacks", "Opens your sacks.") { Items.BUNDLE },
        CommandPreset("auction_house", "Auction House", "ah", "Opens the Auction House.") { Items.GOLD_INGOT },
        CommandPreset("bazaar", "Bazaar", "bz", "Opens the Bazaar.") { Items.GOLD_BLOCK },
        CommandPreset("craft", "Crafting Table", "craft", "Opens a crafting table anywhere.") { Items.CRAFTING_TABLE },
        CommandPreset(
            "soul_gui",
            "Soul GUI",
            "soul gui",
            "Opens the Move GUI to place and size Soul's HUDs. Also works with your inventory or a menu open; closing it returns there.",
            screen = { parent -> HudMoveScreen(parent) },
        ) { Items.NETHER_STAR },
        CommandPreset(
            // Not "soul": that id was retired in config migration v3 and must stay unused.
            "soul_config",
            "Soul Config",
            "soul",
            "Opens Soul's settings. Also works with your inventory or a menu open; closing it returns there.",
            screen = { parent -> SoulConfigScreen(parent) },
        ) { Items.COMPARATOR },
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): CommandPreset? = byId[id]
}
