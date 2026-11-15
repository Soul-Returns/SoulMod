package com.soulreturns.sound

import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays [SoundRef]s through Minecraft's sound engine, so category volumes, the subtitle overlay and
 * device switching behave like any vanilla sound. Call from the client thread; a call from another
 * thread is moved onto it and returns null.
 */
object SoulSounds {
    private val logger = SoulLogger("Soul/Sounds")
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Default attenuation distance of vanilla sound events (blocks), used for custom positional sounds. */
    const val DEFAULT_ATTENUATION_DISTANCE = 16

    private var previewInstance: SoundInstance? = null

    /** Non-positional playback (UI and alerts). Returns the playing instance, or null when the ref cannot play. */
    fun play(
        ref: SoundRef,
        category: SoundSource = SoundSource.MASTER,
    ): SoundInstance? {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { play(ref, category) }
            return null
        }
        val instance = create(ref, category, ref.volume, ref.pitch, 0.0, 0.0, 0.0, SoundInstance.Attenuation.NONE, true, subtitleKey(ref)) ?: return null
        mc.soundManager.play(instance)
        return instance
    }

    /** Positional playback at a world position, attenuated like a vanilla sound. */
    fun playAt(
        ref: SoundRef,
        x: Double,
        y: Double,
        z: Double,
        category: SoundSource = SoundSource.MASTER,
    ): SoundInstance? {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { playAt(ref, x, y, z, category) }
            return null
        }
        val instance = create(ref, category, ref.volume, ref.pitch, x, y, z, SoundInstance.Attenuation.LINEAR, false, subtitleKey(ref)) ?: return null
        mc.soundManager.play(instance)
        return instance
    }

    /** Library preview: stops the previous preview first, so only one plays at a time. Records the ref in [SoundLibrary.recents]. */
    fun preview(ref: SoundRef): SoundInstance? {
        stopPreview()
        val instance = play(ref) ?: return null
        previewInstance = instance
        SoundLibrary.markUsed(ref)
        return instance
    }

    fun stopPreview() {
        val instance = previewInstance ?: return
        previewInstance = null
        Minecraft.getInstance().soundManager.stop(instance)
    }

    fun isPreviewing(): Boolean {
        val instance = previewInstance ?: return false
        return Minecraft.getInstance().soundManager.isActive(instance)
    }

    fun stop(instance: SoundInstance) {
        Minecraft.getInstance().soundManager.stop(instance)
    }

    /** True when [ref] names a sound event the client knows or an entry of the custom library. */
    fun canPlay(ref: SoundRef): Boolean =
        if (ref.isCustom) {
            SoundLibrary.entry(ref.id) != null
        } else {
            ref.vanillaIdentifier()?.let { Minecraft.getInstance().soundManager.getSoundEvent(it) } != null
        }

    /**
     * The instance [SoundReplacer] and the play functions hand to the engine. Unknown vanilla events
     * and missing library entries log once and return null.
     */
    internal fun create(
        ref: SoundRef,
        category: SoundSource,
        volume: Float,
        pitch: Float,
        x: Double,
        y: Double,
        z: Double,
        attenuation: SoundInstance.Attenuation,
        relative: Boolean,
        subtitleKey: String?,
    ): SoulSoundInstance? {
        if (ref.isCustom) {
            if (SoundLibrary.entry(ref.id) == null) {
                warnOnce("custom:${ref.id}", "Custom sound '${ref.id}' is not in the library; not playing it")
                return null
            }
            return CustomSoundInstance(ref.id, category, volume, pitch, x, y, z, attenuation, relative, DEFAULT_ATTENUATION_DISTANCE, subtitleKey)
        }
        val id = ref.vanillaIdentifier()
        if (id == null || Minecraft.getInstance().soundManager.getSoundEvent(id) == null) {
            warnOnce("vanilla:${ref.id}", "Unknown sound event '${ref.id}'; not playing it")
            return null
        }
        return SoulSoundInstance(id, category, volume, pitch, x, y, z, attenuation, relative)
    }

    /**
     * Subtitle for a custom sound played directly: its library name. Shown as-is because no
     * translation exists for it; `%` would be read as a format argument, so it is dropped.
     */
    private fun subtitleKey(ref: SoundRef): String? =
        if (ref.isCustom) SoundLibrary.entry(ref.id)?.name?.replace("%", "") else null

    internal fun warnOnce(
        key: String,
        message: String,
    ) {
        if (warned.add(key)) logger.warn(message)
    }
}
