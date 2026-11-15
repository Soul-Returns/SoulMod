package com.soulreturns.features.dungeons.roles

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.TabWidgetParser
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.boss.BossPhase
import com.soulreturns.features.dungeons.boss.F7Boss
import com.soulreturns.features.dungeons.boss.GoldorSections
import com.soulreturns.features.dungeons.leap.LeapMenu
import com.soulreturns.features.dungeons.devices.GoldorPhase
import com.soulreturns.platform.ServerTime
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/** Read-only F7/M7 route advice. No input callback, packet sender, or gameplay action belongs here. */
object ArcherRoles {
    private val route = ArcherRoleModel()
    val protection = DeathSaveTracker()
    var cue: ArcherCue? = null
        private set

    private val pos = BlockPos.MutableBlockPos()
    private val slot = BlockPos(94, 224, 41)
    private var watched = false
    private var polls = 0
    private var gateThree = false
    private var tabLines: List<String>? = null
    private var phoenixActive: Boolean? = null
    private val masks = arrayOfNulls<Mask>(64)
    private val stacks = arrayOfNulls<ItemStack>(64)
    private var headStack: ItemStack? = null
    private var headMask: Mask? = null

    private class Mask(val source: Int, val identity: String?, val cooldown: Long?)

    fun register() {
        Events.subscribe<ChatMessage> { onChat(it) }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
    }

    private fun inFloorSeven() = LocationApi.inDungeon &&
        (DungeonRunState.floor == DungeonFloor.F7 || DungeonRunState.floor == DungeonFloor.M7)

    private fun reset() {
        route.reset()
        protection.reset()
        cue = null
        watched = false
        polls = 0
        gateThree = false
        tabLines = null
        phoenixActive = null
        masks.fill(null)
        stacks.fill(null)
        headStack = null
        headMask = null
    }

    private fun tick() {
        if (!inFloorSeven()) {
            if (watched) reset()
            cue = null
            return
        }
        watched = true
        val mc = Minecraft.getInstance()
        val me = mc.player ?: return clearCue()
        val level = mc.level ?: return clearCue()
        val tick = ServerTime.tick()
        protection.advance(tick, tick != null && ServerTime.fresh())
        if (tick == null) return clearCue()
        observeMasks(me)
        observePet()
        if (++polls % 5 == 0) {
            observeCoverage()
            if (!gateThree && route.step >= 19 && route.step <= 23) gateThree = gateAbsent()
        }
        val model = F7Boss.model
        var phase = model?.phase ?: BossPhase.NONE
        if (GoldorPhase.model.stormEnd != null) phase = maxOf(phase, BossPhase.STORM_DONE)
        if (GoldorPhase.model.active) phase = maxOf(phase, BossPhase.GOLDOR)
        if (GoldorPhase.sections.open == GoldorSections.CORE) phase = maxOf(phase, BossPhase.CORE)
        route.sync(
            phase, GoldorPhase.sections.open, GoldorSections.area(me.x, me.y, me.z),
            F7Boss.maxorProgress?.let { it <= 0f } == true,
            model?.leftPurpleAt != null,
            F7Boss.necronProgress?.let { it <= 0.05f } == true,
            gateThree || GoldorPhase.sections.gateDestroyed(3),
        )
        if (route.step in 0..7 && level.hasChunkAt(slot)) {
            val state = level.getBlockState(slot)
            var crystal = false
            if (route.step == 3) {
                for (e in level.entitiesForRendering()) {
                    if (e.type === EntityTypes.END_CRYSTAL && e.x in 80.0..85.0 && e.y in 236.0..241.0 && e.z in 48.0..54.0) {
                        crystal = true
                        break
                    }
                }
            }
            route.crystalSlot(
                state.`is`(Blocks.STONE_PRESSURE_PLATE) && state.getValue(BlockStateProperties.POWERED),
                me.distanceToSqr(94.5, 224.0, 41.5) <= 25.0,
                crystal,
            )
        }
        route.arrived(me.x, me.y, me.z)
        publish()
    }

    private fun clearCue() { cue = null }

    private fun onChat(message: ChatMessage) {
        if (!inFloorSeven() || message.source != ChatMessage.Source.SERVER) return
        val line = MessageDetector.stripColorCodes(message.raw).trim()
        if (MessageDetector.isPlayerMessage(line)) return
        val me = Minecraft.getInstance().player ?: return
        val self = me.gameProfile.name
        val tick = ServerTime.tick()
        observeMasks(me)
        protection.advance(tick, tick != null && ServerTime.fresh())
        if (tick != null) protection.chat(line, tick)
        val teleport = ArcherRoleModel.TELEPORT.matchEntire(line)
        val cls = teleport?.groupValues?.get(1)?.let { name ->
            DungeonRunState.players.firstOrNull { it?.name.equals(name, ignoreCase = true) }?.dungeonClass
        }
        val completion = ArcherRoleModel.COMPLETION.matchEntire(line)
        val actor = completion?.groupValues?.get(1)?.let { name ->
            if (name.equals(self, ignoreCase = true)) me
            else if (DungeonRunState.players.any { it?.name.equals(name, ignoreCase = true) }) tracked(name) else null
        }
        route.chat(line, self, cls, actor?.x, actor?.y, actor?.z)
    }

