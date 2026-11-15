package com.soulreturns.features.storage

import kotlinx.serialization.Serializable
import net.minecraft.world.item.ItemStack
import java.util.UUID

@Serializable
data class StoragePageId(val kind: String, val index: Int)

/** [preview]: contents come from the SkyBlock API only; the page was never opened on this client. */
data class StoragePageView(val id: StoragePageId, val label: String, val rows: Int, val items: List<ItemStack>?, val open: Boolean, val preview: Boolean = false)

@Serializable
internal data class StoredStoragePage(
    val id: StoragePageId,
    val label: String,
    val rows: Int,
    val identity: String? = null,
    val items: List<String?>? = null,
)

@Serializable
internal data class StorageSelector(val id: StoragePageId, val state: String, val identity: String? = null, val rows: Int = 4)

@Serializable
internal data class StorageBucket(
    val pages: List<StoredStoragePage> = emptyList(),
    val selectors: List<StorageSelector> = emptyList(),
    val lastPage: StoragePageId? = null,
) {
    fun observe(page: StoredStoragePage): StorageBucket =
        copy(pages = pages.filterNot { it.id == page.id } + page, lastPage = page.id)

    fun openingCommand(command: String): String {
        if (!command.trim().equals("storage", ignoreCase = true)) return command
        val id = lastPage ?: return command
        if (selectors.any { it.id == id && it.state != "installed" }) return command
        if (id.kind == "ender" && id.index in 1..9) return "enderchest ${id.index}"
        if (id.kind == "backpack" && id.index in 1..18) return "backpack ${id.index}"
        return command
    }

    fun invalidateBackpacks(ids: Set<StoragePageId>? = null): StorageBucket = copy(pages = pages.map {
        if (it.id.kind == "backpack" && (ids == null || it.id in ids)) it.copy(items = null) else it
    })

    fun reconcile(next: List<StorageSelector>): StorageBucket {
        val selectorsById = next.associateBy { it.id }
        return copy(selectors = next, pages = pages.mapNotNull { page ->
            val selector = selectorsById[page.id]
            if (selector == null || selector.state != "installed") null
            else if (page.id.kind == "backpack" && (selector.identity == null || selector.identity != page.identity)) page.copy(identity = selector.identity, items = null)
            else page
        })
    }
}

internal data class StorageMenu(val page: StoragePageId?, val rows: Int, val label: String) {
    val size: Int get() = rows * 9
}

internal object StorageMenus {
    private val ender = Regex("Ender Chest \\(([1-9])/9\\)", RegexOption.IGNORE_CASE)
    private val backpack = Regex("([A-Za-z]+) Backpack \\(Slot #([1-9]|1[0-8])\\)", RegexOption.IGNORE_CASE)
    fun parse(title: String, rows: Int): StorageMenu? {
        if (title.equals("Storage", ignoreCase = true) && rows == 6) return StorageMenu(null, rows, title)
        ender.matchEntire(title)?.let { if (rows == 6) return StorageMenu(StoragePageId("ender", it.groupValues[1].toInt()), rows, "Ender Chest ${it.groupValues[1]}") }
        backpack.matchEntire(title)?.let {
            if (rows in 2..6) return StorageMenu(StoragePageId("backpack", it.groupValues[2].toInt()), rows, "${it.groupValues[1]} Backpack ${it.groupValues[2]}")
        }
        return null
    }
}

internal fun storageScope(account: String?, profile: String?): String? {
    fun normalize(value: String?): String? {
        val raw = value ?: return null
        if (!raw.matches(Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) return null
        val dashed = if (raw.length == 32) "${raw.substring(0, 8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20)}" else raw
        return UUID.fromString(dashed).toString()
    }
    return "${normalize(account) ?: return null}/${normalize(profile) ?: return null}"
}

/** Full server contents establish the baseline; neither predictions nor isolated slots can do so. */
internal class StorageObservation<T> {
    var containerId: Int? = null
        private set
    var description: StorageMenu? = null
        private set
    var contents: List<T>? = null
        private set
    fun open(id: Int, menu: StorageMenu) { containerId = id; description = menu; contents = null }
    fun clear() { containerId = null; description = null; contents = null }
    fun full(id: Int, values: List<T>): Boolean {
        val menu = description ?: return false
        if (id != containerId || values.size != menu.size + 36) return false
        contents = values.take(menu.size)
        return true
    }
    fun slot(id: Int, slot: Int, value: T): Boolean {
        val old = contents ?: return false
        if (id != containerId || slot !in old.indices) return false
        contents = old.toMutableList().also { it[slot] = value }
        return true
    }
    fun predicted(id: Int, values: List<T>): Boolean {
        val old = contents ?: return false
        if (id != containerId || values.size != old.size || old == values) return false
        contents = values.toList()
        return true
    }
}
