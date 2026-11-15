package com.soulreturns.devbridge

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.core.SectionPos
import io.netty.buffer.Unpooled
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.entity.BlockEntityType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge's packet serializer is bounded. Regression: a real `block_entity_data` packet for a skull
 * serialized to 1.2 MB because the reflective dump walked into the block entity *type* object.
 */
class PacketDumpTest {
    @BeforeTest
    fun boot() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun entityType(name: String): BlockEntityType<*> = BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(name))!!

    private fun skull(): ClientboundBlockEntityDataPacket {
        val texture = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTc4ZTZhODNmZmEzYTE2OWZjN2ExMmM2ZjZhYzkzMWZmMWIyMTJjMzYwYTU2Nzc0ZTRjOGQ0ZTI2OWI5NGU1YiJ9fX0="
        val property = CompoundTag().apply { putString("name", "textures"); putString("value", texture) }
        val profile = CompoundTag().apply { putString("name", "f811e5de52d6"); put("properties", ListTag().apply { add(property) }) }
        val tag = CompoundTag().apply { put("profile", profile); put("note", StringTag.valueOf("x")) }
        val ctor = ClientboundBlockEntityDataPacket::class.java.getDeclaredConstructor(BlockPos::class.java, BlockEntityType::class.java, CompoundTag::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(BlockPos(95, 225, 72), entityType("skull"), tag)
    }

    @Test
    fun blockEntityDataIsSmallAndKeepsTheData() {
        val json = PacketDump.packet("in", skull(), 1L, PacketDump.SYNC_MILLIS).toString()
        assertTrue(json.length < 2_000, "block_entity_data is ${json.length} chars: $json")
        assertTrue("\"minecraft:skull\"" in json, json)
        assertTrue("\"x\":95" in json && "\"y\":225" in json && "\"z\":72" in json, json)
        assertTrue("textures" in json && "eyJ0ZXh0dXJlcyI6" in json, json)
        assertTrue("validBlocks" !in json && "renderer" !in json, json)
    }

    @Test
    fun registryEntriesAreIdsNotObjectGraphs() {
        val out = PacketDump.dump(mapOf("type" to entityType("chest"), "block" to net.minecraft.world.level.block.Blocks.STONE), PacketDump.Budget(1_000, 10_000, 100)).toString()
        assertTrue("minecraft:chest" in out && "minecraft:stone" in out && out.length < 200, out)
    }

    @Test
    fun anOversizedPacketBecomesAStub() {
        val stubs = ArrayList<String>()
        val huge = CompoundTag().apply { putByteArray("blob", ByteArray(200_000)) }
        val ctor = ClientboundBlockEntityDataPacket::class.java.getDeclaredConstructor(BlockPos::class.java, BlockEntityType::class.java, CompoundTag::class.java)
        ctor.isAccessible = true
        val packet = ctor.newInstance(BlockPos(0, 0, 0), entityType("skull"), huge)
        val json = PacketDump.packet("in", packet, 1L, PacketDump.SYNC_MILLIS) { stubs += it }.toString()
        assertTrue(json.length < 500, json)
        assertTrue("\"bytes\"" in json, json)

        // A structure wider than the node budget stops at the budget, not at the end.
        val wide = List(100_000) { listOf(it, it) }
        val budget = PacketDump.Budget(100, 1_000_000, 1_000)
        val overflow = runCatching { PacketDump.dump(wide, budget) }.exceptionOrNull()
        assertTrue(overflow is PacketDump.Overflow && overflow.reason == "nodes", "$overflow")
        assertTrue(budget.nodes <= 101)
        assertEquals(emptyList(), stubs)
    }

    @Test
    fun stringsAreCapped() {
        val out = PacketDump.dump("x".repeat(1_000_000), PacketDump.Budget(10, 10_000, 100)).toString()
        assertTrue(out.length < 2_100, "${out.length}")
    }

    /** Item default components are bound by the data pack load in a real game; a bare bootstrap leaves them unbound. */
    private fun bound(item: net.minecraft.world.item.Item): net.minecraft.world.item.Item {
        val holder = item.builtInRegistryHolder()
        if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY)
        return item
    }

    private fun skullStack(): net.minecraft.world.item.ItemStack {
        val url = "http://textures.minecraft.net/texture/e78e6a83ffa3a169fc7a12c6f6ac931ff1b212c360a56774e4c8d4e269b94e5b"
        val value = java.util.Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"$url"}}}""".toByteArray())
        val properties = com.mojang.authlib.properties.PropertyMap(com.google.common.collect.ImmutableMultimap.of("textures", com.mojang.authlib.properties.Property("textures", value)))
        val stack = net.minecraft.world.item.ItemStack(bound(net.minecraft.world.item.Items.PLAYER_HEAD))
        stack.set(net.minecraft.core.component.DataComponents.PROFILE, net.minecraft.world.item.component.ResolvableProfile.createResolved(com.mojang.authlib.GameProfile(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"), "x", properties)))
        stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    @Test
    fun equipmentPacketsKeepTheItemsAndAHeadsTexture() {
        val packet = net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
            5,
            listOf(com.mojang.datafixers.util.Pair(net.minecraft.world.entity.EquipmentSlot.HEAD, skullStack()), com.mojang.datafixers.util.Pair(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack(bound(net.minecraft.world.item.Items.STICK)))),
        )
        val json = PacketDump.packet("in", packet, 1L, PacketDump.SYNC_MILLIS).toString()
        assertTrue("\"HEAD\"" in json && "\"minecraft:player_head\"" in json && "\"minecraft:stick\"" in json, json)
        assertTrue("\"texture\":\"e78e6a83ffa3a169fc7a12c6f6ac931ff1b212c360a56774e4c8d4e269b94e5b\"" in json, json)
        assertTrue("\"glint_override\":true" in json, json)
        assertTrue(json.length < 2_000, "${json.length} chars: $json")
        assertEquals("e78e6a83ffa3a169fc7a12c6f6ac931ff1b212c360a56774e4c8d4e269b94e5b", (Items.summary(skullStack())!!["texture"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    private fun sectionUpdate(entries: List<Triple<Int, Int, Int>>, states: List<net.minecraft.world.level.block.state.BlockState>): ClientboundSectionBlocksUpdatePacket {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(SectionPos.of(2, 3, 4).asLong())
        buf.writeVarInt(entries.size)
        for ((i, e) in entries.withIndex()) {
            val rel = (e.first shl 8) or (e.third shl 4) or e.second  // x<<8 | z<<4 | y, section relative
            buf.writeVarLong((Block.getId(states[i % states.size]).toLong() shl 12) or rel.toLong())
        }
        val ctor = ClientboundSectionBlocksUpdatePacket::class.java.getDeclaredConstructor(FriendlyByteBuf::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(buf)
    }

    @Test
    fun blockStatesAreNamedAndSectionUpdatesAreAPaletteWithRows() {
        val lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, true)
        assertEquals("minecraft:lever[face=wall,facing=north,powered=true]", PacketDump.state(lever))
        val update = PacketDump.packet("in", sectionUpdate(listOf(Triple(1, 2, 3), Triple(4, 5, 6), Triple(0, 0, 0)), listOf(lever, Blocks.STONE.defaultBlockState())), 1L, PacketDump.SYNC_MILLIS).toString()
        // Section (2,3,4) starts at block (32, 48, 64); the rows are absolute with an index into the palette.
        assertTrue("\"states\":[\"minecraft:lever[face=wall,facing=north,powered=true]\",\"minecraft:stone\"]" in update, update)
        assertTrue("\"changes\":[[33,50,67,0],[36,53,70,1],[32,48,64,0]]" in update, update)
        assertTrue("\"count\":3" in update && "\"stub\"" !in update, update)
    }

    @Test
    fun aFullSectionUpdateIsNeitherAStubNorHuge() {
        val stubs = ArrayList<String>()
        val all = (0 until 4096).map { Triple(it shr 8 and 15, it and 15, it shr 4 and 15) }
        val states = listOf(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LEVER.defaultBlockState())
        val json = PacketDump.packet("in", sectionUpdate(all, states), 1L, PacketDump.WORKER_MILLIS, 7L) { stubs += it }.toString()
        assertEquals(emptyList(), stubs, "4096 changes are one node each: no stub")
        assertTrue(json.length < 100_000, "${json.length} chars")
        assertTrue("\"seq\":7" in json, "the hook order is part of the line")
    }

    @Test
    fun aBlockUpdateNamesItsState() {
        val ctor = ClientboundBlockUpdatePacket::class.java.getDeclaredConstructor(BlockPos::class.java, net.minecraft.world.level.block.state.BlockState::class.java)
        ctor.isAccessible = true
        val json = PacketDump.packet("in", ctor.newInstance(BlockPos(111, 120, 92), Blocks.SEA_LANTERN.defaultBlockState()), 1L, PacketDump.SYNC_MILLIS).toString()
        assertTrue("\"minecraft:sea_lantern\"" in json && "\"x\":111" in json, json)
    }
}
