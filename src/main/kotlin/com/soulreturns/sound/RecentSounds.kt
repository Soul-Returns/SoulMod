package com.soulreturns.sound

import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.world.phys.Vec3

/** One sound the client was asked to play, merged with its repeats. */
data class RecentSound(
    /** Sound event id, `minecraft:entity.enderman.teleport`. */
    val id: String,
    /** `SoundSource` name: `hostile`, `players`, `master`, … */
    val category: String,
    val volume: Float,
    val pitch: Float,
    /** World position of the latest play; null for UI and other listener-relative sounds. */
    val position: Vec3?,
    /** Epoch millis of the first and the latest play while this entry was in the buffer. */
    val firstAt: Long,
    val time: Long,
    val count: Int,
    /** [PLAYED], [MUTED] or [REPLACED]: what [SoundReplacer] did with the latest play. */
    val outcome: String,
) {
    companion object {
        const val PLAYED = "played"
        const val MUTED = "muted"
        const val REPLACED = "replaced"
    }
}

/**
 * The sounds the client played lately, for the replacer's "recently heard" picker: finding the id of
 * the sound Hypixel uses for a thing is the hard part of writing a rule. Fed by [SoundReplacer] from
 * the engine's play path (server packets and client-side sounds alike; Soul's own sounds excluded).
 * Repeats of the same id and category merge into one entry that moves to the front, so a burst of
 * footsteps cannot push everything else out. Memory only, never persisted.
 */
object RecentSounds {
    const val CAPACITY = 128

    private val lock = Any()
    private val entries = ArrayDeque<RecentSound>()

    @Volatile
    var version: Int = 0
        private set

    internal fun record(
        instance: SoundInstance,
        volume: Float,
        pitch: Float,
        outcome: String,
    ) {
        val id = instance.identifier.toString()
        val category = instance.source.getName()
        val position =
            if (instance.isRelative || instance.attenuation == SoundInstance.Attenuation.NONE) null else Vec3(instance.x, instance.y, instance.z)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == id && it.category == category }
            val previous = if (index >= 0) entries.removeAt(index) else null
            entries.addFirst(
                RecentSound(id, category, volume, pitch, position, previous?.firstAt ?: now, now, (previous?.count ?: 0) + 1, outcome),
            )
            if (entries.size > CAPACITY) entries.removeLast()
            version++
        }
    }

    /** Newest first. */
    fun snapshot(): List<RecentSound> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            version++
        }
    }
}
