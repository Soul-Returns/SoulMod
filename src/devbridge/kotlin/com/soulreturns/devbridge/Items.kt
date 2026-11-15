package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

/**
 * The item dump plus a compact `components` summary: what a menu pane's look is made of. Hypixel
 * marks a clicked or completed pane with an item component (enchant glint override, custom model
 * data, dye, a different item model), invisible in name and lore. Only what differs from the
 * item's defaults is listed, every list is capped, and items with nothing to say get no key.
 *
 * `components` = `{glint?, glint_override?, model? (only a replaced item model), cmd?, dye?, ench?, unbreakable?, damage?,
 * tooltip_style?, patch}`: `glint` true when the stack renders with the enchant glint,
 * `glint_override` the component's value when present, `cmd` the custom model data (`f` floats,
 * `b` flags, `s` strings, `c` colors), `dye` as `#rrggbb`, `ench` id → level, `patch` every
 * component type the stack changes from its prototype ("-" prefix = removed), without the ones the
 * item JSON already shows (custom_name, lore, custom_data) and `tooltip_display` (on every menu item of the test server, so only noise).
 */
object Items {
    private const val MAX_LIST = 8
    private const val MAX_PATCH = 24
    private val SHOWN_ELSEWHERE = setOf("custom_name", "lore", "custom_data", "tooltip_display")

    fun item(stack: ItemStack): JsonElement {
        val base = DevBridgeApi.item(stack)
        if (base !is JsonObject) return base
        val components = summary(stack) ?: return base
        return JsonObject(base + ("components" to components))
    }

    fun summary(stack: ItemStack): JsonObject? {
        if (stack.isEmpty) return null
        val out = LinkedHashMap<String, JsonElement>()
        if (stack.hasFoil()) out["glint"] = JsonPrimitive(true)
        stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)?.let { out["glint_override"] = JsonPrimitive(it) }
        // Every item has a default model; only a replaced one says anything.
        if (stack.componentsPatch.entrySet().any { it.key == DataComponents.ITEM_MODEL && it.value.isPresent }) {
            stack.get(DataComponents.ITEM_MODEL)?.let { out["model"] = JsonPrimitive(it.toString()) }
        }
        stack.get(DataComponents.CUSTOM_MODEL_DATA)?.let { cmd ->
            val fields = LinkedHashMap<String, JsonElement>()
            if (cmd.floats().isNotEmpty()) fields["f"] = JsonArray(cmd.floats().take(MAX_LIST).map { JsonPrimitive(it) })
            if (cmd.flags().isNotEmpty()) fields["b"] = JsonArray(cmd.flags().take(MAX_LIST).map { JsonPrimitive(it) })
            if (cmd.strings().isNotEmpty()) fields["s"] = JsonArray(cmd.strings().take(MAX_LIST).map { JsonPrimitive(it) })
            if (cmd.colors().isNotEmpty()) fields["c"] = JsonArray(cmd.colors().take(MAX_LIST).map { JsonPrimitive(it) })
            out["cmd"] = JsonObject(fields)
        }
        stack.get(DataComponents.DYED_COLOR)?.let { out["dye"] = JsonPrimitive(String.format(java.util.Locale.ROOT, "#%06x", it.rgb() and 0xFFFFFF)) }
        stack.get(DataComponents.ENCHANTMENTS)?.takeIf { !it.isEmpty }?.let { ench ->
            out["ench"] =
                JsonObject(
                    ench.entrySet().take(MAX_LIST).associate { e ->
                        (e.key.unwrapKey().map { k -> k.identifier().toString() }.orElse("?")) to JsonPrimitive(e.intValue)
                    },
                )
        }
        if (stack.get(DataComponents.UNBREAKABLE) != null) out["unbreakable"] = JsonPrimitive(true)
        stack.get(DataComponents.DAMAGE)?.takeIf { it > 0 }?.let { out["damage"] = JsonPrimitive(it) }
        stack.get(DataComponents.TOOLTIP_STYLE)?.let { out["tooltip_style"] = JsonPrimitive(it.toString()) }
        headTexture(stack)?.let { out["texture"] = JsonPrimitive(it) }
        val patch = ArrayList<JsonElement>()
        var more = 0
        for ((type, value) in stack.componentsPatch.entrySet()) {
            val id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)?.path ?: continue
            if (id in SHOWN_ELSEWHERE) continue
            if (patch.size < MAX_PATCH) patch += JsonPrimitive(if (value.isPresent) id else "-$id") else more++
        }
        if (more > 0) patch += JsonPrimitive("… $more more")
        if (patch.isNotEmpty()) out["patch"] = JsonArray(patch)
        return if (out.isEmpty()) null else JsonObject(out)
    }

    /**
     * The skin texture of a player head: the last path segment of the `SKIN` url in the profile's `textures` property
     * (base64 JSON), which is what identifies a Hypixel head. Bounded: a property over 4 KB is ignored.
     */
    private fun headTexture(stack: ItemStack): String? =
        try {
            val profile = stack.get(DataComponents.PROFILE)?.partialProfile() ?: return null
            val value = profile.properties().get("textures").firstOrNull()?.value()?.takeIf { it.length <= 4096 } ?: return null
            val text = String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
            Regex("\"SKIN\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.substringAfterLast('/')
        } catch (_: Exception) {
            null
        }
}
