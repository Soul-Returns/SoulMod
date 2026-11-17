package com.soulreturns.features.pets

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.dev.DevState
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SelfProfileLoaded
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.data.skyblock.TabWidgetsChanged
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.stats.StatsHandle
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.toLegacyText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * The game side of the Pet HUD's data: feeds [PetTracker] from the tab Pet widget, pet chat, the Pets and
 * Stats & Equipment menus and the self profile, and stores its per-pet cache in `stats.json` (namespace
 * `pets`, one bucket per SkyBlock profile). Everything runs on the client thread and only while the Pet
 * HUD is on. Rules: rule://pet-hud.
 */
object PetState {
    const val NAMESPACE = "pets"

    /** Stats & Equipment: the equipped pet sits in this container slot. */
    private const val EQUIPMENT_PET_SLOT = 47

    private val handle: StatsHandle<PetProfileData> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = PetProfileData.serializer(),
            default = { PetProfileData() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    val tracker = PetTracker(load = { handle.read() }, save = { d -> handle.update { d } }, heldName = ::heldName)

    private var activeProfile: String? = null
    private var restored = false

    /** Menu stacks already read, by container slot (identity: a slot update replaces the instance). */
    private val parsedFrom = HashMap<Int, ItemStack>()
    private var screenSeen: AbstractContainerScreen<*>? = null

    fun enabled(): Boolean = cfg.petHud.enabled

    fun register() {
        DevState.register("pet") {
            linkedMapOf("enabled" to enabled(), "profile" to activeProfile, "view" to tracker.view(System.currentTimeMillis()))
        }
        Events.subscribe<ProfileChanged> { event ->
            val current = event.current ?: return@subscribe
            if (current == activeProfile) return@subscribe
            activeProfile = current
            if (enabled()) restore()
        }
        Events.subscribe<TabWidgetsChanged> { e ->
            if (!ready()) return@subscribe
            val cur = e.current
            if (!cur.petKnown) return@subscribe
            if (e.previous.petKnown && e.previous.pet == cur.pet) return@subscribe
            tracker.onTab(cur.pet, System.currentTimeMillis())
        }
        Events.subscribe<ChatMessage> { onChat(it) }
        Events.subscribe<SelfProfileLoaded> { seed() }
        // The other dataset's pet cache is open now: read the active pet from it before the next save.
        Events.subscribe<PlayerDataset.Resolved> { if (it.switched) restored = false }
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                if (!ready()) {
                    restored = restored && enabled()
                    return@EndTick
                }
                tracker.tick(System.currentTimeMillis())
                scanMenu()
            },
        )
    }

    /** On SkyBlock with the HUD on; restores the stored pet the first time (also when turned on mid-session). */
    private fun ready(): Boolean {
        if (!enabled() || !SkyblockState.onSkyblock) return false
        if (!restored) restore()
        return true
    }

    private fun restore() {
        restored = true
        tracker.restore()
        seed()
        val snapshot = TabWidgets.snapshot
        if (snapshot.petKnown) tracker.onTab(snapshot.pet, System.currentTimeMillis())
    }

    private fun onChat(message: ChatMessage) {
        if (!ready() || message.source != ChatMessage.Source.SERVER) return
        val component = message.component
        val legacy = component?.toLegacyText() ?: message.raw
        // Cheap gate before the hover walk: every pet line names "pet", "your" or Autopet.
        val plain = MessageDetector.stripColorCodes(legacy)
        if (!plain.contains("Autopet") && !plain.contains("your ") && !plain.contains("Your ")) return
        val hovers = if (component != null && plain.startsWith("Autopet")) hoverTexts(component) else emptyList()
        val line = PetChat.parse(legacy, hovers) ?: return
        tracker.onChat(line, System.currentTimeMillis())
    }

    private fun hoverTexts(component: Component): List<String> {
        val out = LinkedHashSet<String>()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, _ ->
                val hover = style.hoverEvent
                if (hover is HoverEvent.ShowText) out += hover.value().toLegacyText()
                Optional.empty()
            },
            Style.EMPTY,
        )
        return out.toList()
    }

    /** Seeds the cache from the self profile's `pets_data.pets[]` (minutes stale; live values win). */
    private fun seed() {
        if (!enabled()) return
        val snapshot = SkyblockSelfProfile.snapshot ?: return
        if (!snapshot.profileName.equals(SkyblockProfileState.current, ignoreCase = true)) return
        val pets = apiPets(snapshot.member)
        if (pets.isEmpty()) return
        tracker.onApi(pets, snapshot.loadedAt.toEpochMilli())
    }

    fun apiPets(member: SkyblockProfileMember): List<ApiPet> {
        val data = member.raw["pets_data"] as? JsonObject ?: return emptyList()
        val list = data["pets"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o.str("type") ?: return@mapNotNull null
            ApiPet(
                uuid = o.str("uuid"),
                type = type,
                exp = (o["exp"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                tier = o.str("tier"),
                heldItem = o.str("heldItem"),
                skin = o.str("skin"),
                active = (o["active"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ menus

    private fun isPetsMenu(title: String): Boolean = title == "Pets" || title.endsWith(") Pets") || title.startsWith("Pets (") || title.startsWith("Pets: ")

    /** `(1/3) Loadouts`: slot 21 holds the equipped pet, and the menu reopens with the new one after a switch. */
    private fun isLoadoutsMenu(title: String): Boolean = title == "Loadouts" || title.endsWith(") Loadouts")

    private fun isEquipmentMenu(title: String): Boolean = title == "Stats & Equipment" || title == "Your Equipment and Stats"

    private fun scanMenu() {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        if (screen !== screenSeen) {
            parsedFrom.clear()
            screenSeen = screen
        }
        if (screen == null) return
        val title = MessageDetector.stripColorCodes(screen.title.string).trim()
        val pets = isPetsMenu(title) || isLoadoutsMenu(title)
        val equipment = !pets && isEquipmentMenu(title)
        if (!pets && !equipment) return
        val now = System.currentTimeMillis()
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory) continue
            if (equipment && slot.index != EQUIPMENT_PET_SLOT) continue
            val stack = slot.item
            if (parsedFrom[slot.index] === stack) continue
            parsedFrom[slot.index] = stack
            val item = readPet(stack) ?: continue
            val look = InventoryButtonIcons.look(stack)
            tracker.onMenuPet(item, PetIconLook(look.item, look.skin, look.model), equipment, now)
        }
    }

    /** The pet [stack] shows (`custom_data.id == PET`), or null. */
    fun readPet(stack: ItemStack): PetItem? {
        if (stack.isEmpty) return null
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        if (tag.getStringOr("id", "") != "PET") return null
        // Hypixel stores petInfo as a JSON string; accept a compound too (as MarketItemKey does).
        val compound = tag.getCompound("petInfo")
        val petInfo = if (compound.isPresent) {
            val c = compound.get()
            buildJsonObject {
                for (k in listOf("uuid", "type", "tier", "heldItem", "skin")) c.getString(k).ifPresent { put(k, it) }
                c.getDouble("exp").ifPresent { put("exp", it) }
                c.getBoolean("active").ifPresent { put("active", it) }
            }.toString()
        } else {
            tag.getStringOr("petInfo", "").ifEmpty { null }
        }
        val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.toLegacyText() }.orEmpty()
        return PetItems.parse(stack.hoverName.toLegacyText(), lore, petInfo)
    }

    // ------------------------------------------------------------------ held item names

    private fun heldName(id: String): PetHeld {
        val entry = ItemCatalogClient.byId(id)
        val name = entry?.displayName?.takeIf { it.isNotBlank() }?.let(MessageDetector::stripColorCodes) ?: PetItems.titleOf(id)
        return PetHeld(name, rarityCode(SkyblockRarity.forName(entry?.rarity)))
    }

    private fun rarityCode(r: SkyblockRarity?): Char =
        if (r == null) {
            'f'
        } else {
            "fa956dbcc4"[r.ordinal.coerceIn(0, 9)]
        }
}