    private fun publish() {
        if (!cfg.dungeons.roles.enabled || DungeonRunState.self?.dungeonClass != DungeonClass.ARCHER || DungeonRunState.self?.isDead == true) return clearCue()
        val target = route.target
        var name: String? = null
        var known = false
        var ambiguous = false
        val now = System.currentTimeMillis()
        if (target != null) {
            for (p in DungeonRunState.players) {
                if (p == null || p.dungeonClass != target) continue
                if (name != null) { ambiguous = true; break }
                name = p.name
                if (!p.isDead) {
                    val e = tracked(p.name)
                    known = if (e != null) route.destination(e.x, e.y, e.z)
                    else route.destination(LeapMenu.locations.locate(p.name, null, now))
                }
            }
        }
        if (ambiguous) { name = null; known = false }
        cue = route.cue(GoldorPhase.sections.open, name, known, protection.status())
    }

    private fun tracked(name: String): Player? {
        val level = Minecraft.getInstance().level ?: return null
        for (player in level.players()) if (!player.isRemoved && player.gameProfile.name.equals(name, ignoreCase = true)) return player
        return null
    }

    private fun observeCoverage() {
        if (!GoldorPhase.model.active || route.step > 25) return
        val level = Minecraft.getInstance().level ?: return
        for (o in ArcherRoleModel.OBJECTIVES) {
            if (o.kind != ArcherRoleModel.LEVER) continue
            pos.set(o.x.toInt(), o.y.toInt(), o.z.toInt())
            if (!level.hasChunkAt(pos)) continue
            val state = level.getBlockState(pos)
            if (state.`is`(Blocks.LEVER) && state.getValue(BlockStateProperties.POWERED)) route.complete(o.id)
        }
        for (e in level.entitiesForRendering()) {
            if (e.type !== EntityTypes.ARMOR_STAND || !e.hasCustomName()) continue
            if (MessageDetector.stripColorCodes(e.customName?.string ?: "").trim() != "Terminal Active") continue
            for (o in ArcherRoleModel.OBJECTIVES) {
                if (o.kind != ArcherRoleModel.TERMINAL) continue
                val dx = e.x - (o.x + 0.5)
                val dz = e.z - (o.z + 0.5)
                val dy = e.y - (o.y - 1.0)
                if (dx * dx + dz * dz <= 2.0 && kotlin.math.abs(dy) <= 0.6) route.complete(o.id)
            }
        }
    }

    /** The complete observed S3 door face, not just the lock or one missing block. Unloaded chunks are not air evidence. */
    private fun gateAbsent(): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        for (x in 4..12) for (y in 115..133) {
            pos.set(x, y, 49)
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir) return false
        }
        return true
    }

    private fun observePet() {
        val lines = TabWidgets.lastLines
        if (lines !== tabLines) {
            tabLines = lines
            val read = TabWidgetParser.parse(lines)
            phoenixActive = if (read.petSeen) read.pet?.name.equals("Phoenix", ignoreCase = true) else null
        }
        protection.observe(DeathSaveTracker.PHOENIX, phoenixActive, null, null)
    }

    private fun observeMasks(player: Player) {
        val head = player.getItemBySlot(EquipmentSlot.HEAD)
        if (head !== headStack) { headStack = head; headMask = mask(head) }
        var spirit: Mask? = headMask?.takeIf { it.source == DeathSaveTracker.SPIRIT }
        var bonzo: Mask? = headMask?.takeIf { it.source == DeathSaveTracker.BONZO }
        val inventory = player.inventory
        for (i in 0 until minOf(inventory.containerSize, stacks.size)) {
            val stack = inventory.getItem(i)
            if (stack !== stacks[i]) { stacks[i] = stack; masks[i] = mask(stack) }
            val item = masks[i] ?: continue
            if (item.source == DeathSaveTracker.SPIRIT && (spirit == null || (headMask?.source != DeathSaveTracker.SPIRIT && item.identity == protection.spirit.identity))) spirit = item
            if (item.source == DeathSaveTracker.BONZO && (bonzo == null || (headMask?.source != DeathSaveTracker.BONZO && item.identity == protection.bonzo.identity))) bonzo = item
        }
        protection.observe(DeathSaveTracker.SPIRIT, headMask?.source == DeathSaveTracker.SPIRIT, spirit?.identity, spirit?.cooldown)
        protection.observe(DeathSaveTracker.BONZO, headMask?.source == DeathSaveTracker.BONZO, bonzo?.identity, bonzo?.cooldown)
    }

    private fun mask(stack: ItemStack): Mask? {
        if (stack.isEmpty || !stack.`is`(Items.PLAYER_HEAD)) return null
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        val id = tag.getStringOr("id", "")
        val source = when (id) {
            "SPIRIT_MASK", "STARRED_SPIRIT_MASK" -> DeathSaveTracker.SPIRIT
            "BONZO_MASK", "STARRED_BONZO_MASK" -> DeathSaveTracker.BONZO
            else -> return null
        }
        val uuid = tag.getStringOr("uuid", "").takeIf { it.isNotEmpty() }
        var cooldown: Long? = null
        for (line in stack.get(DataComponents.LORE)?.lines() ?: emptyList()) {
            val ticks = DeathSaveTracker.advertisedCooldown(line.string) ?: continue
            if (cooldown != null && cooldown != ticks) return Mask(source, null, null)
            cooldown = ticks
        }
        return Mask(source, uuid?.let { "$id:$it" }, cooldown)
    }
}
