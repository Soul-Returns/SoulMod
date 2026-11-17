package com.soulreturns.features.islandchests

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.FreshProfileId
import com.soulreturns.data.skyblock.OnSkyblockChanged
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.PacketTap
import com.soulreturns.features.search.SearchItems
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendAuthenticated
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.platform.realtime.SyncInvalidate
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import java.time.Instant
import java.util.UUID

object IslandChests {
    private const val FILE = "island-chests.json"

    private var registered = false
    private var loaded = false
    private var stopping = false
    private var generation = 0L
    private var account: String? = null
    private var accountId: UUID? = null
    private val freshProfile = FreshProfileId()
    private var identity: ChestObservationContext? = null
    private var lastLevel: Any? = null
    private var armedPosition: BlockPos? = null
    private var armedTopology: ChestTopology? = null
    private var watchingUse = false
    private val useSink = PacketTap.Sink { inbound, packet ->
        if (!inbound && packet is ServerboundUseItemOnPacket && packet.hand == InteractionHand.MAIN_HAND) {
            val position = packet.hitResult.blockPos
            observation.confirm(ChestPos(position.x, position.y, position.z), System.currentTimeMillis())
        }
    }
    private val observation = IslandChestObservation()
    private var trackedMenu: AbstractContainerMenu? = null
    private var menuStacks: Array<ItemStack>? = null
    private var observationBaseRevision: Long? = null
    private var previousObservationId: String? = null
    private val index = IslandChestSearchIndex()
    private var locatingKey: String? = null
    private var lastBucket: IslandChestBucket? = null
    private var visibleChests: List<StoredIslandChest> = emptyList()
    private var lastSharing = false
    private var remoteState = "local"
    private var ticks = 0
    private var saveJob: Job? = null
    private var realtimeSince: Instant? = null
    @Volatile private var syncContext: IslandChestSyncContext? = null
    private var lastSyncIdentity: IslandChestSyncContext? = null
    @Volatile private var snapshot = IslandChestView()
    private val unavailableView = IslandChestView()
    private lateinit var store: IslandChestStore
    private lateinit var sync: IslandChestSync

