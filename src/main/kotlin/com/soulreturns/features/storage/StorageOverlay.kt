package com.soulreturns.features.storage

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.FreshProfileId
import com.soulreturns.data.skyblock.OnSkyblockChanged
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.PacketTap
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.event.Event
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import java.util.UUID

object StorageOverlay {
    private val logger = SoulLogger("Soul/Storage")
    private val observation = StorageObservation<ItemStack>()
    private var registered = false
    private var loaded = false
    private var stopping = false
    private lateinit var store: StorageStore
    private var saveJob: Job? = null
    private var ticks = 0
    private var account: String? = null
    private var accountId: UUID? = null
    private var level: Any? = null
    private val freshProfile = FreshProfileId()
    private var scope: String? = null
    private var scopeProfile: String? = null
    private var trackedMenu: ChestMenu? = null
    private val invalidatedApiBackpacks = mutableSetOf<StoragePageId>()
    private var watchingInputs = false
    private val inputSink = PacketTap.Sink { inbound, packet ->
        if (!inbound && packet is ServerboundContainerClickPacket && Minecraft.getInstance().isSameThread) observeManagementInput(packet)
    }
    private var pending: StoragePageId? = null
    private var managing = false
    private var preservedMenu: ChestMenu? = null
    private var transitionScreen: StorageOverlayScreen? = null
    private var bucketSeen: StorageBucket? = null
    private var snapshot: List<StoragePageView> = emptyList()
    private var summary = "Storage slots not observed yet"
    private var searchMark: SearchMark? = null
    private const val SEARCH_MARK_TIMEOUT_MS = 10_000L

    fun register() {
        if (registered) return
        registered = true
        StorageApiPrefill.register { bucketSeen = null; publish() }
        openStore()
        PlayerDataset.attach(
            object : PlayerDataset.Store {
                override fun flush() = store.flush()

                override fun open(dataset: String) = openStore()
            },
        )
        val commandPhase = Identifier.fromNamespaceAndPath("soul", "last_storage_page")
        // Run before other storage overlays can redirect the bare /storage command.
        ClientSendMessageEvents.MODIFY_COMMAND.addPhaseOrdering(commandPhase, Event.DEFAULT_PHASE)
        ClientSendMessageEvents.MODIFY_COMMAND.register(commandPhase) { command ->
            if (!cfg.storageOverlay.openLastPage || !Minecraft.getInstance().isSameThread) command
            else {
                updateContext()
                if (validContext()) store.read(scope!!).openingCommand(command) else command
            }
        }
        Events.subscribe<ChatMessage> { message ->
            if (message.source != ChatMessage.Source.SERVER) return@subscribe
            updateContext()
            if (freshProfile.offer(MessageDetector.stripColorCodes(message.raw).trim(), Minecraft.getInstance().level)) updateContext()
        }
        Events.subscribe<ProfileChanged> { resetIdentity() }
        Events.subscribe<OnSkyblockChanged> { if (!it.current) resetIdentity() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetIdentity() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onClient { resetIdentity() } }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { stopping = true; clearSession(); store.flush() }
    }

    fun scopeKey(): String? = scope.takeIf { validContext() }
    fun pages(): List<StoragePageView> = if (validContext()) snapshot else emptyList()
    fun pendingPage(): StoragePageId? = pending.takeIf { validContext() }
    fun selectorSummary(): String = if (validContext()) summary else "Storage unavailable"
    fun preservingMenu(menu: ChestMenu): Boolean = menu === preservedMenu

    fun interactionReady(menu: ChestMenu): Boolean = contextReady(menu) && observation.description?.page != null
    fun overviewReady(menu: ChestMenu): Boolean = contextReady(menu) && observation.description?.page == null

    private fun contextReady(menu: ChestMenu): Boolean = validContext() && menu === trackedMenu &&
        Minecraft.getInstance().player?.containerMenu === menu && observation.contents != null && pending == null && !managing

    @JvmStatic
    fun shouldOverrideSkyblocker(title: String): Boolean {
        if (!registered || !Minecraft.getInstance().isSameThread || !cfg.storageOverlay.enabled || !cfg.storageOverlay.overrideSkyblocker) return false
        updateContext()
        if (!validContext()) return false
        val plain = clean(title)
        return (2..6).any { StorageMenus.parse(plain, it) != null }
    }

    fun requestOpen(id: StoragePageId): Boolean {
        updateContext()
        val mc = Minecraft.getInstance()
        val menu = trackedMenu ?: return false
        if (!contextReady(menu) || !menu.carried.isEmpty || mc.gui.screen() !is StorageOverlayScreen) return false
        if (observation.description?.page == id || snapshot.none { it.id == id }) return false
        val command = pageCommand(id) ?: return false
        val connection = mc.connection ?: return false
        pending = id
        publish()
        connection.sendCommand(command)
        return true
    }

