package com.soulreturns.features.storage

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.OnSkyblockChanged
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.PacketTap
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
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
import net.minecraft.resources.RegistryOps
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.item.ItemStack
import java.util.UUID

object StorageOverlay {
    private val logger = SoulLogger("Soul/Storage")
    private val profileNameLine = Regex("You are playing on profile: ([A-Za-z][A-Za-z0-9_]*)(?:\\s*\\([^)]+\\))?")
    private val profileIdLine = Regex("Profile ID: ([0-9a-fA-F-]{32,36})")
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
    private var joinedProfile: String? = null
    private var joinedLevel: Any? = null
    private var joinNameSeen = false
    private var scope: String? = null
    private var scopeProfile: String? = null
    private var trackedMenu: ChestMenu? = null
    private var trustedSelectors: Set<StoragePageId> = emptySet()
    private var watchingInputs = false
    private val inputSink = PacketTap.Sink { inbound, packet ->
        if (!inbound && packet is ServerboundContainerClickPacket && Minecraft.getInstance().isSameThread) observeManagementInput(packet)
    }
    private var pending: StoragePageId? = null
    private var managing = false
    private var preservedMenu: ChestMenu? = null
    private var bucketSeen: StorageBucket? = null
    private var snapshot: List<StoragePageView> = emptyList()
    private var summary = "Storage slots not observed yet"

