package com.soulreturns.sound

import net.minecraft.client.resources.sounds.AbstractSoundInstance
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.AudioStream
import net.minecraft.client.sounds.SoundBufferLibrary
import net.minecraft.client.sounds.SoundManager
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.util.valueproviders.ConstantFloat
import org.lwjgl.BufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import javax.sound.sampled.AudioFormat

/**
 * Every sound Soul itself plays. The type is the recursion guard: [SoundReplacer] and [RecentSounds]
 * skip these, so a replacement never gets replaced again and never shows up as "heard".
 */
open class SoulSoundInstance(
    id: Identifier,
    source: SoundSource,
    vol: Float,
    pit: Float,
    px: Double,
    py: Double,
    pz: Double,
    att: SoundInstance.Attenuation,
    rel: Boolean,
) : AbstractSoundInstance(id, source, SoundInstance.createUnseededRandom()) {
    init {
        volume = vol
        pitch = pit
        x = px
        y = py
        z = pz
        attenuation = att
        relative = rel
    }

    /** True when the engine will place this in the world (mono needed for OpenAL to spatialize it). */
    val positional: Boolean get() = !relative && attenuation != SoundInstance.Attenuation.NONE
}

/**
 * A custom library sound. Played without touching `SoundManager`'s registry or a resource reload:
 * [resolve] hands the engine a one-off streaming [Sound], and Fabric's `FabricSoundInstance.getAudioStream`
 * hook (fabric-sound-api) lets the instance serve that stream from our decoded clip instead of a
 * resource file. Streaming, because only the streaming path consults the instance.
 */
class CustomSoundInstance(
    val customId: String,
    source: SoundSource,
    vol: Float,
    pit: Float,
    px: Double,
    py: Double,
    pz: Double,
    att: SoundInstance.Attenuation,
    rel: Boolean,
    private val attenuationDistance: Int,
    private val subtitleKey: String?,
) : SoulSoundInstance(identifierFor(customId), source, vol, pit, px, py, pz, att, rel) {
    override fun resolve(manager: SoundManager): WeighedSoundEvents {
        val events = WeighedSoundEvents(identifier, subtitleKey)
        val s = Sound(identifier, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, true, false, attenuationDistance)
        events.addSound(s)
        sound = s
        return events
    }

    override fun getAudioStream(
        loader: SoundBufferLibrary,
        id: Identifier,
        repeatInstantly: Boolean,
    ): CompletableFuture<AudioStream> {
        val clip = CustomClips.get(customId) ?: return CompletableFuture.failedFuture(IllegalStateException("Unknown custom sound $customId"))
        val mono = positional
        CustomClips.noteOpened(customId, mono)
        return clip.thenApply { ClipAudioStream(it, mono) { CustomClips.noteFinished(customId) } as AudioStream }
            .whenComplete { _, error ->
                if (error != null) SoulSounds.warnOnce("stream:$customId", "Custom sound '$customId' failed to decode: ${error.message}")
            }
    }

    companion object {
        fun identifierFor(customId: String): Identifier = Identifier.fromNamespaceAndPath("soul", "custom/$customId")
    }
}

/** Streams a [DecodedClip] to an OpenAL channel, optionally downmixed to mono so a positional sound is placed in the world. */
class ClipAudioStream(
    private val clip: DecodedClip,
    mono: Boolean,
    private val onFinished: (() -> Unit)? = null,
) : AudioStream {
    private val downmix = mono && clip.channels == 2
    private val outChannels = if (mono) 1 else clip.channels
    private val audioFormat = AudioFormat(clip.sampleRate.toFloat(), 16, outChannels, true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN)
    private var frame = 0

    override fun getFormat(): AudioFormat = audioFormat

    override fun read(size: Int): ByteBuffer {
        val remaining = clip.frames - frame
        // Like vanilla's FloatSampleSource: an empty buffer marks the end.
        if (remaining <= 0) return BufferUtils.createByteBuffer(0)
        val frames = minOf(remaining, maxOf(1, size / (2 * outChannels)))
        // Native order: OpenAL takes 16-bit samples in host byte order.
        val buf = BufferUtils.createByteBuffer(frames * 2 * outChannels)
        val pcm = clip.pcm
        if (downmix) {
            var i = frame * 2
            repeat(frames) {
                buf.putShort(((pcm[i] + pcm[i + 1]) / 2).toShort())
                i += 2
            }
        } else {
            val start = frame * clip.channels
            for (i in start until start + frames * clip.channels) buf.putShort(pcm[i])
        }
        frame += frames
        if (frame >= clip.frames) onFinished?.invoke()
        buf.flip()
        return buf
    }

    override fun close() {}
}
