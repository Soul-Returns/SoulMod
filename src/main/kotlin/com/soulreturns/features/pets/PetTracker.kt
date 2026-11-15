package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.data.skyblock.TabPet
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A pet icon: what a 16 px head looks like (see `InventoryButtonIcons.ItemLook`). */
@Serializable
data class PetIconLook(val item: String, val skin: String = "", val model: String = "", val glint: Boolean = false)

/**
 * What is known about one pet, cached per profile. Fields from the profile API carry `…Live = false`;
 * live reads (menus, chat, tab) set it and their time, and the API never overwrites a live value
 * newer than its snapshot ([PetTracker.API_STALE_MS]).
 */
@Serializable
data class PetRecord(
    val uuid: String? = null,
    val type: String? = null,
    val name: String = "",
    /** Displayed rarity ([SkyblockRarity] name), Tier Boost applied. */
    val rarity: String? = null,
    val skinned: Boolean = false,
    /** Last known level, 0 = unknown. */
    val level: Int = 0,
    /** Total XP. */
    val exp: Double? = null,
    val expAt: Long = 0L,
    val expLive: Boolean = false,
    val held: PetHeld? = null,
    /** [held] is known (null then means "holds nothing"). */
    val heldKnown: Boolean = false,
    val heldAt: Long = 0L,
    val heldLive: Boolean = false,
    val icon: PetIconLook? = null,
    /** The profile API flagged it as the summoned pet. */
    val apiActive: Boolean = false,
    val seenAt: Long = 0L,
)

/** The `pets` stats namespace, one bucket per SkyBlock profile. */
@Serializable
data class PetProfileData(
    /** Records by pet uuid, or `name:<lowercase name>` until a uuid is known. */
    val pets: Map<String, PetRecord> = emptyMap(),
    /** Record key of the last active pet; null (with [none] false) = unknown. */
    val active: String? = null,
    val none: Boolean = false,
)

/** One pet of `members.<uuid>.pets_data.pets[]`. */
data class ApiPet(val uuid: String?, val type: String, val exp: Double, val tier: String?, val heldItem: String?, val skin: String?, val active: Boolean)

/** What the HUD draws for the active pet. */
data class PetView(
    val name: String,
    val rarity: SkyblockRarity?,
    val skinned: Boolean,
    /** Null when only the name is known (a summon before the tab confirms it). */
    val level: Int?,
    val maxLevel: Int,
    val maxed: Boolean,
    /** XP into the level and the level's need; null when unknown. */
    val into: Double?,
    val need: Double?,
    /** XP past max level, at max. */
    val overflow: Double?,
    /** Total XP, when level and progress are known. */
    val total: Double?,
    val held: PetHeld?,
    val icon: PetIconLook?,
    /** XP/h while levelling (gaining in the last 90 s and not maxed), else null. */
    val rate: Double?,
) {
    val fraction: Double? get() = if (maxed) 1.0 else if (into != null && need != null && need > 0) (into / need).coerceIn(0.0, 1.0) else null
}

/** HUD effects the tracker asks for (badges, swap slide, level-up glow). */
sealed interface PetFx {
    /** Another pet (or none) took the place; [badge] AUTOPET / SUMMONED, or null for a quiet swap. */
    data class Swap(val badge: String?) : PetFx

    data class LevelUp(val from: Int, val to: Int, val maxed: Boolean) : PetFx
}

/**
 * The active pet and per-pet cache, from every source (research §8, owner decisions §9). Pure: no MC,
 * no clock (every call takes `now`), storage through [load]/[save].
 *
 * Precedence:
 * 1. **Chat** (autopet / summon / despawn) asserts a swap at once and parks tab reads for [PARK_MS]:
 *    the tab follows 0.7–2.5 s later and can miss a fast swap entirely. A tab read that agrees (same
 *    pet name, or none) applies right away (XP updates); one that disagrees waits, and wins at the end
 *    of the park only if the tab changed after the assertion.
 * 2. **Tab** is the truth for level and XP otherwise. A missing widget (Catacombs) never clears.
 * 3. **Menus** (Pets `petInfo`, Stats & Equipment slot 47) are live for held item, icon, exact XP, uuid,
 *    and name the active pet when they show it.
 * 4. **Profile API** seeds records at start; it never overrides a live value newer than its snapshot,
 *    and names the active pet only while nothing live did this session.
 *
 * Level-up chat counts only for the active pet's name (Exp Share pets level too).
 */
