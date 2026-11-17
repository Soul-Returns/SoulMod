package com.soulreturns.features.experiments

import com.soulreturns.config.cfg
import com.soulreturns.features.experiments.ExpMenus.Kind
import com.soulreturns.sound.SoulSounds
import com.soulreturns.sound.SoundRef
import com.soulreturns.util.ServerTraffic
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import java.util.Base64

/**
 * Experimentation Table glue (rule://experiments): container packets and sounds go one at a time into
 * the pure [ExpFlow] (from `ClientPacketListenerMixin`, client-thread passes only, never `slot.getItem()`
 * which other mods rewrite); a recognized menu gets [ExperimentScreen] one task after vanilla's screen
 * init, the real menu staying open underneath; a press sends at most one container click, at once.
 */
object Experiments {
    private val logger = SoulLogger("Soul/Experiments")
    private var registered = false

    /** Click per screen (research §5.3): proven middle clicks on the add-on cells, PICKUP/0 elsewhere. */
    data class Click(val input: ContainerInput, val button: Int)

    val CHRONO_CLICK = Click(ContainerInput.CLONE, 2)
    val ULTRA_CLICK = Click(ContainerInput.CLONE, 2)
    val SUPERPAIRS_CLICK = Click(ContainerInput.PICKUP, 0)
    val MENU_CLICK = Click(ContainerInput.PICKUP, 0)

    /** Right click for Practice on the table (never captured). */
    val PRACTICE_CLICK = Click(ContainerInput.PICKUP, 1)

    /** Soft cue for a blocked press. */
    private val BLOCK_SOUND = SoundRef.vanilla("minecraft:block.note_block.bass", volume = 0.35f, pitch = 0.6f)

    val flow = ExpFlow(rare = { cfg.experiments.rareWarning && RngMeterStore.isRare(it) }, onRngPage = RngMeterStore::onPage)

