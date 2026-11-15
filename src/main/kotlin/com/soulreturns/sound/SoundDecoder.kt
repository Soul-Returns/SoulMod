package com.soulreturns.sound

import javazoom.jl.decoder.Bitstream
import it.unimi.dsi.fastutil.floats.FloatConsumer
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import net.minecraft.client.sounds.JOrbisAudioStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** Decoded 16-bit PCM, interleaved when stereo. Immutable once built; shared by every stream over it. */
class DecodedClip(
    val sampleRate: Int,
    val channels: Int,
    val pcm: ShortArray,
) {
    val frames: Int get() = pcm.size / channels
    val durationMs: Long get() = frames * 1000L / sampleRate
    val bytes: Long get() = pcm.size * 2L
}

/**
 * Decodes the three import formats into [DecodedClip]s. Blocking: callers run it off the render
 * thread. Caps keep a stray music file from eating memory: [MAX_FILE_BYTES] on disk and
 * [MAX_DURATION_MS] of audio, checked while decoding so an oversized file stops early.
 */
object SoundDecoder {
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_DURATION_MS = 60_000L

    val FORMATS = listOf("ogg", "wav", "mp3")

    /** Format from the file's magic bytes, falling back to its extension; null when neither is ours. */
    fun detectFormat(path: Path): String? {
        val head = ByteArray(12)
        val n = Files.newInputStream(path).use { it.readNBytes(head, 0, head.size) }
        if (n >= 4) {
            val magic = String(head, 0, 4, Charsets.ISO_8859_1)
            if (magic == "OggS") return "ogg"
            if (magic == "RIFF" && n >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WAVE") return "wav"
            if (magic.startsWith("ID3")) return "mp3"
        }
        if (n >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0) return "mp3"
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in FORMATS }
    }

    @Throws(IOException::class)
    fun decode(
        path: Path,
        format: String,
    ): DecodedClip {
        val size = Files.size(path)
        if (size > MAX_FILE_BYTES) throw IOException("File is ${size / 1024 / 1024} MiB, the limit is ${MAX_FILE_BYTES / 1024 / 1024} MiB")
        val clip =
            BufferedInputStream(Files.newInputStream(path)).use { input ->
                when (format) {
                    "ogg" -> decodeOgg(input)
                    "wav" -> decodeWav(input)
                    "mp3" -> decodeMp3(input)
                    else -> throw IOException("Unsupported format: $format")
                }
            }
        if (clip.frames == 0) throw IOException("No audio in file")
        return clip
    }

    private fun maxSamples(
        sampleRate: Int,
        channels: Int,
    ): Long = MAX_DURATION_MS * sampleRate / 1000 * channels

    private fun tooLong(): IOException = IOException("Longer than ${MAX_DURATION_MS / 1000} s")

    private fun checkChannels(channels: Int) {
        if (channels != 1 && channels != 2) throw IOException("Only mono or stereo audio is supported ($channels channels)")
    }

    private class ShortSink(
        private val limit: Long,
    ) {
        var data = ShortArray(64 * 1024)
        var size = 0

        fun add(s: Short) {
            if (size >= limit) throw tooLong()
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = s
        }

        fun result(): ShortArray = data.copyOf(size)
    }

    private fun decodeOgg(input: InputStream): DecodedClip {
        JOrbisAudioStream(input).use { stream ->
            val fmt = stream.format
            val channels = fmt.channels
            checkChannels(channels)
            val rate = fmt.sampleRate.toInt()
            val sink = ShortSink(maxSamples(rate, channels))
            val consumer = FloatConsumer { f -> sink.add((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
            @Suppress("ControlFlowWithEmptyBody")
            while (stream.readChunk(consumer)) {}
            return DecodedClip(rate, channels, sink.result())
        }
    }

    private fun decodeWav(input: InputStream): DecodedClip {
        val source =
            try {
                AudioSystem.getAudioInputStream(input)
            } catch (e: Exception) {
                throw IOException("Not a readable WAV file (${e.message})")
            }
        source.use { src ->
            val sf = src.format
            checkChannels(sf.channels)
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sf.sampleRate, 16, sf.channels, sf.channels * 2, sf.sampleRate, false)
            val pcm =
                if (sf.matches(target)) {
                    src
                } else {
                    try {
                        AudioSystem.getAudioInputStream(target, src)
                    } catch (e: IllegalArgumentException) {
                        throw IOException("Unsupported WAV encoding: ${sf.encoding}, ${sf.sampleSizeInBits} bit")
                    }
                }
            pcm.use { stream ->
                val rate = sf.sampleRate.toInt()
                val sink = ShortSink(maxSamples(rate, sf.channels))
                val buf = ByteArray(16 * 1024)
                var carry = -1
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    var i = 0
                    if (carry >= 0 && n > 0) {
                        sink.add(((buf[0].toInt() shl 8) or carry).toShort())
                        carry = -1
                        i = 1
                    }
                    while (i + 1 < n) {
                        sink.add(((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort())
                        i += 2
                    }
                    if (i < n) carry = buf[i].toInt() and 0xFF
                }
                return DecodedClip(rate, sf.channels, sink.result())
            }
        }
    }

    private fun decodeMp3(input: InputStream): DecodedClip {
        val bitstream = Bitstream(input)
        try {
            val decoder = Decoder()
            var sink: ShortSink? = null
            var rate = 0
            var channels = 0
            while (true) {
                val header = bitstream.readFrame() ?: break
                val out = decoder.decodeFrame(header, bitstream) as SampleBuffer
                if (sink == null) {
                    rate = out.sampleFrequency
                    channels = out.channelCount
                    checkChannels(channels)
                    sink = ShortSink(maxSamples(rate, channels))
                }
                // A stream that switches channel count mid-file would interleave garbage; stop at the switch.
                if (out.channelCount != channels) break
                val buffer = out.buffer
                for (i in 0 until out.bufferLength) sink.add(buffer[i])
                bitstream.closeFrame()
            }
            if (sink == null) throw IOException("No MP3 frames found")
            return DecodedClip(rate, channels, sink.result())
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Not a readable MP3 file (${e.message})")
        } finally {
            runCatching { bitstream.close() }
        }
    }
}
