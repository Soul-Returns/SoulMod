package com.soulreturns.features.storage

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.hypixel.LegacyItemDecoder
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SelfProfileLoaded
import com.soulreturns.data.skyblock.SelfProfileSnapshot
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** Session-only API previews. Observed contents and backpack invalidation always take precedence. */
internal object StorageApiPrefill {
    private var registered = false
    private var onChanged: () -> Unit = {}
    private var source: SelfProfileSnapshot? = null
    private var sourceScope: StorageApiScope? = null
    private var decodedPages: List<StoredStoragePage> = emptyList()
    private var generation = 0L
    private var decodeJob: Job? = null

    fun register(onChanged: () -> Unit) {
        this.onChanged = onChanged
        if (registered) return
        registered = true
        Events.subscribe<ProfileChanged> { clear() }
        // The API only has production pages; Alpha shows only what its own menus showed.
        Events.subscribe<PlayerDataset.Resolved> { if (it.switched) clear() }
        Events.subscribe<SelfProfileLoaded> { prefill(it.snapshot) }
        SkyblockSelfProfile.snapshot?.let(::prefill)
    }

    private fun clear() {
        generation++
        decodeJob?.cancel()
        source = null
        sourceScope = null
        decodedPages = emptyList()
        onChanged()
    }

    /** Client-thread access; a missing exact join-chat profile id never falls back to a cute name. */
    fun pages(account: UUID, profile: UUID): List<StoredStoragePage> {
        SkyblockSelfProfile.snapshot?.let(::prefill)
        val scope = sourceScope ?: return emptyList()
        return if (scope.account == account && scope.profile == profile && active(scope)) decodedPages else emptyList()
    }

    private fun active(scope: StorageApiScope): Boolean =
        scope.matches(Minecraft.getInstance().user.profileId, SkyblockProfileState.profileId)

    private fun prefill(snapshot: SelfProfileSnapshot) {
        if (!cfg.storageOverlay.enabled || source === snapshot || SkyblockSelfProfile.snapshot !== snapshot) return
        val scope = StorageApiScope.from(snapshot.uuidUndashed, snapshot.profileId) ?: return
        if (!active(scope)) return
        val mc = Minecraft.getInstance()
        val registries = mc.level?.registryAccess() ?: return
        val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
        if (sourceScope != scope) decodedPages = emptyList()
        source = snapshot
        sourceScope = scope
        val mine = ++generation
        decodeJob?.cancel()
        decodeJob = SoulScope.io.launch {
            val decoded = try {
                storageApiPages(
                    snapshot.member.raw,
                    decode = { blob ->
                        ensureActive()
                        val slots = LegacyItemDecoder.decodeSlots(blob)
                        if (slots.isNullOrEmpty()) null else {
                            val failed = HashSet<Int>()
                            val encoded = slots.mapIndexed { index, stack ->
                                if (stack == null) { failed += index; null }
                                else if (stack.isEmpty) null
                                else ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null)?.toString() ?: run { failed += index; null }
                            }
                            ApiItems(encoded, failed)
                        }
                    },
                    identity = { blob ->
                        ensureActive()
                        val icon = LegacyItemDecoder.decodeComplete(blob)?.singleOrNull()
                        MojangUuid.parse(icon?.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("uuid")?.orElse(null))?.toString()
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            ensureActive()
            mc.execute {
                if (generation != mine || source !== snapshot || SkyblockSelfProfile.snapshot !== snapshot || !active(scope)) return@execute
                decodedPages = decoded
                onChanged()
            }
        }
    }
}

internal data class StorageApiScope(val account: UUID, val profile: UUID) {
    private val profileId = profile.toString()

    fun matches(account: UUID, profileId: String?): Boolean =
        this.account == account && this.profileId.equals(profileId, ignoreCase = true)

    companion object {
        fun from(account: String?, profile: String?): StorageApiScope? {
            return StorageApiScope(
                MojangUuid.parse(account) ?: return null,
                MojangUuid.parse(profile) ?: return null,
            )
        }
    }
}

/** One decoded API blob: encoded stacks per slot (null = air or [failed]); a failed slot is never air. */
internal class ApiItems(val items: List<String?>, val failed: Set<Int> = emptySet())

/**
 * Wire fields/indexing cross-checked against SkyCrypt's src/stats/items.js, not copied code. All Ender Chest
 * pages share one blob, so a slot that does not convert costs only its own page's preview: that page stays
 * known (items null, "Not viewed yet") instead of every page vanishing. A backpack with one is skipped whole.
 */
internal fun storageApiPages(
    member: JsonObject,
    decode: (String) -> ApiItems?,
    identity: (String) -> String? = { null },
): List<StoredStoragePage> {
    val inventory = member["inventory"] as? JsonObject ?: return emptyList()
    fun blob(value: kotlinx.serialization.json.JsonElement?): String? {
        val data = (value as? JsonObject)?.get("data") as? JsonPrimitive ?: return null
        return data.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
    fun contents(value: kotlinx.serialization.json.JsonElement?): ApiItems? = blob(value)?.let(decode)
    return buildList {
        val ender = contents(inventory["ender_chest_contents"])
        if (ender != null && ender.items.size in 9..405 && ender.items.size % 9 == 0) {
            for ((index, items) in ender.items.chunked(45).withIndex()) {
                val complete = (index * 45 until index * 45 + items.size).none { it in ender.failed }
                add(StoredStoragePage(StoragePageId("ender", index + 1), "Ender Chest ${index + 1}", items.size / 9, items = items.takeIf { complete }))
            }
        }
        val backpacks = inventory["backpack_contents"] as? JsonObject ?: return@buildList
        val icons = inventory["backpack_icons"] as? JsonObject
        // Keys are zero-based slot numbers, not a dense list or a detected 0/1-based convention.
        for (slot in 0..17) {
            val key = slot.toString()
            val decoded = contents(backpacks[key]) ?: continue
            val items = decoded.items
            if (decoded.failed.isNotEmpty() || items.size !in 9..45 || items.size % 9 != 0) continue
            val uuid = blob(icons?.get(key))?.let(identity)
            add(StoredStoragePage(StoragePageId("backpack", slot + 1), "Backpack ${slot + 1}", items.size / 9, uuid, items))
        }
    }
}
