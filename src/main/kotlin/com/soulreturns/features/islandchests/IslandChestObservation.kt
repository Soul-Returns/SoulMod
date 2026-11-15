package com.soulreturns.features.islandchests

internal data class ChestObservationContext(val account: String, val profile: String, val generation: Long)

internal data class ChestTopology(val blockId: String, val menuOrder: List<ChestPos>, val customTitle: String? = null) {
    val blocks: List<ChestPos> = menuOrder.sortedWith(chestPositionOrder)
    val size: Int get() = blocks.size * 27
    fun canonicalSlots(slots: List<StoredChestItem?>): List<StoredChestItem?> =
        if (blocks.size == 2 && menuOrder.first() != blocks.first()) slots.drop(27) + slots.take(27) else slots.toList()

    fun matches(title: String): Boolean = title == customTitle || title == if (blocks.size == 2) "Large Chest" else "Chest"
}

/** Starts with a complete server snapshot, then follows vanilla's live view and server corrections. */
internal class IslandChestObservation {
    private class Pending(val context: ChestObservationContext, val topology: ChestTopology, val position: ChestPos, val at: Long) {
        @Volatile var confirmed = false
    }
    private class Open(val context: ChestObservationContext, val topology: ChestTopology, val container: Int) {
        var slots: List<StoredChestItem?>? = null
        var locatingSlots: BooleanArray? = null
    }
    @Volatile private var pending: Pending? = null
    private var open: Open? = null
    private var selected: StoredChestItem? = null
    val containerId: Int? get() = open?.container
    val activeBlocks: List<ChestPos> get() = open?.topology?.blocks.orEmpty()

    fun reset() { pending = null; open = null }
    fun close() { open = null }
    fun cancelPending() { pending = null }
    fun select(item: StoredChestItem?) {
        selected = item
        open?.let(::updateLocatingSlots)
    }

    fun menuSlots(context: ChestObservationContext?, container: Int): List<StoredChestItem?>? = valid(context, container)?.slots

    fun locatingSlots(context: ChestObservationContext?, container: Int): BooleanArray? = valid(context, container)?.locatingSlots

    fun pendingExpired(now: Long): Boolean = pending?.let { now - it.at > 1500 } == true
    fun arm(context: ChestObservationContext, topology: ChestTopology?, now: Long, position: ChestPos? = topology?.menuOrder?.firstOrNull()) {
        open = null
        pending = if (topology != null && position != null && position in topology.blocks) Pending(context, topology, position, now) else null
    }

    fun confirm(position: ChestPos, now: Long) {
        val candidate = pending ?: return
        if (candidate.position == position && now - candidate.at in 0..1500) candidate.confirmed = true
    }

    fun open(context: ChestObservationContext?, container: Int, size: Int, title: String, now: Long): Boolean {
        val candidate = pending
        reset()
        if (candidate == null || !candidate.confirmed || context != candidate.context || now - candidate.at !in 0..1500) return false
        if (size != candidate.topology.size || !candidate.topology.matches(title)) return false
        open = Open(candidate.context, candidate.topology, container)
        return true
    }

    fun full(context: ChestObservationContext?, container: Int, items: List<StoredChestItem?>, now: Long, observerName: String?): StoredIslandChest? {
        val current = valid(context, container) ?: return null
        if (items.size != current.topology.size + 36) return null
        current.slots = items.take(current.topology.size)
        updateLocatingSlots(current)
        return snapshot(current, now, observerName)
    }

    fun slot(context: ChestObservationContext?, container: Int, slot: Int, item: StoredChestItem?, now: Long, observerName: String?): StoredIslandChest? {
        val current = valid(context, container) ?: return null
        val slots = current.slots ?: return null
        if (slot !in slots.indices || slots[slot] == item) return null
        current.slots = slots.toMutableList().also { it[slot] = item }
        current.locatingSlots?.set(slot, item != null && selected?.sameSearchIdentity(item) == true)
        return snapshot(current, now, observerName)
    }

    fun menu(context: ChestObservationContext?, container: Int, items: List<StoredChestItem?>, now: Long, observerName: String?): StoredIslandChest? {
        val current = valid(context, container) ?: return null
        val slots = current.slots ?: return null
        if (items.size != slots.size || items == slots) return null
        current.slots = items.toList()
        updateLocatingSlots(current)
        return snapshot(current, now, observerName)
    }

    private fun updateLocatingSlots(current: Open) {
        val item = selected
        val slots = current.slots
        current.locatingSlots = if (item == null || slots == null) null else
            BooleanArray(slots.size) { index -> slots[index]?.let(item::sameSearchIdentity) == true }
    }

    private fun valid(context: ChestObservationContext?, container: Int): Open? {
        val current = open ?: return null
        if (current.context != context) { reset(); return null }
        return current.takeIf { it.container == container }
    }

    private fun snapshot(current: Open, now: Long, observerName: String?): StoredIslandChest = StoredIslandChest(
        key = chestKey(current.topology.blocks), blocks = current.topology.blocks, blockId = current.topology.blockId,
        slots = current.topology.canonicalSlots(current.slots!!), observerUuid = current.context.account,
        observerName = observerName, observedAt = now,
    )
}
