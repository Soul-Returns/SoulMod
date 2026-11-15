package com.soulreturns.features.qol

import com.soulreturns.config.cfg
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack

/**
 * Stops SkyBlock ability items that are backed by a placeable vanilla block — e.g. the Fire Freeze
 * Staff (a dandelion), Turbo Fishing Net (cobweb), Summoning Ring (a player head) — from placing
 * that block when you right-click a block face to use their ability.
 *
 * **Why CONSUME, not FAIL.** Fabric's `UseBlockCallback` fires inside `MultiPlayerGameMode.useItemOn`
 * just before the vanilla local interaction. Fabric only forwards the interaction packet to the
 * server when the callback result `consumesAction()` — i.e. for SUCCESS/CONSUME, not FAIL/PASS.
 * Returning **FAIL** would eat the packet too, so Hypixel would never see the right-click and the
 * ability wouldn't fire. Returning a Success sends the interaction packet (Hypixel still triggers the
 * ability, and server-driven interactions like chests/NPCs still open) while skipping vanilla's
 * *local* block-placement prediction — so no ghost block flickers. We use **CONSUME** rather than
 * SUCCESS because SUCCESS is the arm-swinging variant: intercepting shouldn't add a phantom hand-swing
 * on top of whatever the ability itself does. Right-clicks in the air (no block targeted) go through
 * `useItem`, which we never touch, so ability use pointing at nothing is vanilla.
 *
 * **Narrow on purpose.** Only items that are BOTH a placeable block (their vanilla `Item` is a
 * [BlockItem]) AND a SkyBlock ability item (a `custom_data` id plus an "Ability:" lore line) are
 * intercepted. Plain SkyBlock building blocks (no ability line) still place normally on your island,
 * and non-block ability items (swords, etc.) are left fully vanilla.
 */
object PreventAbilityItemPlace {
    fun register() {
        UseBlockCallback.EVENT.register(
            UseBlockCallback { player, _, hand, _ ->
                if (!cfg.general.preventAbilityItemPlace) return@UseBlockCallback InteractionResult.PASS
                if (isPlaceableAbilityItem(player.getItemInHand(hand))) {
                    // Send the ability interaction to the server, skip local placement (see class doc).
                    // CONSUME, not SUCCESS: both send the packet, but CONSUME is the no-swing variant
                    // so intercepting doesn't trigger a phantom hand-swing.
                    InteractionResult.CONSUME
                } else {
                    InteractionResult.PASS
                }
            },
        )
    }

    private fun isPlaceableAbilityItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        // Only items that would actually place a block can misfire into a placement.
        if (stack.item !is BlockItem) return false
        // A SkyBlock item (has a custom_data id) whose lore advertises an ability — this is what
        // separates an ability tool you never want to place from a legit building block.
        if (SkyblockItemUtils.getSkyblockId(stack) == null) return false
        return hasAbilityLore(stack)
    }

    private fun hasAbilityLore(stack: ItemStack): Boolean {
        val lore = stack.get(DataComponents.LORE) ?: return false
        // Component.string is the flattened text without §-format codes, so a plain contains works.
        return lore.lines().any { it.string.contains("Ability:") }
    }
}
