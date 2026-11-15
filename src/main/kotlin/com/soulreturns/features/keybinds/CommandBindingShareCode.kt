package com.soulreturns.features.keybinds

import com.soulreturns.config.CommandBinding
import com.soulreturns.config.PresetBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * What a share code carries. [presets] is null for an `SK1:` code, which predates presets: importing
 * one replaces only the custom commands.
 */
class CommandKeybindShare(
    val bindings: List<CommandBinding>,
    val presets: Map<String, PresetBinding>?,
)

object CommandBindingShareCode {
    private const val PREFIX_V1 = "SK1:"
    private const val PREFIX = "SK2:"
    private const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private const val MAX_CODE_CHARS = 1_500_000
    private val tuplesSerializer = ListSerializer(ListSerializer(String.serializer()))
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    @Serializable
    private class Payload(
        @SerialName("c") val commands: List<Entry> = emptyList(),
        @SerialName("p") val presets: List<Preset> = emptyList(),
    )

    @Serializable
    private class Entry(
        @SerialName("c") val command: String,
        @SerialName("k") val key: String = "",
        @SerialName("on") val enabled: Boolean = true,
    )

    @Serializable
    private class Preset(
        @SerialName("id") val id: String,
        @SerialName("k") val key: String,
        @SerialName("on") val enabled: Boolean = true,
    )

    /** `SK2:` code with every custom entry and every bound, known preset, including on/off state. */
    fun encode(bindings: List<CommandBinding>, presets: Map<String, PresetBinding>): String {
        var minimumSize = if (bindings.isEmpty()) 2L else 1L
        for (binding in bindings) {
            validateCommand(binding.command)
            minimumSize += binding.command.length.toLong() + binding.key.length + 8
            require(minimumSize <= MAX_PAYLOAD_BYTES) { "These command keybinds are too large to export (maximum 1 MiB)." }
        }
        val payload = Payload(
            bindings.map { Entry(it.command, it.key, it.enabled) },
            CommandPresets.all.mapNotNull { preset ->
                presets[preset.id]?.takeIf { it.key.isNotBlank() }?.let { Preset(preset.id, it.key, it.enabled) }
            },
        )
        val text = json.encodeToString(Payload.serializer(), payload)
        val bytes = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("A command or key contains invalid Unicode text.")
        }
        require(bytes.remaining() <= MAX_PAYLOAD_BYTES) { "These command keybinds are too large to export (maximum 1 MiB)." }

        val compressed = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(compressed, deflater).use {
                it.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining())
            }
        } finally {
            deflater.end()
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed.toByteArray())
    }

    /** Reads `SK2:` and the older `SK1:` codes. Unknown preset ids are dropped; the retired `soul` preset becomes a custom `/soul`. */
    fun decode(code: String): CommandKeybindShare {
        require(code.length <= MAX_CODE_CHARS) { "The share code is too large (maximum 1,500,000 characters)." }
        val trimmed = code.trim()
        val prefix = when {
            trimmed.startsWith(PREFIX) -> PREFIX
            trimmed.startsWith(PREFIX_V1) -> PREFIX_V1
            else -> throw IllegalArgumentException("Unsupported share code. Expected a code beginning with SK2: or SK1:.")
        }
        val text = payloadText(trimmed.substring(prefix.length), prefix)
        if (prefix == PREFIX_V1) {
            val tuples = try {
                Json.decodeFromString(tuplesSerializer, text)
            } catch (_: SerializationException) {
                throw IllegalArgumentException("The share code must contain a list of [command, key] string pairs.")
            }
            val bindings = tuples.map { tuple ->
                require(tuple.size == 2) { "Each imported keybind must contain exactly one command and one key." }
                validateCommand(tuple[0])
                CommandBinding(tuple[0], tuple[1])
            }
            return CommandKeybindShare(bindings, null)
        }
        val payload = try {
            json.decodeFromString(Payload.serializer(), text)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("The share code does not contain command keybinds.")
        }
        val bindings = payload.commands.mapTo(ArrayList()) { entry ->
            validateCommand(entry.command)
            CommandBinding(entry.command, entry.key, entry.enabled)
        }
        val presets = LinkedHashMap<String, PresetBinding>()
        for (preset in payload.presets) {
            if (preset.key.isBlank()) continue
            // Config migration v3 does the same to a stored `presets.soul`.
            if (preset.id == "soul") bindings.add(CommandBinding("soul", preset.key, preset.enabled))
            else if (CommandPresets.byId(preset.id) != null) presets[preset.id] = PresetBinding(preset.key, preset.enabled)
        }
        return CommandKeybindShare(bindings, presets)
    }

    private fun payloadText(body: String, prefix: String): String {
        val encoded = body.filterNot { it.isWhitespace() }
        require(encoded.isNotEmpty()) { "The share code has no encoded data." }
        require(encoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "The share code contains invalid Base64 characters. Copy the complete $prefix code."
        }
        val compressed = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The share code has incomplete or invalid Base64 data.")
        }
        val payload = inflate(compressed, prefix)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload)).toString()
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("The share code contains invalid Unicode text.")
        }
    }

    private fun inflate(compressed: ByteArray, prefix: String): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val payload = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                // Read at most one byte beyond the limit, without growing the output buffer.
                val count = inflater.inflate(buffer, 0, minOf(buffer.size, MAX_PAYLOAD_BYTES - payload.size() + 1))
                require(count <= MAX_PAYLOAD_BYTES - payload.size()) { "The imported keybind data is too large (maximum 1 MiB)." }
                if (count > 0) {
                    payload.write(buffer, 0, count)
                } else if (!inflater.finished()) {
                    require(!inflater.needsDictionary()) { "The share code requires an unsupported compression dictionary." }
                    require(!inflater.needsInput()) { "The share code is incomplete. Copy the complete $prefix code." }
                    throw IllegalArgumentException("The share code contains invalid compressed data.")
                }
            }
            require(inflater.remaining == 0) { "The share code contains unexpected data after its compressed payload." }
            return payload.toByteArray()
        } catch (_: DataFormatException) {
            throw IllegalArgumentException("The share code is damaged or contains invalid compressed data.")
        } finally {
            inflater.end()
        }
    }

    private fun validateCommand(command: String) {
        require(command.isNotBlank() && command.none { it == '\r' || it == '\n' }) {
            "Every command must be non-empty and single-line."
        }
    }
}