    private fun pageCommand(id: StoragePageId): String? = when (id.kind) {
        "ender" -> "enderchest ${id.index}"
        "backpack" -> "backpack ${id.index}"
        else -> null
    }

    /** The item Search everywhere opened [page] for; the overlay marks its stacks there. */
    class SearchMark(val page: StoragePageId, val itemKey: String, val item: StoredChestItem, val count: Long, val at: Long) {
        var shown = false
    }

    fun searchMark(): SearchMark? = searchMark.takeIf { validContext() }

    /** Search everywhere's Open: the overlay's own page command, sent from outside a storage menu. */
    fun openFromSearch(id: StoragePageId, itemKey: String, item: StoredChestItem, count: Long): Boolean {
        updateContext()
        val mc = Minecraft.getInstance()
        if (!validContext() || snapshot.none { it.id == id } || mc.player?.containerMenu !== mc.player?.inventoryMenu) return false
        val command = pageCommand(id) ?: return false
        val connection = mc.connection ?: return false
        searchMark = SearchMark(id, itemKey, item, count, System.currentTimeMillis())
        connection.sendCommand(command)
        return true
    }

    fun manage() {
        updateContext()
        val mc = Minecraft.getInstance()
        val menu = trackedMenu ?: return
        if (!contextReady(menu) || !menu.carried.isEmpty || mc.gui.screen() !is StorageOverlayScreen) return
        if (observation.description?.page == null) {
            managing = true
            swap(ManagementScreen(menu, mc.player!!.inventory, Component.literal("Storage")), menu)
        } else {
            if (clean(menu.getSlot(1).item.hoverName.string) != "Back") return
            val gameMode = mc.gameMode ?: return
            managing = true
            gameMode.handleContainerInput(menu.containerId, 1, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player!!)
        }
    }

