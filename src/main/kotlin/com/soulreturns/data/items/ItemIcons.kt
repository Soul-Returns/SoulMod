package com.soulreturns.data.items

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import com.mojang.serialization.JsonOps
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons.ItemLook
import com.soulreturns.util.MessageDetector
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import com.soulreturns.data.recipes.RecipeCatalogClient

/**
 * The icon of a SkyBlock item id: an exact look seen in game (sources plug in, [SeenLooks]) > the loaded
 * Hypixel pack's model for the id ([PackModels]) on the catalog material > the `/items` material
 * ([LegacyMaterials]; skull items wear their catalog head skin; ids `/items` lacks may have a NEU head from
 * `/recipes` `heads`) > nothing, and the caller keeps its own fallback. Never a bare player head: without a skin
 * it would be Steve. Ids are looked up, never validated against a list.
 */
object ItemIcons {
    /** Exact looks seen in game, by SkyBlock id. [stamp] must change (by identity) whenever an answer may change. */
    interface SeenLooks {
        val stamp: Any?
        fun look(id: String): ItemLook?
    }

    private const val PAPER = "minecraft:paper"
    private const val PLAYER_HEAD = "minecraft:player_head"
    private const val MAX_CACHED = 512
    private val PUA = Regex("^[\\uE000-\\uF8FF]\\s*")

    private val sources = CopyOnWriteArrayList<SeenLooks>()

    fun addSeen(source: SeenLooks) {
        sources += source
    }

    /**
     * The source order, pure. A seen look whose Hypixel pack model is not loaded now is skipped (it would draw the
     * missing-model checkerboard), and a bare paper look (Hypixel's placeholder material, seen without its model)
     * yields to a pack [model]. [material] is a modern item id or null; [skin] a head profile JSON for skull items.
     */
    internal fun choose(seen: ItemLook?, packHas: (String) -> Boolean, model: String?, material: String?, skin: String?, glint: Boolean): ItemLook? {
        val bare = seen != null && seen.item == PAPER && seen.model.isEmpty()
        if (seen != null && usable(seen, packHas) && !(bare && model != null)) return seen
        if (model != null) return ItemLook(material ?: PAPER, model = model, glint = glint)
        if (material == null) return null
        if (material == PLAYER_HEAD) return skin?.let { ItemLook(material, skin = it, glint = glint) }
        return ItemLook(material, glint = glint)
    }

    /** The catalog's display name without color codes and Hypixel's private-use icon prefix, or null. */
    fun cleanName(displayName: String?): String? =
        displayName?.let { MessageDetector.stripColorCodes(it).replace(PUA, "").trim() }?.takeIf { it.isNotEmpty() }

    /** Pack model and catalogs only (no seen looks); [name] is the display name used to match a model. */
    fun catalogLook(id: String, name: String?): ItemLook? = lookWith(null, id, name)

    /** The full order for [id]; [name] matters only for ids whose model is named after the display name. */
    fun look(id: String, name: String? = null): ItemLook? {
        val models = PackModels.index
        return lookWith(sources.firstNotNullOfOrNull { source -> source.look(id)?.takeIf { usable(it, models::contains) } }, id, name)
    }

    /** [choose] with [seen] (a caller's own seen look) and the catalogs' parts for [id]. */
    fun lookWith(seen: ItemLook?, id: String, name: String?): ItemLook? {
        val entry = ItemCatalogClient.byId(id)
        val shown = name ?: cleanName(entry?.displayName)
        val head = entry?.skin ?: if (entry == null) RecipeCatalogClient.index.heads[id] else null
        val material = if (entry == null && head != null) PLAYER_HEAD
            else LegacyMaterials.itemId(entry?.material, id.substringAfter(':', "").toIntOrNull() ?: entry?.damage ?: 0)
        val skin = head?.let(HeadSkins::profileJson)
        val models = PackModels.index
        return choose(seen, models::contains, models.resolve(id, shown), material, skin, shown?.startsWith("Enchanted ") == true)
    }

    private fun usable(look: ItemLook, packHas: (String) -> Boolean) = !look.model.startsWith("${PackModels.NAMESPACE}:") || packHas(look.model)

    private class Entry(val stack: ItemStack?)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_CACHED
    }
    private var cacheCatalog: Any? = null
    private var cacheRecipes: Any? = null
    private var cacheModels: Any? = null
    private var cacheStamps: List<Any?> = emptyList()

    /**
     * [look] as a stack, cached per id (bounded) and resolved again after a resource reload, a catalog swap or a
     * change in a seen source. Null = no icon known. Any thread.
     */
    fun icon(id: String, name: String? = null): ItemStack? {
        val catalog = ItemCatalogClient.snapshot
        val recipes = RecipeCatalogClient.index
        val models = PackModels.index
        val stamps = sources.map { it.stamp }
        val key = if (name == null) id else "$id\u0000$name"
        synchronized(cache) {
            if (catalog !== cacheCatalog || recipes !== cacheRecipes || models !== cacheModels || !sameStamps(stamps)) {
                cache.clear()
                cacheCatalog = catalog
                cacheRecipes = recipes
                cacheModels = models
                cacheStamps = stamps
            }
            cache[key]?.let { return it.stack }
        }
        val stack = look(id, name)?.let(InventoryButtonIcons::build)
        synchronized(cache) { if (cacheModels === models && cacheCatalog === catalog && cacheRecipes === recipes) cache[key] = Entry(stack) }
        return stack
    }

    private fun sameStamps(stamps: List<Any?>): Boolean =
        stamps.size == cacheStamps.size && stamps.indices.all { stamps[it] === cacheStamps[it] }
}

/** Player-head profiles for Minecraft texture hashes (`textures.minecraft.net/texture/<hash>`). */
object HeadSkins {
    private val HASH = Regex("^[0-9a-f]{16,128}$")

    /** Whether [hash] has the shape of a texture hash; anything else is never turned into a head. */
    fun valid(hash: String?): Boolean = hash != null && HASH.matches(hash)

    /** The `PROFILE` component JSON ([InventoryButtonIcons.ItemLook.skin]) of a head wearing [hash], or null for a malformed hash. */
    fun profileJson(hash: String): String? {
        val h = hash.lowercase(Locale.ROOT)
        if (!valid(h)) return null
        val value = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/$h\"}}}".toByteArray())
        // A fixed id per texture: the skin cache keys heads by profile, so equal textures share one download.
        val profile = GameProfile(UUID.nameUUIDFromBytes("soul-head:$h".toByteArray()), "", PropertyMap(ImmutableMultimap.of("textures", Property("textures", value))))
        return ResolvableProfile.CODEC.encodeStart(JsonOps.INSTANCE, ResolvableProfile.createResolved(profile)).result().map { it.toString() }.orElse(null)
    }
}
