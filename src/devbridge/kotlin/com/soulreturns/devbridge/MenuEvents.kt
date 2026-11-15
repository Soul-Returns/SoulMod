package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

/**
 * Semantic menu events, called by the bridge mixins on the client thread after vanilla applied the
 * packet, so the open menu is already the one the packet describes. Hypixel replaces one menu with
 * the next without a close, so an open over an open container also emits `page`.
 */
object MenuEvents {
    private var previous: Pair<Int, String>? = null
    private var closingByClient = false

    @JvmStatic
    fun beforeOpenScreen() {
        previous = openContainer()?.let { (screen, menu) -> menu.containerId to DevBridgeApi.legacy(screen.title) }
    }

    @JvmStatic
    fun afterOpenScreen(packet: ClientboundOpenScreenPacket) {
        val prev = previous
        previous = null
        val title = DevBridgeApi.legacy(packet.title)
        if (prev != null) {
            Bridge.emit("page") {
                JsonObject(
                    mapOf(
                        "from_title" to JsonPrimitive(prev.second),
                        "from_container_id" to JsonPrimitive(prev.first),
                        "title" to JsonPrimitive(title),
                        "container_id" to JsonPrimitive(packet.containerId),
                        "same_container" to JsonPrimitive(prev.first == packet.containerId),
                    ),
                )
            }
        }
        Bridge.emit("screen_open") {
            val menu = Minecraft.getInstance().player?.containerMenu?.takeIf { it.containerId == packet.containerId }
            JsonObject(
                buildMap {
                    put("container_id", JsonPrimitive(packet.containerId))
                    put("title", JsonPrimitive(title))
                    put("menu_type", JsonPrimitive(BuiltInRegistries.MENU.getKey(packet.type)?.toString()))
                    if (menu != null) {
                        put("slot_count", JsonPrimitive(menu.slots.size))
                        put("container_slots", JsonPrimitive(containerSlots(menu)))
                    }
                    Minecraft.getInstance().gui.screen()?.let { put("screen", JsonPrimitive(it.javaClass.simpleName)) }
                },
            )
        }
    }

    @JvmStatic
    fun afterContents(packet: ClientboundContainerSetContentPacket) {
        val id = packet.containerId()
        Bridge.emit(if (id == 0) "inventory_contents" else "screen_contents") {
            val menu = menuFor(id)
            JsonObject(
                buildMap {
                    put("container_id", JsonPrimitive(id))
                    put("title", JsonPrimitive(titleFor(id)))
                    put("state_id", JsonPrimitive(packet.stateId()))
                    put("slot_count", JsonPrimitive(packet.items().size))
                    if (menu != null) put("container_slots", JsonPrimitive(containerSlots(menu)))
                    put(
                        "slots",
                        JsonArray(
                            packet.items().withIndex().filter { !it.value.isEmpty }.map { (i, stack) -> slot(menu, i, stack) },
                        ),
                    )
                    if (!packet.carriedItem().isEmpty) put("carried", item(packet.carriedItem()))
                },
            )
        }
    }

    @JvmStatic
    fun afterSetSlot(packet: ClientboundContainerSetSlotPacket) {
        val id = packet.containerId
        Bridge.emit(if (id == 0) "inventory_slot" else "slot_update") {
            val obj = slot(menuFor(id), packet.slot, packet.item).toMutableMap()
            obj["container_id"] = JsonPrimitive(id)
            obj["title"] = JsonPrimitive(titleFor(id))
            JsonObject(obj)
        }
    }

    /** From `LocalPlayer.closeContainer`: the next close is the player's (Escape), not the server's. */
    @JvmStatic
    fun clientClosing() {
        closingByClient = true
    }

    @JvmStatic
    fun beforeClose() {
        val byClient = closingByClient
        closingByClient = false
        val menu = Minecraft.getInstance().player?.containerMenu ?: return
        if (menu.containerId == 0) return
        Bridge.emit("screen_close") {
            JsonObject(
                mapOf(
                    "container_id" to JsonPrimitive(menu.containerId),
                    "title" to JsonPrimitive(titleFor(menu.containerId)),
                    "by" to JsonPrimitive(if (byClient) "client" else "server"),
                ),
            )
        }
    }

    @JvmStatic
    fun beforeClick(containerId: Int, slot: Int, button: Int, input: ContainerInput) {
        Bridge.emit("click") {
            val menu = menuFor(containerId)
            val stack = menu?.slots?.getOrNull(slot)?.item
            JsonObject(
                buildMap {
                    put("container_id", JsonPrimitive(containerId))
                    put("title", JsonPrimitive(titleFor(containerId)))
                    put("slot", JsonPrimitive(slot))
                    put("button", JsonPrimitive(button))
                    put("input", JsonPrimitive(input.name))
                    put("item", if (stack == null) JsonNull else item(stack))
                },
            )
        }
    }

    private fun openContainer(): Pair<AbstractContainerScreen<*>, AbstractContainerMenu>? {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return null
        val menu = screen.menu
        return if (menu.containerId == 0) null else screen to menu
    }

    private fun menuFor(containerId: Int): AbstractContainerMenu? {
        val player = Minecraft.getInstance().player ?: return null
        if (containerId == 0) return player.inventoryMenu
        return player.containerMenu.takeIf { it.containerId == containerId }
    }

    private fun titleFor(containerId: Int): String? =
        openContainer()?.takeIf { it.second.containerId == containerId }?.let { DevBridgeApi.legacy(it.first.title) }

    fun containerSlots(menu: AbstractContainerMenu): Int = menu.slots.count { it.container !is Inventory }

    fun slot(menu: AbstractContainerMenu?, index: Int, stack: ItemStack): JsonObject =
        JsonObject(
            buildMap {
                put("slot", JsonPrimitive(index))
                if (menu?.slots?.getOrNull(index)?.container is Inventory) put("player", JsonPrimitive(true))
                put("item", item(stack))
            },
        )

    /** The item dump with its SkyBlock id and component summary ([Items]). */
    fun item(stack: ItemStack): JsonElement = Items.item(stack)
}
