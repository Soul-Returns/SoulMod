package com.soulreturns.sound

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.features.mining.HollowsWeather
import com.soulreturns.mixin.AbstractSoundInstanceAccessor
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.resources.sounds.TickableSoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.network.chat.contents.TranslatableContents

/** One replacer rule. Persisted in config (`sounds.replaceRules`), so field names stay put. */
@Serializable
data class ReplaceRule(
    /** Sound event id; `minecraft:` may be omitted. */
    val match: String = "",
    /** `SoundSource` name (`hostile`, `players`, …) the sound must play in; null = any. */
    val sourceFilter: String? = null,
    /** [MUTE] or [REPLACE]. */
    val action: String = MUTE,
    /** What plays instead, for [REPLACE]. */
    val replacement: SoundRef? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val MUTE = "mute"
        const val REPLACE = "replace"
    }
}

/**
 * Mutes or swaps sounds by id, for every sound the client plays (server packets and client-side).
 * `SoundEngineMixin` calls [onEnginePlay] at the head of `SoundEngine.play`, which every sound passes
 * through, delayed ones included once they are due.
 *
 * A replacement keeps the original's position, attenuation and category, plays at the original's
 * requested volume times the ref's volume and at the ref's own pitch (the pitch the user picked, not
 * Hypixel's random variation). Looping and ticking sounds (music, minecarts) can be muted but not
 * replaced: their owner stops the original instance, which would leave a replacement running.
 */
object SoundReplacer {
    private var indexedRules: List<ReplaceRule>? = null
    private var index: Map<String, List<ReplaceRule>> = emptyMap()

    var enabled: Boolean
        get() = cfg.sounds.replacerEnabled
        set(value) = ConfigManager.update { copy(sounds = sounds.copy(replacerEnabled = value)) }

    fun rules(): List<ReplaceRule> = cfg.sounds.replaceRules

    fun addRule(rule: ReplaceRule) = setRules(rules() + normalize(rule))

    fun updateRule(
        index: Int,
        rule: ReplaceRule,
    ): Boolean {
        val rules = rules()
        if (index !in rules.indices) return false
        setRules(rules.toMutableList().also { it[index] = normalize(rule) })
        return true
    }

    fun removeRule(index: Int): Boolean {
        val rules = rules()
        if (index !in rules.indices) return false
        setRules(rules.filterIndexed { i, _ -> i != index })
        return true
    }

    fun setRules(rules: List<ReplaceRule>) = ConfigManager.update { copy(sounds = sounds.copy(replaceRules = rules)) }

    /** The rule that applies to a sound, or null. First enabled match wins. */
    fun ruleFor(
        id: String,
        category: String,
    ): ReplaceRule? {
        val rules = cfg.sounds.replaceRules
        if (rules !== indexedRules) {
            index = rules.filter { it.enabled }.groupBy { SoundRef.normalizeVanillaId(it.match) }
            indexedRules = rules
        }
        return index[id]?.firstOrNull { it.sourceFilter.isNullOrBlank() || it.sourceFilter.equals(category, ignoreCase = true) }
    }

    /** Null lets vanilla play the sound; anything else is returned from `SoundEngine.play` instead. Client thread. */
    @JvmStatic
    fun onEnginePlay(
        engine: SoundEngine,
        sound: SoundInstance,
    ): SoundEngine.PlayResult? {
        if (sound is SoulSoundInstance) return null
        val accessor = sound as? AbstractSoundInstanceAccessor
        val volume = accessor?.`soul$getVolume`() ?: 1f
        val pitch = accessor?.`soul$getPitch`() ?: 1f
        if (HollowsWeather.mutes(sound.identifier.toString(), sound.source.getName())) {
            RecentSounds.record(sound, volume, pitch, RecentSound.MUTED)
            return SoundEngine.PlayResult.NOT_STARTED
        }
        val rule = if (cfg.sounds.replacerEnabled) ruleFor(sound.identifier.toString(), sound.source.getName()) else null
        if (rule == null) {
            RecentSounds.record(sound, volume, pitch, RecentSound.PLAYED)
            return null
        }
        if (rule.action == ReplaceRule.MUTE) {
            RecentSounds.record(sound, volume, pitch, RecentSound.MUTED)
            return SoundEngine.PlayResult.NOT_STARTED
        }
        val ref = rule.replacement
        val replacement =
            if (rule.action != ReplaceRule.REPLACE || ref == null || sound.isLooping || sound is TickableSoundInstance) {
                null
            } else {
                SoulSounds.create(
                    ref, sound.source, volume * ref.volume, ref.pitch, sound.x, sound.y, sound.z,
                    sound.attenuation, sound.isRelative, subtitleOf(sound),
                )
            }
        if (replacement == null) {
            RecentSounds.record(sound, volume, pitch, RecentSound.PLAYED)
            return null
        }
        RecentSounds.record(sound, volume, pitch, RecentSound.REPLACED)
        return engine.play(replacement)
    }

    /** The original's subtitle key, so subtitles still say what happened rather than naming the file. */
    private fun subtitleOf(sound: SoundInstance): String? {
        val events = Minecraft.getInstance().soundManager.getSoundEvent(sound.identifier) ?: return null
        return (events.subtitle?.contents as? TranslatableContents)?.key
    }

    private fun normalize(rule: ReplaceRule): ReplaceRule =
        rule.copy(match = SoundRef.normalizeVanillaId(rule.match), sourceFilter = rule.sourceFilter?.trim()?.lowercase()?.ifEmpty { null })
}
