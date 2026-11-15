package com.soulreturns.sound

import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * One playable sound as a feature stores it: a vanilla (or resource pack) sound event, or an entry
 * of the custom [SoundLibrary]. Persisted in config, so field names stay put.
 */
@Serializable
data class SoundRef(
    /** [VANILLA] or [CUSTOM]. */
    val source: String = VANILLA,
    /** Vanilla: sound event id (`minecraft:entity.experience_orb.pickup`). Custom: library entry id. */
    val id: String = "",
    val volume: Float = 1f,
    val pitch: Float = 1f,
) {
    val isCustom: Boolean get() = source == CUSTOM

    /** `source:id`, volume and pitch ignored: what favorites, recents and the GUI compare by. */
    val key: String get() = "$source:$id"

    /** Same sound, ignoring volume and pitch. */
    fun sameSound(other: SoundRef): Boolean = source == other.source && id == other.id

    /** The vanilla sound event id, `minecraft:` added when missing; null for custom refs or a malformed id. */
    fun vanillaIdentifier(): Identifier? = if (isCustom) null else Identifier.tryParse(id)

    companion object {
        const val VANILLA = "vanilla"
        const val CUSTOM = "custom"

        fun vanilla(
            id: String,
            volume: Float = 1f,
            pitch: Float = 1f,
        ): SoundRef = SoundRef(VANILLA, normalizeVanillaId(id), volume, pitch)

        fun custom(
            id: String,
            volume: Float = 1f,
            pitch: Float = 1f,
        ): SoundRef = SoundRef(CUSTOM, id, volume, pitch)

        /**
         * Parses the text form the dev command (and any text field) uses: `custom:<id>` for a library
         * entry, anything else is a vanilla sound event id (`entity.enderman.teleport` or
         * `minecraft:entity.enderman.teleport`). Null when the vanilla id is malformed.
         */
        fun parse(
            text: String,
            volume: Float = 1f,
            pitch: Float = 1f,
        ): SoundRef? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (t.startsWith("$CUSTOM:")) return custom(t.removePrefix("$CUSTOM:"), volume, pitch).takeIf { it.id.isNotEmpty() }
            val id = Identifier.tryParse(t.removePrefix("$VANILLA:")) ?: return null
            return SoundRef(VANILLA, id.toString(), volume, pitch)
        }

        /** `entity.enderman.teleport` → `minecraft:entity.enderman.teleport`; malformed ids pass through untouched. */
        fun normalizeVanillaId(id: String): String = Identifier.tryParse(id.trim())?.toString() ?: id.trim()
    }
}
