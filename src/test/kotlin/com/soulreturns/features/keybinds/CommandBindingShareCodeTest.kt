package com.soulreturns.features.keybinds

import com.soulreturns.config.CommandBinding
import com.soulreturns.config.PresetBinding
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandBindingShareCodeTest {
    /** An `SK1:` code exactly as the editor exported it before presets: `[command, key]` tuples. */
    private fun sk1(json: String): String {
        val out = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        DeflaterOutputStream(out, deflater).use { it.write(json.toByteArray()) }
        deflater.end()
        return "SK1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    @Test
    fun oldCodesStillImport() {
        val share = CommandBindingShareCode.decode(sk1("""[["storage","key.keyboard.right.control+key.keyboard.1"],["warp dh",""],["pc ✓ gg","key.mouse.4"]]"""))
        assertEquals(
            listOf(
                CommandBinding("storage", "key.keyboard.right.control+key.keyboard.1"),
                CommandBinding("warp dh", ""),
                CommandBinding("pc ✓ gg", "key.mouse.4"),
            ),
            share.bindings,
        )
        assertNull(share.presets)
    }

    @Test
    fun oldEmptyCodeImportsAsEmpty() {
        val share = CommandBindingShareCode.decode(sk1("[]"))
        assertTrue(share.bindings.isEmpty())
        assertNull(share.presets)
    }

    @Test
    fun presetsAndEnabledStateRoundTrip() {
        val bindings = listOf(
            CommandBinding("warp dh", "key.keyboard.f6", enabled = false),
            CommandBinding("warp dh", "key.keyboard.f6"),
            CommandBinding("soul sounds", ""),
        )
        val presets = mapOf(
            "craft" to PresetBinding("key.keyboard.right.control+key.keyboard.c"),
            "pets" to PresetBinding("key.keyboard.p", enabled = false),
            "storage" to PresetBinding(""),
        )
        val code = CommandBindingShareCode.encode(bindings, presets)
        assertTrue(code.startsWith("SK2:"))
        val share = CommandBindingShareCode.decode("  ${code.chunked(40).joinToString("\n")}  ")
        assertEquals(bindings, share.bindings)
        // Unbound presets are not exported.
        assertEquals(presets - "storage", share.presets)
    }

    @Test
    fun unknownPresetIdsAreDropped() {
        val share = CommandBindingShareCode.decode(sk2("""{"p":[{"id":"nope","k":"key.keyboard.k"},{"id":"bazaar","k":"key.keyboard.b"}]}"""))
        assertEquals(mapOf("bazaar" to PresetBinding("key.keyboard.b")), share.presets)
        assertTrue(share.bindings.isEmpty())
    }

    @Test
    fun retiredSoulPresetImportsAsCustomCommand() {
        val share = CommandBindingShareCode.decode(
            sk2("""{"c":[{"c":"warp dh","k":"key.keyboard.f6"}],"p":[{"id":"soul","k":"key.keyboard.backslash","on":false},{"id":"pets","k":"key.keyboard.x"}]}"""),
        )
        assertEquals(
            listOf(CommandBinding("warp dh", "key.keyboard.f6"), CommandBinding("soul", "key.keyboard.backslash", enabled = false)),
            share.bindings,
        )
        assertEquals(mapOf("pets" to PresetBinding("key.keyboard.x")), share.presets)
    }

    @Test
    fun malformedCodesAreRejected() {
        assertFailsWith<IllegalArgumentException> { CommandBindingShareCode.decode("SK3:abc") }
        assertFailsWith<IllegalArgumentException> { CommandBindingShareCode.decode("SK2:") }
        assertFailsWith<IllegalArgumentException> { CommandBindingShareCode.decode(sk2("""{"c":[{"c":"  "}]}""")) }
        assertFailsWith<IllegalArgumentException> { CommandBindingShareCode.decode(sk2("""[["a","b"]]""")) }
        val valid = CommandBindingShareCode.encode(listOf(CommandBinding("pets", "key.keyboard.p")), emptyMap())
        assertFailsWith<IllegalArgumentException> { CommandBindingShareCode.decode(valid.dropLast(4)) }
    }

    private fun sk2(json: String): String = "SK2:" + sk1(json).removePrefix("SK1:")
}
