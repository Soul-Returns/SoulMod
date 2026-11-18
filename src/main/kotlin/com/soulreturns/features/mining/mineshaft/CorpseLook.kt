package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.MineshaftConfig

/** How one located corpse is drawn: label, color, whether it gets a line, whether it is dimmed. */
data class CorpseDraw(val label: String, val color: ChromaColor, val line: Boolean, val dimmed: Boolean)

/**
 * Corpse type and waypoint look for the corpse locator (rule://mineshaft). The type comes from a
 * corpse stand the player can see ([com.soulreturns.util.MobSpotter]) or from a fixed layout
 * (`FAIR_1` = Vanguard); until then a located spot is a plain `Corpse` in the locator's own color.
 */
object CorpseLook {
    /** A seen corpse whose helmet is no known corpse helmet. */
    const val OTHER = "other"

    /** SkyBlock helmet id -> corpse type as the tab rows name it. Anything else is [OTHER], never dropped. */
    private val HELMETS =
        mapOf(
            "LAPIS_ARMOR_HELMET" to "Lapis",
            "ARMOR_OF_YOG_HELMET" to "Umber",
            "MINERAL_HELMET" to "Tungsten",
            "VANGUARD_HELMET" to "Vanguard",
        )

    /** The key a corpse type needs to be looted; Lapis needs none, unknown types are never assumed to. */
    private val KEYS = mapOf("Umber" to "UMBER_KEY", "Tungsten" to "TUNGSTEN_KEY", "Vanguard" to "SKELETON_KEY")

    fun typeOf(helmetId: String?): String = HELMETS[helmetId] ?: OTHER

    fun keyFor(type: String?): String? = type?.let { KEYS[it] }

    /** [type] null = not identified yet. [inventory] = SkyBlock ids in the player's inventory. */
    fun decide(type: String?, inventory: Set<String>, m: MineshaftConfig): CorpseDraw {
        val label: String
        val color: ChromaColor
        if (type == null) {
            label = "Corpse"
            color = m.corpseLocatorColor
        } else if (type == OTHER) {
            label = "Corpse"
            color = m.corpsesHudColors.other
        } else {
            label = "$type Corpse"
            color = m.corpsesHudColors.of(type)
        }
        val key = keyFor(type)
        val dimmed = m.corpseLocatorDimKeyless && key != null && key !in inventory
        val line =
            when (type) {
                "Lapis" -> m.corpseLineLapis
                "Umber" -> m.corpseLineUmber
                "Tungsten" -> m.corpseLineTungsten
                "Vanguard" -> m.corpseLineVanguard
                else -> m.corpseLineUnknown
            }
        return CorpseDraw(label, color, line && !dimmed, dimmed)
    }
}