    fun register() {
        if (registered) return
        registered = true
        store = StorageStore(FabricLoader.getInstance().configDir.resolve("soul/storage.json"))
        SoulScope.io.launch {
            store.load()
            Minecraft.getInstance().execute { loaded = true; updateContext(); publish() }
        }
        Events.subscribe<ChatMessage> { message ->
            if (message.source != ChatMessage.Source.SERVER) return@subscribe
            updateContext()
            val line = MessageDetector.stripColorCodes(message.raw).trim()
            if (profileNameLine.matches(line)) joinNameSeen = true
            profileIdLine.matchEntire(line)?.takeIf { joinNameSeen }?.let {
                joinedProfile = MojangUuid.parse(it.groupValues[1])?.toString()
                joinedLevel = Minecraft.getInstance().level
                updateContext()
            }
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
        val command = when (id.kind) { "ender" -> "enderchest ${id.index}"; "backpack" -> "backpack ${id.index}"; else -> return false }
        val connection = mc.connection ?: return false
        pending = id
        publish()
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
            observation.clear()
            trackedMenu = null
            pending = null
            managing = false
            stopWatchingInputs()
            bucketSeen = null
            publish()
            return
        }
        if (description.page?.kind == "backpack" && description.page !in trustedSelectors && observation.contents == null) {
            store.update(scope!!) { it.observe(StoredStoragePage(description.page, description.label, description.rows - 1)) }
        }
        observation.full(packet.containerId(), packet.items().map { it.copy() })
        observeContents(fullOverview = true)
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
        if (observation.description?.page == null || packet.slot >= 9) observeContents(changedSlot = packet.slot)
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
    fun onClose(packet: ClientboundContainerClosePacket) {
        if (!registered || !Minecraft.getInstance().isSameThread || observation.containerId != packet.containerId) return
        stopWatchingInputs()
        observation.clear()
        trackedMenu = null
        bucketSeen = null
        publish()
    }

    private fun observeContents(fullOverview: Boolean = false, changedSlot: Int? = null) {
        val current = scope ?: return
        val description = observation.description ?: return
        val items = observation.contents ?: return
        val id = description.page
        if (id == null) {
            val selectors = readSelectors(items)
            val verified = selectors.filter { it.id.kind == "backpack" && it.state == "installed" && it.identity != null }.map { it.id }.toSet()
            trustedSelectors = if (fullOverview) verified else {
                val id = changedSlot?.takeIf { it in 27..44 }?.let { StoragePageId("backpack", it - 26) }
                if (id == null) trustedSelectors else (trustedSelectors - id) + verified.filter { it == id }
            }
            store.update(current) { it.reconcile(selectors) }
        } else {
            val bucket = store.read(current)
            val identity = if (id.kind == "backpack" && id in trustedSelectors) bucket.selectors.firstOrNull { it.id == id && it.state == "installed" }?.identity else null
            val encoded = encodeItems(items.drop(9))
            store.update(current) { it.observe(StoredStoragePage(id, description.label, description.rows - 1, identity, encoded)) }
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
        val ids = (bucket.selectors.filter { it.state == "installed" }.map { it.id } + bucket.pages.map { it.id }).distinct().sortedWith(compareBy<StoragePageId>({ if (it.kind == "ender") 0 else 1 }, { it.index }))
        snapshot = ids.map { id ->
            val page = pages[id]
            val selector = bucket.selectors.firstOrNull { it.id == id }
            val liveItems = if (active == id) observation.contents?.drop(9) else null
            // An unverified backpack identity cannot inherit a persisted slot's old contents.
            val remembered = if (liveItems != null || (id.kind == "backpack" && (page?.identity == null || id !in trustedSelectors))) null else decodeItems(page?.items)
            StoragePageView(id, page?.label ?: if (id.kind == "ender") "Ender Chest ${id.index}" else "Backpack ${id.index}", page?.rows ?: selector?.rows ?: 4, liveItems ?: remembered, active == id)
        }
        val empty = bucket.selectors.count { it.id.kind == "backpack" && it.state == "empty" }
        val locked = bucket.selectors.count { it.id.kind == "backpack" && it.state == "locked" }
        summary = if (bucket.selectors.isEmpty()) "Storage slots not observed yet" else "$empty empty backpack slots · $locked locked"
    }

    private fun validContext(): Boolean {
        val mc = Minecraft.getInstance()
        return registered && loaded && !stopping && cfg.storageOverlay.enabled && SkyblockState.onSkyblock && mc.player != null &&
            mc.level === level && joinedLevel === level && mc.user.profileId == accountId &&
            joinedProfile != null && joinedProfile == SkyblockProfileState.profileId && scope != null
    }

    private fun updateContext() {
        if (!registered) return
        val mc = Minecraft.getInstance()
        val nextAccount = mc.user.profileId
        val accountChanged = accountId != nextAccount
        if (accountChanged || level !== mc.level) {
            accountId = nextAccount
            account = nextAccount.toString()
            level = mc.level
            if (accountChanged || joinedLevel !== level) { joinedProfile = null; joinedLevel = null; joinNameSeen = false }
            scope = null
            clearSession()
        }
        val next = if (loaded && !stopping && cfg.storageOverlay.enabled && SkyblockState.onSkyblock && mc.player != null &&
            joinedLevel === level && joinedProfile != null && joinedProfile == SkyblockProfileState.profileId) {
            if (scope != null && scopeProfile == joinedProfile) scope else storageScope(account, joinedProfile)
        } else null
        if (scope != next) { scope = next; scopeProfile = joinedProfile.takeIf { next != null }; clearSession() }
    }

    private fun resetIdentity() {
        joinedProfile = null
        joinedLevel = null
        joinNameSeen = false
        scope = null
        clearSession()
    }

    private fun clearSession() {
        observation.clear()
        trackedMenu = null
        pending = null
        managing = false
        trustedSelectors = emptySet()
        stopWatchingInputs()
        bucketSeen = null
        snapshot = emptyList()
    }

    private fun tick() {
        updateContext()
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen() as? ContainerScreen
        if (!validContext() && (screen is StorageOverlayScreen || screen is ManagementScreen) && mc.player?.containerMenu === screen.menu) {
            swap(ContainerScreen(screen.menu, mc.player!!.inventory, screen.title), screen.menu)
        }
        if (trackedMenu != null && mc.player?.containerMenu !== trackedMenu) {
            stopWatchingInputs()
            observation.clear()
            trackedMenu = null
            bucketSeen = null
            if (mc.gui.screen() != null) { pending = null; managing = false }
        }
        publish()
        if (++ticks % 20 == 0 && loaded && !stopping && store.hasChanges() && saveJob?.isActive != true) saveJob = SoulScope.io.launch { store.flush() }
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
        store.update(scope!!) { it.invalidateBackpacks(affected) }
        trustedSelectors = if (affected == null) emptySet() else trustedSelectors - affected
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
