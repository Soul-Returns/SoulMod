package com.soulreturns.features.experiments

/**
 * One container stack as the server sent it, reduced to what the experiment readers need. Pure (no
 * Minecraft types) so the models replay recorded packets in unit tests. [name] and [lore] keep the
 * legacy `§` codes; equality covers every field, so an identical resend compares equal.
 */
data class ExpItem(
    /** Item id path without the namespace (`cyan_stained_glass`). */
    val id: String,
    val count: Int = 1,
    val name: String = "",
    val lore: List<String> = emptyList(),
    /** Renders with the enchant glint. */
    val glint: Boolean = false,
    /** Player head skin: the last path segment of the profile's SKIN url. */
    val texture: String? = null,
    /** Leather dye as 0xRRGGBB. */
    val dye: Int? = null,
    /** SkyBlock id (`custom_data.id`). */
    val sbId: String? = null,
    val petType: String? = null,
    val petTier: String? = null,
) {
    val plain: String = strip(name)
    val plainLore: List<String> = lore.map(::strip)
    val isPane: Boolean get() = id.endsWith("_stained_glass_pane")
    val isBlackPane: Boolean get() = id == "black_stained_glass_pane"

    /** The first legacy color code of the name (`d` for `§r§dSuperpairs`), or null. */
    val nameColor: Char?
        get() {
            var i = 0
            while (i < name.length - 1) {
                if (name[i] == '§') {
                    val c = name[i + 1].lowercaseChar()
                    if (c in '0'..'9' || c in 'a'..'f') return c
                    i += 2
                } else {
                    return null
                }
            }
            return null
        }

    companion object {
        private val CODES = Regex("§(#[0-9a-fA-F]{6}|.)")

        fun strip(s: String): String = if (s.indexOf('§') < 0) s else CODES.replace(s, "")
    }
}
