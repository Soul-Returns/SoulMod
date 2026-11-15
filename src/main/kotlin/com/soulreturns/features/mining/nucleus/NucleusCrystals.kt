package com.soulreturns.features.mining.nucleus

/**
 * Display mapping for the five nucleus crystals: row order and Hypixel's chat colors (Amethyst §5,
 * Topaz §e, Sapphire §b, Amber §6, Jade §a). Never a validation list: any other crystal name Hypixel
 * prints gets a row after these, in grey.
 */
object NucleusCrystals {
    val ORDER: List<String> = listOf("Amethyst", "Topaz", "Sapphire", "Amber", "Jade")

    private val COLORS: Map<String, Int> =
        mapOf(
            "Amethyst" to 0xFFAA00AA.toInt(),
            "Topaz" to 0xFFFFFF55.toInt(),
            "Sapphire" to 0xFF55FFFF.toInt(),
            "Amber" to 0xFFFFAA00.toInt(),
            "Jade" to 0xFF55FF55.toInt(),
        )

    private const val OTHER = 0xFFAAAAAA.toInt()

    fun color(crystal: String): Int = COLORS[crystal] ?: OTHER

    private val LEGACY: Map<String, String> =
        mapOf("Amethyst" to "§5", "Topaz" to "§e", "Sapphire" to "§b", "Amber" to "§6", "Jade" to "§a")

    /** The same colors as legacy chat codes, for compacted chat lines. */
    fun legacy(crystal: String): String = LEGACY[crystal] ?: "§7"
}