    /** The stacks the server sent (by packet) for the current container, for drawing; plus every face a slot ever showed. */
    var stacks: Array<ItemStack> = emptyArray()
        private set
    val seen = HashMap<Int, ItemStack>()
    private var cid = -1
    private val cache = HashMap<ItemStack, ExpItem>()
    private var rareHeard = 0

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ -> onInit(screen) })
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun on(): Boolean = cfg.experiments.enabled

    fun tick() {
        val now = System.currentTimeMillis()
        flow.tick(now, ServerTraffic.lastInboundMs)
        trace()
        val sp = flow.menu?.model as? SuperpairsModel ?: return
        if (sp.rareCount > rareHeard) {
            rareHeard = sp.rareCount
            logger.info("Rare card revealed: {}", sp.lastRare?.plain)
            cfg.experiments.rareSound?.let { SoulSounds.play(it) }
        }
    }

    private var lastTrace = ""

    /** Debug log (dev debug mode) of what the solver believes on each change: compared with the sim's truth, and asked for in live bug reports. */
    private fun trace() {
        val line =
            when (val m = flow.menu?.model) {
                is ChronoModel -> "Chronomatron round=${m.round} phase=${m.phase} k=${m.k} pending=${m.pending.size} off=${m.off} expect=" +
                    if (m.phase == ChronoModel.Phase.INPUT) m.notes.map { n -> m.colors.indexOfFirst { it.name == n } } else "-"
                is UltraModel -> "Ultrasequencer round=${m.round} phase=${m.phase} k=${m.k} pending=${m.pending.size} off=${m.off} expect=" +
                    if (m.phase == UltraModel.Phase.INPUT) (1..m.round).map { m.nums[it] } else "-"
                is SuperpairsModel -> "Superpairs state=${m.state} open=${m.open} partner=${m.partner()} pairs=${m.pairs()} matched=${m.matched.size} " +
                    "clicks=${m.remaining} final=${m.final} unknown=${m.grid.filter { m.hidden(it) && it !in m.mem }}"
                else -> ""
            }
        if (line == lastTrace) return
        lastTrace = line
        if (line.isNotEmpty()) logger.info("State {}", line)
    }

    // ---------------------------------------------------------------- packets (client thread)

    @JvmStatic
    fun onOpen(packet: ClientboundOpenScreenPacket) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) return
        if (!on()) {
            flow.close()
            return
        }
        val menu = mc.player?.containerMenu as? ChestMenu
        val rows = if (menu != null && menu.containerId == packet.containerId) menu.rowCount else 0
        cid = packet.containerId
        stacks = Array(rows * 9) { ItemStack.EMPTY }
        seen.clear()
        cache.clear()
        val sp = flow.menu?.model as? SuperpairsModel
        flow.open(packet.containerId, ExpItem.strip(packet.title.string), rows, System.currentTimeMillis())
        if (flow.menu?.model !== sp) rareHeard = 0
        flow.menu?.let { logger.info("Open {} \"{}\" ({} rows)", it.kind, it.title, rows) }
    }

    @JvmStatic
    fun onSlot(packet: ClientboundContainerSetSlotPacket) {
        if (!Minecraft.getInstance().isSameThread || flow.menu == null || packet.containerId != cid) return
        val slot = packet.slot
        if (slot !in stacks.indices) return
        val stack = packet.item.copy()
        stacks[slot] = stack
        remember(slot, stack)
        val before = flow.menu?.state
        flow.slot(packet.containerId, slot, item(stack), System.currentTimeMillis())
        afterChange(before)
    }

    @JvmStatic
    fun onContents(packet: ClientboundContainerSetContentPacket) {
        if (!Minecraft.getInstance().isSameThread || flow.menu == null || packet.containerId() != cid) return
        val items = packet.items()
        for (i in stacks.indices) {
            stacks[i] = items.getOrNull(i)?.copy() ?: ItemStack.EMPTY
            remember(i, stacks[i])
        }
        val before = flow.menu?.state
        flow.contents(packet.containerId(), stacks.map { item(it) }, System.currentTimeMillis())
        afterChange(before)
    }

    @JvmStatic
    fun onClose(packet: ClientboundContainerClosePacket) {
        if (!Minecraft.getInstance().isSameThread || packet.containerId != cid) return
        flow.close()
    }

    /** `ClientPacketListenerMixin.handleSoundEvent` HEAD, both passes; only the client-thread pass counts. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!Minecraft.getInstance().isSameThread || flow.menu == null) return
        flow.sound(packet.sound.value().location().path, packet.pitch, System.currentTimeMillis())
    }

    private fun afterChange(before: ExpFlow.State?) {
        val m = flow.menu ?: return
        if (before != m.state && m.state == ExpFlow.State.FAILED) logger.info("\"{}\" left to the vanilla menu: {}", m.title, m.why)
        if (before == ExpFlow.State.PENDING && m.state == ExpFlow.State.ACTIVE) {
            logger.info("Recognized \"{}\"", m.title)
            (Minecraft.getInstance().gui.screen() as? ContainerScreen)?.let { onInit(it) }
        }
    }

    private fun remember(slot: Int, stack: ItemStack) {
        if (stack.isEmpty) return
        val id = BuiltInRegistries.ITEM.getKey(stack.item).path
        if (id.endsWith("_stained_glass_pane") || id == "cyan_stained_glass") return
        seen[slot] = stack
    }

    // ---------------------------------------------------------------- screen

    /** A Soul screen exists for these; the RNG Meter pages are only read. */
    fun ownsScreen(kind: Kind): Boolean = kind != Kind.RNG

    /** Vanilla's screen opens before the contents arrive: ours replaces it once the menu is recognized (and after any re-init). */
    private fun onInit(screen: Screen) {
        if (screen !is ContainerScreen || !on()) return
        val m = flow.menu ?: return
        if (m.cid != screen.menu.containerId || !ownsScreen(m.kind) || m.state != ExpFlow.State.ACTIVE) return
        val mc = Minecraft.getInstance()
        mc.execute { if (mc.gui.screen() === screen && flow.menu === m && m.state == ExpFlow.State.ACTIVE) mc.gui.setScreen(ExperimentScreen(screen, m)) }
    }

    /**
     * One physical press on [slot] of [menu]: judged in games (blocked = nothing sent), then exactly one
     * container click through vanilla `handleContainerInput`, now. Returns whether a click was sent.
     */
    fun press(menu: ExpFlow.Menu, slot: Int, click: Click, judge: Boolean): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        if (player.containerMenu.containerId != menu.cid || flow.menu !== menu) return false
        val now = System.currentTimeMillis()
        if (judge) {
            val v = flow.press(slot, now)
            if (v is Verdict.Block && cfg.experiments.blockClicks) {
                logger.info("Blocked slot {} in \"{}\": {}", slot, menu.title, v.text)
                SoulSounds.play(BLOCK_SOUND)
                return false
            }
            if (v is Verdict.Block) menu.blocked = null
        }
        gameMode.handleContainerInput(menu.cid, slot, click.button, click.input, player)
        logger.info("Sent {}/{} on slot {} in \"{}\" (+{} ms)", click.input, click.button, slot, menu.title, now - menu.openedAt)
        return true
    }

    // ---------------------------------------------------------------- items

    fun item(stack: ItemStack): ExpItem? {
        if (stack.isEmpty) return null
        cache[stack]?.let { return it }
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val sbId = tag?.getStringOr("id", "")?.ifEmpty { null }
        var petType: String? = null
        var petTier: String? = null
        if (sbId == "PET") {
            val info = tag.getString("petInfo").orElse("") ?: ""
            petType = Regex("\"type\"\\s*:\\s*\"([A-Z_]+)\"").find(info)?.groupValues?.get(1)
            petTier = Regex("\"tier\"\\s*:\\s*\"([A-Z_]+)\"").find(info)?.groupValues?.get(1)
            if (petType == null) {
                tag.getCompound("petInfo").ifPresent { c ->
                    petType = c.getStringOr("type", "").ifEmpty { null }
                    petTier = c.getStringOr("tier", "").ifEmpty { null }
                }
            }
        }
        val out =
            ExpItem(
                id = BuiltInRegistries.ITEM.getKey(stack.item).path,
                count = stack.count,
                name = stack.hoverName.toLegacyText(),
                lore = stack.get(DataComponents.LORE)?.lines()?.map { it.toLegacyText() }.orEmpty(),
                glint = stack.hasFoil(),
                texture = headTexture(stack),
                dye = stack.get(DataComponents.DYED_COLOR)?.rgb()?.and(0xFFFFFF),
                sbId = sbId,
                petType = petType,
                petTier = petTier,
            )
        cache[stack] = out
        return out
    }

    /** The last path segment of a head's SKIN url (what identifies a Hypixel head); bounded. */
    private fun headTexture(stack: ItemStack): String? =
        try {
            val profile = stack.get(DataComponents.PROFILE)?.partialProfile()
            val value = profile?.properties()?.get("textures")?.firstOrNull()?.value()?.takeIf { it.length <= 4096 }
            value?.let {
                val text = String(Base64.getDecoder().decode(it), Charsets.UTF_8)
                Regex("\"SKIN\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.substringAfterLast('/')
            }
        } catch (_: Exception) {
            null
        }
}