    fun register() {
        if (registered) return
        registered = true
        accountId = Minecraft.getInstance().user.profileId
        account = MojangUuid.undashed(accountId!!)
        sync = IslandChestSync({ store }, { syncContext }, ::publish) { remoteState = it; publish() }
        openStore()
        PlayerDataset.attach(
            object : PlayerDataset.Store {
                override fun flush() = store.flush()

                override fun open(dataset: String) = openStore()
            },
        )
        Events.subscribe<ChatMessage> { message ->
            if (message.source != ChatMessage.Source.SERVER) return@subscribe
            updateContext()
            if (freshProfile.offer(MessageDetector.stripColorCodes(message.raw).trim(), Minecraft.getInstance().level)) {
                updateContext()
                publish()
            }
        }
        Events.subscribe<HypixelModApi.LocationChangeEvent> { transfer() }
        Events.subscribe<ProfileChanged> { transfer() }
        Events.subscribe<OnSkyblockChanged> { if (!it.current) transfer() }
        Events.subscribe<BackendAuthenticated> { onClient { sync.refresh() } }
        Events.subscribe<PlayerDataset.Resolved> { updateContext(); publish(); sync.refresh() }
        Events.subscribe<SyncInvalidate> { event ->
            onClient {
                val expected = account?.let { "user/$it" }
                if (event.topic == expected && event.kind == "island-chests") sync.refresh()
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> transfer() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onClient { transfer() } }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, hit ->
            val mc = Minecraft.getInstance()
            if (hand == InteractionHand.MAIN_HAND && player === mc.player && level === mc.level) {
                updateContext()
                val context = captureContext()
                val topology = if (context != null && mc.gui.screen() == null) topology(hit.blockPos) else null
                clearMenuTracking()
                clearArm()
                val at = System.currentTimeMillis()
                armedPosition = if (topology != null) hit.blockPos.immutable() else null
                armedTopology = topology
                if (context != null && topology != null) {
                    watchingUse = true
                    PacketTap.register(useSink)
                    observation.arm(context, topology, at, ChestPos(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z))
                } else resetObservation()
            }
            InteractionResult.PASS
        })
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            stopping = true
            syncContext = null
            resetObservation()
            clearArm()
            store.flush()
        }
    }

    /** The current profile's remembered chests, anywhere on SkyBlock; [IslandChestView.ownIsland] gates locating. */
    fun view(): IslandChestView = if (searchContext() != null) snapshot else unavailableView
    fun search(query: String): List<ChestSearchResult> =
        if (captureContext() == null) emptyList() else index.search(snapshot.chests, query)
    fun startLocating(itemKey: String) {
        val row = search("").firstOrNull { it.key == itemKey }
        locatingKey = row?.key
        observation.select(row?.item)
    }
    fun clearLocating() {
        locatingKey = null
        observation.select(null)
    }
    internal fun locatingSlots(containerId: Int): BooleanArray? {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread || locatingKey == null || trackedMenu == null ||
            mc.player?.containerMenu !== trackedMenu || trackedMenu?.containerId != containerId) return null
        return observation.locatingSlots(captureContext(), containerId)
    }
    fun locating(): ChestSearchResult? {
        if (captureContext() == null) return null
        val key = locatingKey ?: return null
        return search("").firstOrNull { it.key == key }
    }
    fun refresh() {
        if (!registered) return
        onClient { updateContext(); publish(); sync.refresh() }
    }

    private fun transfer() {
        generation++
        freshProfile.reset()
        identity = null
        syncContext = null
        resetObservation()
        clearArm()
        clearLocating()
        lastBucket = null
        // Sync state survives hops: the sync resumes when the same account + profile resolves again.
        if (loaded) publish()
    }

    /** Opens the dataset's file ([PlayerDataset]); the previous store was flushed or dropped by the switch. */
    private fun openStore() {
        val next = IslandChestStore(PlayerDataset.file(FILE))
        store = next
        loaded = false
        // Not transfer(): the join chat's profile id stays valid for the same server.
        generation++
        identity = null
        syncContext = null
        resetObservation()
        clearArm()
        clearLocating()
        lastBucket = null
        SoulScope.io.launch {
            next.load()
            Minecraft.getInstance().execute {
                if (store !== next) return@execute
                loaded = true
                updateContext()
                publish()
                sync.refresh()
            }
        }
    }

    private fun updateContext() {
        val mc = Minecraft.getInstance()
        val nextAccountId = mc.user.profileId
        if (accountId != nextAccountId) {
            accountId = nextAccountId
            account = MojangUuid.undashed(nextAccountId)
            transfer()
        }
        if (lastLevel !== mc.level) {
            lastLevel = mc.level
            generation++
            freshProfile.reset()
            resetObservation()
            clearArm()
        }
        val profile = freshProfile.resolved(lastLevel, SkyblockProfileState.profileId)?.let(MojangUuid::undashedOrNull)
        val old = identity
        val next = if (loaded && SkyblockState.onSkyblock && profile != null && mc.player != null) {
            if (old != null && old.account == account && old.profile == profile && old.generation == generation) old
            else ChestObservationContext(account!!, profile, generation)
        } else null
        if (identity != next) {
            identity = next
            resetObservation()
            clearArm()
            clearLocating()
            lastBucket = null
        }
        val readable = searchContext()
        // Alpha chests stay in the Alpha dataset; an unconfirmed connection waits for its sidebar.
        val nextSync = IslandChestGates.sync(readable, cfg.islandChests.shareWithCoop && PlayerDataset.confirmedProduction(), syncContext)
        if (nextSync != null && nextSync != lastSyncIdentity) {
            lastSyncIdentity = nextSync
            remoteState = "local"
        }
        syncContext = nextSync
        if (!cfg.islandChests.enabled || !ownIsland()) {
            resetObservation()
            clearArm()
        }
    }

    /** The player's own private island (not a visit), where chests are observed and located. */
    internal fun onOwnIsland(): Boolean = ownIsland()

    private fun ownIsland(): Boolean = SkyblockState.onSkyblock && LocationApi.mode == "dynamic" &&
        SkyblockLocation.area == "Private Island" && SkyblockLocation.subLocation == "Your Island" &&
        SkyblockState.profileType != ProfileType.GUEST && SkyblockState.profileType != ProfileType.UNKNOWN

    private fun captureContext(): ChestObservationContext? = IslandChestGates.observable(searchContext(), ownIsland())

    /** The exact profile's cache, readable and synced anywhere on SkyBlock; observing and locating also need [ownIsland]. */
    private fun searchContext(): ChestObservationContext? {
        val mc = Minecraft.getInstance()
        val fresh = mc.user.profileId == accountId && mc.level === lastLevel &&
            freshProfile.resolved(lastLevel, SkyblockProfileState.profileId) != null
        return IslandChestGates.readable(identity, fresh, cfg.islandChests.enabled, SkyblockState.onSkyblock, stopping)
    }

    private fun tick() {
        if (!registered) return
        updateContext()
        val mc = Minecraft.getInstance()
        if (watchingUse && observation.pendingExpired(System.currentTimeMillis())) clearArm()
        if (observation.containerId != null && mc.player?.containerMenu?.containerId != observation.containerId) {
            observation.close()
            clearMenuTracking()
            sync.refresh()
        }
        publish()
        if (++ticks % 20 != 0 || !loaded || stopping) return
        val connectedAt = RealtimeClient.status().connectedSince
        if (connectedAt != realtimeSince) {
            realtimeSince = connectedAt
            if (connectedAt != null) sync.refresh()
        }
        sync.tick(System.currentTimeMillis(), observation.activeBlocks)
        if (store.hasChanges() && PlayerDataset.writable() && saveJob?.isActive != true) saveJob = SoulScope.io.launch { store.flush() }
    }

    private fun publish() {
        val current = searchContext()
        val sharing = cfg.islandChests.enabled && cfg.islandChests.shareWithCoop
        val bucket = if (current != null && loaded) store.read(current.account, current.profile) else null
        if (bucket == null) {
            lastBucket = null
            lastSharing = sharing
            visibleChests = emptyList()
        } else if (bucket !== lastBucket || sharing != lastSharing) {
            lastBucket = bucket
            lastSharing = sharing
            visibleChests = bucket.visible(sharing)
        }
        val name = if (current != null) SkyblockProfileState.current else null
        val ironman = current != null && SkyblockState.profileType == ProfileType.IRONMAN
        val own = current != null && ownIsland()
        val state = if (syncContext != null) remoteState else "local"
        val old = snapshot
        if (old.profileId == current?.profile && old.profileName == name && old.ironman == ironman && old.ownIsland == own &&
            old.sharingEnabled == sharing && old.syncState == state && old.chests === visibleChests) return
        snapshot = IslandChestView(current?.profile, name, ironman, own, sharing, state, visibleChests)
    }

    private fun remember(chest: StoredIslandChest?) {
        val context = captureContext() ?: return
        if (chest == null) return
        val originalBaseline = observationBaseRevision ?: return
        val id = UUID.randomUUID().toString()
        val previousId = previousObservationId
        store.update(context.account, context.profile) { bucket ->
            // Only an ACK may have advanced this opening's pending predecessor; a GET must not rebase it.
            val baseline = bucket.local.firstOrNull { it.observationId == previousId }?.baseRevision ?: originalBaseline
            bucket.observe(LocalChestObservation(id, baseline, chest))
        }
        previousObservationId = id
        publish()
    }

    @JvmStatic
    fun onOpen(packet: ClientboundOpenScreenPacket) {
        if (!registered || !Minecraft.getInstance().isSameThread) return
        updateContext()
        val position = armedPosition
        val expected = armedTopology
        val resolved = position?.let(::topology)
        val size = if (packet.type === MenuType.GENERIC_9x3) 27 else if (packet.type === MenuType.GENERIC_9x6) 54 else 0
        val context = if (expected != null && resolved == expected) captureContext() else null
        val opened = observation.open(context, packet.containerId, size, MessageDetector.stripColorCodes(packet.title.string), System.currentTimeMillis())
        clearMenuTracking()
        if (opened && context != null) observationBaseRevision = store.read(context.account, context.profile).revision
        clearArm()
    }

    private fun resetObservation() {
        observation.reset()
        clearMenuTracking()
    }

    private fun clearMenuTracking() {
        trackedMenu = null
        menuStacks = null
        observationBaseRevision = null
        previousObservationId = null
    }

    private fun clearArm() {
        observation.cancelPending()
        armedPosition = null
        armedTopology = null
        if (watchingUse) {
            watchingUse = false
            PacketTap.unregister(useSink)
        }
    }

    @JvmStatic
    fun onContents(packet: ClientboundContainerSetContentPacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread || mc.player?.containerMenu?.containerId != packet.containerId()) return
        updateContext()
        val size = observation.activeBlocks.size * 27
        if (size == 0 || packet.items().size != size + 36) return
        val items = packet.items().mapIndexed { i, stack -> if (i < size) storedItem(stack) else null }
        val chest = observation.full(captureContext(), packet.containerId(), items, System.currentTimeMillis(), mc.user.name) ?: return
        trackedMenu = mc.player!!.containerMenu
        menuStacks = Array(size) { packet.items()[it].copy() }
        remember(chest)
    }

    @JvmStatic
    fun onSlot(packet: ClientboundContainerSetSlotPacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread || mc.player?.containerMenu?.containerId != packet.containerId) return
        updateContext()
        if (packet.slot !in 0 until observation.activeBlocks.size * 27) return
        if (trackedMenu === mc.player?.containerMenu && observation.containerId == packet.containerId) {
            menuStacks?.set(packet.slot, packet.item.copy())
        }
        remember(observation.slot(captureContext(), packet.containerId, packet.slot, storedItem(packet.item), System.currentTimeMillis(), mc.user.name))
    }

    @JvmStatic
    fun onContainerInput(containerId: Int) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread) return
        updateContext()
        val menu = mc.player?.containerMenu ?: return
        if (menu !== trackedMenu || menu.containerId != containerId) return
        val context = captureContext()
        val slots = observation.menuSlots(context, containerId) ?: return
        val stacks = menuStacks ?: return
        var changed: MutableList<StoredChestItem?>? = null
        for (slot in stacks.indices) {
            val stack = menu.getSlot(slot).item
            if (ItemStack.matches(stacks[slot], stack)) continue
            stacks[slot] = stack.copy()
            val items = changed ?: slots.toMutableList().also { changed = it }
            items[slot] = storedItem(stack)
        }
        changed?.let {
            // Accepted vanilla predictions are not echoed; corrections already replace the same live view.
            remember(observation.menu(context, containerId, it, System.currentTimeMillis(), mc.user.name))
        }
    }

    @JvmStatic
    fun onClose(packet: ClientboundContainerClosePacket) {
        if (!registered || !Minecraft.getInstance().isSameThread || observation.containerId != packet.containerId) return
        observation.close()
        clearMenuTracking()
        sync.refresh()
    }

    private fun storedItem(stack: ItemStack): StoredChestItem? = SearchItems.of(stack)

    private fun topology(position: BlockPos): ChestTopology? {
        val level = Minecraft.getInstance().level ?: return null
        if (!level.hasChunkAt(position)) return null
        val state = level.getBlockState(position)
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        if (blockId != "minecraft:chest" && blockId != "minecraft:trapped_chest") return null
        val type = state.getValue(ChestBlock.TYPE)
        fun pos(p: BlockPos) = ChestPos(p.x, p.y, p.z)
        if (type == ChestType.SINGLE) {
            val customName = (level.getBlockEntity(position) as? ChestBlockEntity)?.customName?.string
            return ChestTopology(blockId, listOf(pos(position)), customName)
        }
        val other = ChestBlock.getConnectedBlockPos(position, state)
        if (!level.hasChunkAt(other)) return null
        val neighbor = level.getBlockState(other)
        if (neighbor.block !== state.block || neighbor.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING) ||
            neighbor.getValue(ChestBlock.TYPE) == type || neighbor.getValue(ChestBlock.TYPE) == ChestType.SINGLE ||
            ChestBlock.getConnectedBlockPos(other, neighbor) != position) return null
        // Vanilla's CompoundContainer starts with RIGHT, independent of which half was clicked.
        val order = if (type == ChestType.RIGHT) listOf(position, other) else listOf(other, position)
        val customName = order.firstNotNullOfOrNull { (level.getBlockEntity(it) as? ChestBlockEntity)?.customName?.string }
        return ChestTopology(blockId, order.map(::pos), customName)
    }

    private fun onClient(block: () -> Unit) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) block() else mc.execute { block() }
    }
}
