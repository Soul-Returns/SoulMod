package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.accessoryBagBlob
import com.soulreturns.data.hypixel.armorBlob
import com.soulreturns.data.hypixel.backpackBlobs
import com.soulreturns.data.hypixel.bankBalance
import com.soulreturns.data.hypixel.enderChestBlob
import com.soulreturns.data.hypixel.equipmentBlob
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
            val groups = blobGroups(member)
            val decoded = groups?.map { (label, blobs) -> label to blobs.map(ProfileItems::get) }
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

    /** The member's item blobs in breakdown order, null when the Inventory API is off. */
    private fun blobGroups(m: SkyblockProfileMember): List<Pair<String, List<String>>>? {
        if (m.apiAccessDisabled) return null
        val backpacks = m.backpackBlobs
        return listOf(
            "Armor and equipment" to listOfNotNull(m.armorBlob, m.equipmentBlob),
            "Inventory" to listOfNotNull(m.inventoryBlob),
            "Ender Chest" to listOfNotNull(m.enderChestBlob),
            "Backpacks" to backpacks.map { it.data } + backpacks.mapNotNull { it.iconBlob },
            "Wardrobe" to listOfNotNull(m.wardrobeBlob),
            "Accessories" to listOfNotNull(m.accessoryBagBlob),
            "Bags" to listOfNotNull(m.potionBagBlob, m.fishingBagBlob, m.quiverBlob, m.sacksBagBlob),
            "Personal Vault" to listOfNotNull(m.personalVaultBlob),
        )
    }

    private fun inputs(profile: SkyblockProfile, m: SkyblockProfileMember, decoded: List<Pair<String, List<ProfileItems.Blob>>>?): MarketValue.Inputs {
        val hidden = ArrayList<String>()
        val groups = decoded?.map { (label, blobs) ->
            if (blobs.any { it.failed }) hidden += "$label: unreadable"
            val stacks = blobs.flatMap { it.stacks }.mapNotNull { s -> s?.takeUnless { it.isEmpty }?.let { MarketValue.Stack(MarketItemKey.of(it), it.count) } }
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
