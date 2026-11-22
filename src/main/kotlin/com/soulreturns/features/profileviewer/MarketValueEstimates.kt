package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.accessoryBagBlob
import com.soulreturns.data.hypixel.armorBlob
import com.soulreturns.data.hypixel.armorSets
import com.soulreturns.data.hypixel.backpackBlobs
import com.soulreturns.data.hypixel.bankBalance
import com.soulreturns.data.hypixel.enderChestBlob
import com.soulreturns.data.hypixel.equipmentBlob
import com.soulreturns.data.hypixel.equipmentSets
import com.soulreturns.data.hypixel.essenceAmounts
import com.soulreturns.data.hypixel.fishingBagBlob
import com.soulreturns.data.hypixel.inventoryBlob
import com.soulreturns.data.hypixel.personalBank
import com.soulreturns.data.hypixel.personalVaultBlob
import com.soulreturns.data.hypixel.potionBagBlob
import com.soulreturns.data.hypixel.purse
import com.soulreturns.data.hypixel.quiverBlob
import com.soulreturns.data.hypixel.sacksBagBlob
import com.soulreturns.data.hypixel.wardrobeBlob
import com.soulreturns.data.prices.MarketItemKey
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceCatalogClient
import com.soulreturns.data.prices.PriceCatalogSnapshot
import com.soulreturns.features.pets.PetState
import com.soulreturns.features.profileviewer.gui.ProfileItems
import com.soulreturns.features.profileviewer.gui.sections.PetsSection
import com.soulreturns.platform.concurrent.SoulScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * [MarketValue] for the profile on screen, computed off the render path and cached per viewed profile
 * and price snapshot. [get] is a few map probes per frame: it waits for the price catalog and every
 * item blob to decode ([ProfileItems]), then prices them once on `SoulScope.io`. A price refresh
 * recomputes in the background while the previous number stays on screen.
 */
object MarketValueEstimates {
    /** [estimate] null while loading; [noPrices] when the price catalog is empty and its last fetch failed. */
    class State(val estimate: MarketValue.Estimate?, val noPrices: Boolean = false)

    private val LOADING = State(null)
    private val NO_PRICES = State(null, noPrices = true)

    private class Entry(val member: JsonObject, val prices: PriceCatalogSnapshot, val estimate: MarketValue.Estimate)

    @Volatile private var done: Entry? = null

    /** The (member, prices) pair being computed; identity-compared, so a duplicate launch is skipped. */
    @Volatile private var running: Pair<JsonObject, PriceCatalogSnapshot>? = null

    fun get(profile: SkyblockProfile, member: SkyblockProfileMember): State {
        if (PriceCatalogClient.isEmpty()) return if (PriceCatalogClient.status().lastError != null) NO_PRICES else LOADING
        val prices = PriceCatalogClient.snapshot
        val cached = done?.takeIf { it.member === member.raw }
        if (cached != null && cached.prices === prices) return State(cached.estimate)
        val busy = running
        if (busy == null || busy.first !== member.raw || busy.second !== prices) {
            val decoded = decodedGroups(member)
            if (decoded == null || decoded.none { (_, blobs) -> blobs.any { it.loading } }) {
                running = member.raw to prices
                SoulScope.io.launch {
                    val estimate = MarketValue.estimate(inputs(profile, member, decoded)) { PriceCache.value(it) }
                    done = Entry(member.raw, prices, estimate)
                    running = null
                }
            }
        }
        return cached?.let { State(it.estimate) } ?: LOADING
    }

    /** The member's decoded item groups in breakdown order, null when the Inventory API is off. */
    internal fun decodedGroups(m: SkyblockProfileMember): List<Pair<String, List<ProfileItems.Blob>>>? {
        if (m.apiAccessDisabled) return null
        val backpacks = m.backpackBlobs
        fun blobs(vararg data: String?) = data.filterNotNull().map(ProfileItems::get)
        // The wardrobe's items: the old blob and/or the Loadouts armor and equipment sets (one-item blobs, decoded
        // together under the same keys the Gear section uses, so they decode once). The worn set's own entry is empty.
        val loadoutSets = listOfNotNull(
            m.armorSets?.let { sets -> ProfileItems.getPieces(m.raw, "armor") { sets.blobs } },
            m.equipmentSets?.let { sets -> ProfileItems.getPieces(m.raw, "equipment") { sets.blobs } },
        )
        return listOf(
            "Armor and equipment" to blobs(m.armorBlob, m.equipmentBlob),
            "Inventory" to blobs(m.inventoryBlob),
            "Ender Chest" to blobs(m.enderChestBlob),
            "Backpacks" to blobs(*(backpacks.map { it.data } + backpacks.mapNotNull { it.iconBlob }).toTypedArray()),
            "Wardrobe" to blobs(m.wardrobeBlob) + loadoutSets,
            "Accessories" to blobs(m.accessoryBagBlob),
            "Bags" to blobs(m.potionBagBlob, m.fishingBagBlob, m.quiverBlob, m.sacksBagBlob),
            "Personal Vault" to blobs(m.personalVaultBlob),
        )
    }

    private fun uuidOf(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("uuid", "")?.takeIf { it.isNotEmpty() }

    private fun inputs(profile: SkyblockProfile, m: SkyblockProfileMember, decoded: List<Pair<String, List<ProfileItems.Blob>>>?): MarketValue.Inputs {
        val hidden = ArrayList<String>()
        val groups = decoded?.map { (label, blobs) ->
            if (blobs.any { it.failed }) hidden += "$label: unreadable"
            val stacks = blobs.flatMap { it.stacks }.mapNotNull { s -> s?.takeUnless { it.isEmpty }?.let { MarketValue.Stack(MarketItemKey.of(it), it.count, uuidOf(it)) } }
            MarketValue.Group(label, stacks)
        }
        if (groups != null && m.personalVaultBlob == null) hidden += "Personal Vault: API off"
        val coop = profile.bankBalance
        val personal = m.personalBank
        val bank = if (coop == null && personal == null) null else (coop ?: 0.0) + (personal ?: 0.0)
        if (bank == null) hidden += "Bank: Banking API off"
        val pets = PetsSection.rows(PetState.apiPets(m), { null }, heldName = { it }).map { it.priceKey }
        val essence = m.essenceAmounts.orEmpty().filter { it.amount > 0 }.associate { it.type to it.amount }
        return MarketValue.Inputs(m.purse, bank, groups, m.sacks, pets, essence, hidden)
    }
}
