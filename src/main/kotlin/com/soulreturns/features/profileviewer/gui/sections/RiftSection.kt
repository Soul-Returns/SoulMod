package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.riftProgress
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui
import net.minecraft.world.item.ItemStack

/** Motes, Enigma Souls, cats, timecharms and the Rift's own inventory (mock `riftSec`). */
object RiftSection : ProfileSection {
    override val id = "rift"
    override val title = "Rift"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_swirl"

    /** Montezuma's cat parts. */
    private const val CATS = 9

    /** Timecharms and Wither Cage eyes: totals seen on ONE real profile; [INFERENCE], not a verified maximum. */
    private const val TIMECHARMS = 8
    private const val EYES = 7

    private const val COLS = 9
    private const val MAIN_SLOTS = 27
    private const val HOTBAR_SLOTS = 9

    /** The Rift has no rail entry for a player who never entered it. */
    override fun visible(ctx: ProfileContext): Boolean = ctx.member.riftProgress != null

    override fun build(ctx: ProfileContext, ui: Ui) {
        val r = ctx.member.riftProgress
        if (r == null) {
            ui.empty("pv_swirl", "Never entered the Rift", "${ctx.name} hasn't been through the Rift on ${ctx.profile.cuteName}.")
            return
        }
        ui.tiles {
            tile("Motes", r.motes?.let { Format.num(it) } ?: "—", sub = r.lifetimeMotes?.let { "${Format.short(it)} earned in total" }, color = Rich.mcColor('d') ?: KitStyle.TEXT)
            tile("Enigma Souls", Format.num(r.enigmaSouls), sub = "found")
            tile("Montezuma", "${r.catsFound} / $CATS", sub = "cat parts found")
            tile("Timecharms", "${r.timecharms} / $TIMECHARMS", sub = "secured")
            tile("Wither Cage", "${r.witherCageEyes} / $EYES", sub = "eyes killed")
            tile("Rift Prism", if (r.prismUsed) "Used" else "Not yet", sub = "+11 Magical Power", color = if (r.prismUsed) KitStyle.GOOD else KitStyle.TEXT)
        }
        val items = r.items
        if (!items.any) {
            ui.apiOff(ctx.name, ctx.isSelf, "Inventory API", "the Rift inventory and equipment")
            return
        }
        ui.grid(250) {
            items.inventory?.let { data -> card("Rift inventory") { blobGrid(ctx, data) { mainThenHotbar(it) } } }
            if (items.armor != null || items.equipment != null) {
                card("Armor and equipment") {
                    items.armor?.let { data ->
                        label("Armor")
                        blobGrid(ctx, data) { it.asReversed() }
                    }
                    items.equipment?.let { data ->
                        label("Equipment")
                        blobGrid(ctx, data) { it }
                    }
                }
            }
            items.enderChest?.let { data -> card("Rift Ender Chest") { blobGrid(ctx, data) { it } } }
        }
    }

    /** The decoded slots of [data] as a 9-wide grid, or a skeleton while decoding and a note when it failed. */
    private fun Ui.blobGrid(ctx: ProfileContext, data: String, order: (List<ItemStack?>) -> List<ItemStack?>) {
        val blob = ctx.items.get(data)
        when {
            blob.loading -> skeleton(3)
            blob.failed -> text("Soul could not read these items.", color = KitStyle.TEXT_DIM)
            else -> slots(order(blob.stacks), cols = COLS)
        }
    }

    /** The API lists the hotbar first; the game shows it under the 27 main slots. */
    private fun mainThenHotbar(slots: List<ItemStack?>): List<ItemStack?> =
        if (slots.size == MAIN_SLOTS + HOTBAR_SLOTS) slots.subList(HOTBAR_SLOTS, slots.size) + slots.subList(0, HOTBAR_SLOTS) else slots
}