class PetTracker(
    private val load: () -> PetProfileData,
    private val save: (PetProfileData) -> Unit,
    /** Held item id → name + color (the item catalog in game). */
    private val heldName: (String) -> PetHeld = { PetHeld(PetItems.titleOf(it)) },
) {
    var fx: (PetFx) -> Unit = {}

    var state: Int = UNKNOWN
        private set

    private data class Active(val key: String, val name: String, val rarity: SkyblockRarity?, val skinned: Boolean, val level: Int?)

    private data class Progress(val level: Int, val into: Double?, val need: Double?, val maxed: Boolean, val overflow: Double?)

    private var active: Active? = null

    /** Last progress per pet name (memory only), so a swap back shows the bar before the tab confirms. */
    private val progress = HashMap<String, Progress>()

    private val rates = HashMap<String, PetXpRate>()

    /** Whether something live named the active pet this session (else it was restored or API-seeded). */
    private var activeLive = false

    private var parkedUntil = 0L
    private var assertedAt = 0L
    private var lastTab: TabPet? = null
    private var lastTabAt = 0L

    /** When level-up chat raised the active pet's level; a tab still showing the old level is ignored for [PARK_MS]. */
    private var levelChatAt = 0L
    private val lastExpSaveAt = HashMap<String, Long>()

    /** Forget the session (profile switch) and take the stored active pet, if any. */
    fun restore() {
        active = null
        progress.clear()
        rates.clear()
        activeLive = false
        parkedUntil = 0L
        assertedAt = 0L
        lastTab = null
        lastTabAt = 0L
        levelChatAt = 0L
        lastExpSaveAt.clear()
        val data = load()
        val rec = data.active?.let { data.pets[it] }
        if (rec != null) {
            active = Active(data.active, rec.name, rarity(rec), rec.skinned, rec.level.takeIf { it > 0 })
            state = ACTIVE
        } else {
            state = if (data.none) NONE else UNKNOWN
        }
    }

    fun record(key: String): PetRecord? = load().pets[key]

    // ------------------------------------------------------------------ chat

    fun onChat(line: PetChat.Line, now: Long) {
        if (line is PetChat.Autopet) {
            val key = keyForName(line.name)
            update(key, now) {
                var r = copy(name = line.name, level = line.level, skinned = line.skinned, rarity = line.rarity?.name ?: rarity)
                if (line.held != null) r = r.copy(held = line.held, heldKnown = true, heldAt = now, heldLive = true)
                r
            }
            assertActive(Active(key, line.name, line.rarity ?: rarity(record(key)), line.skinned, line.level), now, "AUTOPET")
        } else if (line is PetChat.Summon) {
            val key = keyForName(line.name)
            update(key, now) { copy(name = line.name, skinned = line.skinned, rarity = line.rarity?.name ?: rarity) }
            val level = record(key)?.level?.takeIf { it > 0 }
            assertActive(Active(key, line.name, line.rarity ?: rarity(record(key)), line.skinned, level), now, "SUMMONED")
        } else if (line is PetChat.Despawn) {
            val wasActive = state == ACTIVE
            active = null
            state = NONE
            activeLive = true
            park(now)
            persistActive()
            if (wasActive) fx(PetFx.Swap(null))
        } else if (line is PetChat.LevelUp) {
            val a = active ?: return
            if (state != ACTIVE || !a.name.equals(line.name, ignoreCase = true)) return
            val from = a.level ?: return run { setLevel(a, line.level, now) }
            if (line.level <= from) return
            setLevel(a, line.level, now)
            levelChatAt = now
            fx(PetFx.LevelUp(from, line.level, line.level >= PetLevels.maxLevel(a.name)))
        } else if (line is PetChat.Holding) {
            val a = active ?: return
            update(a.key, now) { copy(held = line.held, heldKnown = true, heldAt = now, heldLive = true) }
        }
    }

    private fun setLevel(a: Active, level: Int, now: Long) {
        active = a.copy(level = level)
        val max = PetLevels.maxLevel(a.name)
        val p = if (level >= max) Progress(level, null, null, true, 0.0) else Progress(level, 0.0, PetLevels.need(level, a.rarity, a.name).toDouble(), false, null)
        progress[a.name] = p
        update(a.key, now) { copy(level = level) }
    }

    private fun assertActive(next: Active, now: Long, badge: String) {
        val same = state == ACTIVE && active?.name == next.name
        active = next
        state = ACTIVE
        activeLive = true
        park(now)
        persistActive()
        if (!same) fx(PetFx.Swap(badge))
    }

    private fun park(now: Long) {
        parkedUntil = now + PARK_MS
        assertedAt = now
    }

    // ------------------------------------------------------------------ tab

    /** A tab read of the Pet widget: [pet] null = `No pet selected`. Call only once the widget was seen. */
    fun onTab(pet: TabPet?, now: Long) {
        lastTab = pet
        lastTabAt = now
        if (now < parkedUntil && !agrees(pet)) return
        val a = active
        if (pet != null && state == ACTIVE && a != null && a.name == pet.name && a.level != null && pet.level < a.level && now - levelChatAt < PARK_MS) return
        applyTab(pet, now)
    }

    /** Ends a park: a tab that changed after the chat assertion and still disagrees wins. */
    fun tick(now: Long) {
        if (parkedUntil == 0L || now < parkedUntil) return
        parkedUntil = 0L
        if (lastTabAt > assertedAt && !agrees(lastTab)) applyTab(lastTab, now)
    }

    private fun agrees(pet: TabPet?): Boolean =
        if (pet == null) state == NONE else state == ACTIVE && active?.name == pet.name

    private fun applyTab(pet: TabPet?, now: Long) {
        if (pet == null) {
            val was = state
            active = null
            state = NONE
            activeLive = true
            persistActive()
            if (was == ACTIVE) fx(PetFx.Swap(null))
            return
        }
        val prev = active.takeIf { state == ACTIVE }
        val key = if (prev != null && prev.name == pet.name) prev.key else keyForName(pet.name)
        val rarity = pet.rarity ?: rarity(record(key))
        val next = Active(key, pet.name, rarity, pet.skinned, pet.level)
        active = next
        state = ACTIVE
        activeLive = true

        val p = tabProgress(pet, rarity, record(key))
        if (p != null) progress[pet.name] = p
        val total = p?.let { total(pet.name, rarity, it) }
        if (p != null && total != null && !p.maxed) rates.getOrPut(pet.name) { PetXpRate() }.add(now, total)

        val savedAt = lastExpSaveAt[key]
        val saveExp = total != null && (savedAt == null || now - savedAt >= EXP_SAVE_MS)
        if (saveExp) lastExpSaveAt[key] = now
        update(key, now) {
            var r = copy(name = pet.name, level = pet.level, skinned = pet.skinned, rarity = rarity?.name ?: this.rarity)
            if (saveExp) r = r.copy(exp = total, expAt = now, expLive = true)
            r
        }
        persistActive()

        if (prev == null || prev.name != pet.name) {
            fx(PetFx.Swap(null))
        } else if (prev.level != null && pet.level > prev.level) {
            fx(PetFx.LevelUp(prev.level, pet.level, pet.level >= PetLevels.maxLevel(pet.name)))
        }
    }

    private fun tabProgress(pet: TabPet, rarity: SkyblockRarity?, rec: PetRecord?): Progress? {
        if (pet.maxed) {
            val overflow = pet.xp ?: rec?.exp?.let { PetLevels.levelOf(it, rarity, pet.name).takeIf { l -> l.maxed }?.overflow }
            return Progress(pet.level, null, null, true, overflow)
        }
        val into = pet.xp ?: return fromExp(pet.name, pet.level, rarity, rec)
        val table = PetLevels.need(pet.level, rarity, pet.name).toDouble()
        val shown = pet.xpForNext
        // The table is exact; the tab's need is abbreviated (179.7k). Trust the tab only when they disagree.
        val need = if (shown == null || table <= 0 || abs(table - shown) <= shown * 0.01) table else shown
        return Progress(pet.level, into, need, false, null)
    }

    private fun fromExp(name: String, level: Int?, rarity: SkyblockRarity?, rec: PetRecord?): Progress? {
        val exp = rec?.exp ?: return null
        val l = PetLevels.levelOf(exp, rarity, name)
        if (level != null && l.level != level) return null
        return if (l.maxed) Progress(l.level, null, null, true, l.overflow) else Progress(l.level, l.into, l.need.toDouble(), false, null)
    }

    private fun total(name: String, rarity: SkyblockRarity?, p: Progress): Double? =
        if (p.maxed) {
            p.overflow?.let { PetLevels.levelXp(PetLevels.maxLevel(name), rarity, name) + it }
        } else {
            p.into?.let { PetLevels.levelXp(p.level, rarity, name) + it }
        }

    // ------------------------------------------------------------------ menus

    /**
     * A pet item read in the Pets menu, the Loadouts menu (slot 21, the equipped pet) or the Stats &
     * Equipment pet slot. [equipped] = the equipment slot (always the active pet); elsewhere
     * `petInfo.active` says so. A swap read here parks the tab like chat does: the Pet widget lags.
     */
    fun onMenuPet(item: PetItem, icon: PetIconLook?, equipped: Boolean, now: Long) {
        val key = item.uuid?.let { mergeInto(it, item.name) } ?: keyForName(item.name)
        val held = item.held ?: item.heldId?.let(heldName)
        update(key, now) {
            var r = copy(
                uuid = item.uuid ?: uuid, type = item.type ?: type, name = item.name, level = item.level,
                rarity = item.rarity?.name ?: rarity, skinned = item.skinned,
                held = held, heldKnown = true, heldAt = now, heldLive = true,
                icon = icon ?: this.icon,
            )
            if (item.exp != null) r = r.copy(exp = item.exp, expAt = now, expLive = true)
            r
        }
        if (item.exp != null) {
            val rec = record(key)
            fromExp(item.name, item.level, item.rarity ?: rarity(rec), rec)?.let { progress[item.name] = it }
        }
        if (!(equipped || item.active)) return
        val next = Active(key, item.name, item.rarity ?: rarity(record(key)), item.skinned, item.level)
        val prev = active.takeIf { state == ACTIVE }
        if (prev == next) return
        active = next
        state = ACTIVE
        activeLive = true
        persistActive()
        if (prev?.name != next.name) {
            park(now)
            fx(PetFx.Swap(null))
        }
    }

    // ------------------------------------------------------------------ profile API

    /** Seed from `pets_data.pets[]` of a snapshot loaded at [loadedAt]. */
    fun onApi(pets: List<ApiPet>, loadedAt: Long) {
        val data = load()
        val map = LinkedHashMap(data.pets)
        val names = pets.groupingBy { PetItems.titleOf(it.type).lowercase() }.eachCount()
        var apiActiveKey: String? = null
        for (p in pets) {
            val name = PetItems.titleOf(p.type)
            val key = p.uuid ?: continue
            // A chat-only record of this name becomes this pet when the API has just one of that name.
            val nameKey = nameKey(name)
            var rec = map[key] ?: PetRecord(uuid = key, name = name)
            if (names[name.lowercase()] == 1) map.remove(nameKey)?.let { rec = merge(rec, it) }
            var rarity = SkyblockRarity.forName(p.tier)
            if (p.heldItem == "PET_ITEM_TIER_BOOST" && rarity != null) rarity = SkyblockRarity.entries.getOrNull(rarity.ordinal + 1) ?: rarity
            val staleBefore = loadedAt - API_STALE_MS
            if (!(rec.expLive && rec.expAt > staleBefore)) {
                rec = rec.copy(exp = p.exp, expAt = loadedAt, expLive = false, level = PetLevels.levelOf(p.exp, rarity, name).level)
            }
            if (!(rec.heldLive && rec.heldAt > staleBefore)) {
                rec = rec.copy(held = p.heldItem?.let(heldName), heldKnown = true, heldAt = loadedAt, heldLive = false)
            }
            rec = rec.copy(
                uuid = key, type = p.type, name = rec.name.ifEmpty { name },
                rarity = rec.rarity ?: rarity?.name, skinned = rec.skinned || p.skin != null, apiActive = p.active,
            )
            map[key] = rec
            if (p.active) apiActiveKey = key
            if (active?.key == nameKey && map[nameKey] == null) active = active?.copy(key = key)
        }
        var next = data.copy(pets = map, active = if (data.active != null && map[data.active] == null) active?.key else data.active)
        if (!activeLive && apiActiveKey != null) {
            val rec = map.getValue(apiActiveKey)
            val prev = active.takeIf { state == ACTIVE }
            active = Active(apiActiveKey, rec.name, rarity(rec), rec.skinned, rec.level.takeIf { it > 0 })
            state = ACTIVE
            next = next.copy(active = apiActiveKey, none = false)
            if (prev != null && prev.name != rec.name) fx(PetFx.Swap(null))
        }
        save(next)
    }

    // ------------------------------------------------------------------ view

    fun view(now: Long): PetView? {
        val a = active ?: return null
        if (state != ACTIVE) return null
        val rec = record(a.key)
        val max = PetLevels.maxLevel(a.name)
        val p = progress[a.name]?.takeIf { a.level == null || it.level == a.level } ?: fromExp(a.name, a.level, a.rarity, rec)
        val level = a.level ?: p?.level
        val maxed = p?.maxed ?: (level != null && level >= max)
        val total = p?.let { total(a.name, a.rarity, it) }
        val rate = if (maxed) null else rates[a.name]?.perHour(now)
        return PetView(
            name = a.name,
            rarity = a.rarity,
            skinned = a.skinned,
            level = level,
            maxLevel = max,
            maxed = maxed,
            into = p?.into,
            need = p?.need,
            overflow = p?.overflow,
            total = total,
            held = rec?.held?.takeIf { rec.heldKnown },
            icon = rec?.icon,
            rate = rate,
        )
    }

    // ------------------------------------------------------------------ records

    private fun rarity(rec: PetRecord?): SkyblockRarity? = SkyblockRarity.forName(rec?.rarity)

    private fun nameKey(name: String): String = "name:" + name.lowercase()

    /** The record for a pet known only by name: the active one, else the most recently seen of that name. */
    private fun keyForName(name: String): String {
        active?.let { if (it.name.equals(name, ignoreCase = true)) return it.key }
        val pets = load().pets
        return pets.entries.filter { it.value.name.equals(name, ignoreCase = true) }
            .maxWithOrNull(compareBy<Map.Entry<String, PetRecord>>({ it.value.apiActive }, { it.value.seenAt }))?.key
            ?: nameKey(name)
    }

    /** [uuid]'s key, after folding a chat-only record of [name] into it. */
    private fun mergeInto(uuid: String, name: String): String {
        val data = load()
        val named = data.pets[nameKey(name)] ?: return uuid
        val merged = merge(data.pets[uuid] ?: PetRecord(uuid = uuid, name = name), named)
        val pets = LinkedHashMap(data.pets).apply {
            remove(nameKey(name))
            put(uuid, merged)
        }
        val repoint = data.active == nameKey(name)
        save(data.copy(pets = pets, active = if (repoint) uuid else data.active))
        if (active?.key == nameKey(name)) active = active?.copy(key = uuid)
        return uuid
    }

    /** [base] with the newer fields of [other]. */
    private fun merge(base: PetRecord, other: PetRecord): PetRecord {
        var r = base
        if (other.expAt > r.expAt && other.exp != null) r = r.copy(exp = other.exp, expAt = other.expAt, expLive = other.expLive)
        if (other.heldAt > r.heldAt && other.heldKnown) r = r.copy(held = other.held, heldKnown = true, heldAt = other.heldAt, heldLive = other.heldLive)
        if (other.seenAt > r.seenAt) {
            r = r.copy(seenAt = other.seenAt, level = if (other.level > 0) other.level else r.level, rarity = other.rarity ?: r.rarity, skinned = other.skinned)
        }
        if (r.icon == null) r = r.copy(icon = other.icon)
        return r
    }

    private fun update(key: String, now: Long, block: PetRecord.() -> PetRecord) {
        val data = load()
        val old = data.pets[key] ?: PetRecord(uuid = key.takeUnless { it.startsWith("name:") })
        val new = old.block()
        if (new == old) return
        save(data.copy(pets = LinkedHashMap(data.pets).apply { put(key, new.copy(seenAt = now)) }))
    }

    private fun persistActive() {
        val data = load()
        val key = active?.key.takeIf { state == ACTIVE }
        val none = state == NONE
        if (data.active == key && data.none == none) return
        save(data.copy(active = key, none = none))
    }

    companion object {
        const val UNKNOWN = 0
        const val NONE = 1
        const val ACTIVE = 2

        /** Tab reads that disagree with a chat assertion wait this long (SkyHanni uses 5 s too). */
        const val PARK_MS = 5_000L

        /** The profile API is minutes stale: a live value seen up to this long before its snapshot still wins. */
        const val API_STALE_MS = 10 * 60_000L

        /** Tab XP lands in the stored record at most this often (the in-memory progress is live). */
        const val EXP_SAVE_MS = 60_000L
    }
}