    @JvmStatic
    fun onOpen(packet: ClientboundOpenScreenPacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread) return
        updateContext()
        val screen = mc.gui.screen() as? ContainerScreen
        val menu = screen?.menu
        val description = menu?.let { StorageMenus.parse(clean(packet.title.string), it.rowCount) }
        stopWatchingInputs()
        observation.clear()
        trackedMenu = null
        bucketSeen = null
        if (!validContext() || screen == null || screen is StorageOverlayScreen || menu == null ||
            menu.containerId != packet.containerId || mc.player?.containerMenu !== menu || description == null) {
            pending = null
            managing = false
            publish()
            return
        }
        trackedMenu = menu
        observation.open(packet.containerId, description)
        if (description.page == null) watchInputs()
        publish()
        if (!managing && menu.carried.isEmpty) {
            swap(StorageOverlayScreen(menu, mc.player!!.inventory, screen.title), menu)
        }
    }

    @JvmStatic
    fun onContents(packet: ClientboundContainerSetContentPacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread) return
        updateContext()
        val menu = trackedMenu ?: return
        val description = observation.description ?: return
        if (!validContext() || mc.player?.containerMenu !== menu || packet.containerId() != menu.containerId ||
            packet.items().size != description.size + 36) return
        if (!supportedContents(description, packet.items())) {
            if (mc.gui.screen() is StorageOverlayScreen) {
                swap(ContainerScreen(menu, mc.player!!.inventory, mc.gui.screen()!!.title), menu)
            }
            observation.clear()
            trackedMenu = null
            pending = null
            managing = false
            stopWatchingInputs()
            bucketSeen = null
            publish()
            return
        }
        observation.full(packet.containerId(), packet.items().map { it.copy() })
        observeContents()
        if (description.page == pending) pending = null
        val screen = mc.gui.screen() as? ContainerScreen
        if (screen != null && screen.menu === menu && screen !is StorageOverlayScreen && screen !is ManagementScreen && menu.carried.isEmpty) {
            if (managing && description.page == null) swap(ManagementScreen(menu, mc.player!!.inventory, screen.title), menu)
            else {
                managing = false
                swap(StorageOverlayScreen(menu, mc.player!!.inventory, screen.title), menu)
            }
        }
        bucketSeen = null
        publish()
    }

    @JvmStatic
    fun onSlot(packet: ClientboundContainerSetSlotPacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread) return
        updateContext()
        val description = observation.description ?: return
        if (observation.contents == null || packet.slot !in 0 until description.size) return
        if (!validContext() || mc.player?.containerMenu !== trackedMenu || !observation.slot(packet.containerId, packet.slot, packet.item.copy())) return
        if (observation.description?.page == null || packet.slot >= 9) observeContents()
        publish()
    }

    private fun supportedContents(description: StorageMenu, items: List<ItemStack>): Boolean {
        if (description.page != null) return clean(items[0].hoverName.string) == "Close" && clean(items[1].hoverName.string) == "Back"
        return clean(items[4].hoverName.string) == "Ender Chest" && readSelectors(items).any { it.id.kind == "ender" && it.state == "installed" }
    }

    @JvmStatic
    fun onContainerInput(containerId: Int) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread) return
        updateContext()
        val menu = trackedMenu ?: return
        val old = observation.contents ?: return
        if (!validContext() || mc.player?.containerMenu !== menu || menu.containerId != containerId) return
        if (observation.description?.page == null) return
        var changed = false
        for (slot in old.indices) if (!ItemStack.matches(old[slot], menu.getSlot(slot).item)) { changed = true; break }
        if (!changed) return
        observation.predicted(containerId, List(old.size) { menu.getSlot(it).item.copy() })
        observeContents()
        publish()
    }

    @JvmStatic
    fun beforeClose(packet: ClientboundContainerClosePacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread) return
        transitionScreen = if (validContext() && pending != null && observation.containerId == packet.containerId)
            mc.gui.screen() as? StorageOverlayScreen else null
    }

    @JvmStatic
    fun onClose(packet: ClientboundContainerClosePacket) {
        val mc = Minecraft.getInstance()
        if (!registered || !mc.isSameThread || observation.containerId != packet.containerId) return
        val waiting = transitionScreen
        transitionScreen = null
        stopWatchingInputs()
        observation.clear()
        trackedMenu = null
        bucketSeen = null
        publish()
        if (waiting != null && pending != null && validContext() && mc.gui.screen() == null) {
            swap(waiting, waiting.menu)
        }
    }

    private fun observeContents() {
        val current = scope ?: return
        val description = observation.description ?: return
        val items = observation.contents ?: return
        val id = description.page
        if (id == null) store.update(current) { it.reconcile(readSelectors(items)) }
        else {
            val encoded = encodeItems(items.drop(9))
            store.update(current) { bucket ->
                val observed = bucket.observeOpen(id, description.label, description.rows - 1, encoded)
                description.enderPages?.let(observed::knowEnderPages) ?: observed
            }
        }
    }

    private fun readSelectors(items: List<ItemStack>): List<StorageSelector> = buildList {
        for (index in 1..9) {
            val item = items[8 + index]
            val installed = clean(item.hoverName.string) == "Ender Chest Page $index" && lore(item).any { it == "Also accessible via /enderchest $index" }
            add(StorageSelector(StoragePageId("ender", index), if (installed) "installed" else "unknown", rows = 5))
        }
        for (index in 1..18) {
            val item = items[26 + index]
            val name = clean(item.hoverName.string)
            val lines = lore(item)
            val state = when {
                name == "Empty Backpack Slot $index" -> "empty"
                name == "Locked Backpack Slot $index" -> "locked"
                name == "Backpack Slot $index" && lines.any { it == "Also accessible via /backpack $index" } -> "installed"
                else -> "unknown"
            }
            val identity = if (state == "installed") MojangUuid.parse(item.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("uuid")?.orElse(null))?.toString() else null
            val size = lines.firstNotNullOfOrNull { Regex("This backpack has ([0-9]+) slots\\.").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            add(StorageSelector(StoragePageId("backpack", index), state, identity, if (size != null && size in 9..45 && size % 9 == 0) size / 9 else 4))
        }
    }

    private fun encodeItems(items: List<ItemStack>): List<String?>? {
        val level = Minecraft.getInstance().level ?: return null
        val ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess())
        return try {
            items.map { stack ->
                if (stack.isEmpty) null else {
                    val encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null) ?: return null
                    encoded.toString()
                }
            }
        } catch (e: Exception) { logger.warn("Could not encode storage items; retaining an unknown preview", e); null }
    }

    private fun decodeItems(items: List<String?>?): List<ItemStack>? {
        if (items == null) return null
        val level = Minecraft.getInstance().level ?: return null
        val ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess())
        return try {
            items.map { raw ->
                if (raw == null) ItemStack.EMPTY else {
                    val stack = ItemStack.CODEC.parse(ops, TagParser.parseCompoundFully(raw)).result().orElse(null)
                    if (stack == null) { scope?.let(store::unreadableItems); return null }
                    stack
                }
            }
        } catch (_: Exception) { scope?.let(store::unreadableItems); null }
    }

    private fun publish() {
        val current = scope
        if (!loaded || current == null || !validContext()) { snapshot = emptyList(); bucketSeen = null; return }
        val bucket = store.read(current)
        val live = trackedMenu?.takeIf { Minecraft.getInstance().player?.containerMenu === it && observation.contents != null }
        val active = if (live != null && !managing) observation.description?.page else null
        if (bucket === bucketSeen && snapshot.firstOrNull { it.open }?.id == active) return
        bucketSeen = bucket
        val pages = bucket.pages.associateBy { it.id }
        val api = storageApiPreviews(bucket,
            StorageApiPrefill.pages(accountId!!, MojangUuid.parse(joinedProfile()!!)!!), invalidatedApiBackpacks)
        val ids = (bucket.selectors.filter { it.state == "installed" }.map { it.id } + bucket.pages.map { it.id } + api.keys)
            .distinct().sortedWith(compareBy<StoragePageId>({ if (it.kind == "ender") 0 else 1 }, { it.index }))
        snapshot = ids.map { id ->
            val page = pages[id]
            val selector = bucket.selectors.firstOrNull { it.id == id }
            val prefill = api[id]
            val liveItems = if (active == id) observation.contents?.drop(9) else null
            val remembered = if (liveItems != null) null else decodeItems(bucket.remembered(id))
            val preview = if (liveItems == null && remembered == null) decodeItems(prefill?.items) else null
            val contents = liveItems ?: remembered ?: preview
            StoragePageView(id, page?.label ?: prefill?.label ?: if (id.kind == "ender") "Ender Chest ${id.index}" else "Backpack ${id.index}",
                page?.rows ?: selector?.rows ?: prefill?.rows ?: 4, contents, active == id, preview != null)
        }
        val empty = bucket.selectors.count { it.id.kind == "backpack" && it.state == "empty" }
        val locked = bucket.selectors.count { it.id.kind == "backpack" && it.state == "locked" }
        summary = if (bucket.selectors.isEmpty()) "Storage slots not observed yet" else "$empty empty backpack slots · $locked locked"
    }

    private fun validContext(): Boolean {
        val mc = Minecraft.getInstance()
        return registered && loaded && !stopping && cfg.storageOverlay.enabled && SkyblockState.onSkyblock && mc.player != null &&
            mc.level === level && mc.user.profileId == accountId && joinedProfile() != null && scope != null
    }

    /** The profile announced in the current world, while it is still the one the state reader reports. */
    private fun joinedProfile(): String? = freshProfile.resolved(level, SkyblockProfileState.profileId)

    private fun updateContext() {
        if (!registered) return
        val mc = Minecraft.getInstance()
        val nextAccount = mc.user.profileId
        val accountChanged = accountId != nextAccount
        if (accountChanged || level !== mc.level) {
            accountId = nextAccount
            account = nextAccount.toString()
            level = mc.level
            freshProfile.reset()
            scope = null
            clearSession()
        }
        val joined = joinedProfile()
        val next = if (loaded && !stopping && cfg.storageOverlay.enabled && SkyblockState.onSkyblock && mc.player != null && joined != null) {
            if (scope != null && scopeProfile == joined) scope else storageScope(account, joined)
        } else null
        if (scope != next) { scope = next; scopeProfile = joined.takeIf { next != null }; clearSession() }
    }

    private fun resetIdentity() {
        freshProfile.reset()
        scope = null
        clearSession()
    }

    /** Opens the dataset's `storage.json` ([PlayerDataset]); the previous store was flushed or dropped by the switch. */
    private fun openStore() {
        val next = StorageStore(PlayerDataset.file("storage.json"))
        store = next
        loaded = false
        bucketSeen = null
        // Not resetIdentity(): the join chat's profile id stays valid for the same server.
        scope = null
        clearSession()
        SoulScope.io.launch {
            next.load()
            Minecraft.getInstance().execute {
                if (store !== next) return@execute
                loaded = true
                updateContext()
                publish()
            }
        }
    }

    private fun clearSession() {
        observation.clear()
        trackedMenu = null
        pending = null
        transitionScreen = null
        managing = false
        invalidatedApiBackpacks.clear()
        stopWatchingInputs()
        bucketSeen = null
        snapshot = emptyList()
        searchMark = null
    }

    private fun tick() {
        updateContext()
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? ContainerScreen
        if (!validContext() && (screen is StorageOverlayScreen || screen is ManagementScreen)) {
            if (mc.player?.containerMenu === screen.menu) swap(ContainerScreen(screen.menu, mc.player!!.inventory, screen.title), screen.menu)
            else mc.gui.setScreen(null)
        }
        if (trackedMenu != null && mc.player?.containerMenu !== trackedMenu) {
            stopWatchingInputs()
            observation.clear()
            trackedMenu = null
            bucketSeen = null
            if (mc.gui.screen() !is StorageOverlayScreen) { pending = null; managing = false }
        }
        if (pending != null && mc.gui.screen() !is StorageOverlayScreen) pending = null
        searchMark?.let { mark ->
            // Lives while the overlay it opened stays up; a page that never opens drops it.
            if (mc.gui.screen() is StorageOverlayScreen) mark.shown = true
            else if (mark.shown || System.currentTimeMillis() - mark.at > SEARCH_MARK_TIMEOUT_MS) searchMark = null
        }
        readUntrackedOverview()
        publish()
        if (++ticks % 20 == 0 && loaded && !stopping && store.hasChanges() && PlayerDataset.writable() && saveJob?.isActive != true) {
            saveJob = SoulScope.io.launch { store.flush() }
        }
    }

    /** An overview opened before the profile identity resolved stays vanilla; its selectors still count once it does. */
    private fun readUntrackedOverview() {
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? ContainerScreen ?: return
        val menu = screen.menu
        if (trackedMenu != null || screen is StorageOverlayScreen || screen is ManagementScreen || !validContext() ||
            mc.player?.containerMenu !== menu) return
        val description = StorageMenus.parse(clean(screen.title.string), menu.rowCount)?.takeIf { it.page == null } ?: return
        val items = List(description.size) { menu.getSlot(it).item }
        if (supportedContents(description, items)) store.update(scope!!) { it.reconcile(readSelectors(items)) }
    }

    private fun swap(screen: ContainerScreen, menu: ChestMenu) {
        preservedMenu = menu
        try { Minecraft.getInstance().gui.setScreen(screen) } finally { preservedMenu = null }
    }

    private fun clean(text: String): String = MessageDetector.stripColorCodes(text).trim()
    private fun lore(stack: ItemStack): List<String> = stack.get(DataComponents.LORE)?.lines()?.map { clean(it.string) }.orEmpty()
    private fun onClient(action: () -> Unit) { val mc = Minecraft.getInstance(); if (mc.isSameThread) action() else mc.execute { action() } }

    private fun watchInputs() {
        if (watchingInputs) return
        watchingInputs = true
        PacketTap.register(inputSink)
    }

    private fun stopWatchingInputs() {
        if (!watchingInputs) return
        watchingInputs = false
        PacketTap.unregister(inputSink)
    }

    private fun observeManagementInput(packet: ServerboundContainerClickPacket) {
        val menu = trackedMenu ?: return
        if (!validContext() || observation.description?.page != null || packet.containerId() != menu.containerId ||
            Minecraft.getInstance().player?.containerMenu !== menu || observation.contents == null) return
        val slot = packet.slotNum().toInt()
        val selector = store.read(scope!!).selectors.firstOrNull {
            (it.id.kind == "ender" && slot == it.id.index + 8) || (it.id.kind == "backpack" && slot == it.id.index + 26)
        }
        val opensPage = packet.containerInput() == net.minecraft.world.inventory.ContainerInput.PICKUP &&
            packet.buttonNum().toInt() == 0 && menu.carried.isEmpty && selector?.state == "installed"
        if (opensPage) return
        val affected = if (slot in 27..44) setOf(StoragePageId("backpack", slot - 26)) else null
        if (affected == null) for (index in 1..18) invalidatedApiBackpacks.add(StoragePageId("backpack", index))
        else invalidatedApiBackpacks.addAll(affected)
        store.update(scope!!) { it.invalidateBackpacks(affected) }
        bucketSeen = null
        publish()
    }

    private class ManagementScreen(menu: ChestMenu, inventory: Inventory, title: Component) : ContainerScreen(menu, inventory, title) {
        override fun init() {
            super.init()
            addRenderableWidget(Button.builder(Component.literal("Return to overlay")) {
                updateContext()
                if (interactionReadyForReturn(menu)) {
                    managing = false
                    swap(StorageOverlayScreen(menu, minecraft.player!!.inventory, title), menu)
                    publish()
                }
            }.bounds(leftPos + 56, topPos + 1, 100, 16).build())
        }
        override fun removed() { if (!preservingMenu(menu)) super.removed() }
    }

    private fun interactionReadyForReturn(menu: ChestMenu): Boolean = validContext() && trackedMenu === menu &&
        Minecraft.getInstance().player?.containerMenu === menu && menu.carried.isEmpty && observation.contents != null && pending == null
}
