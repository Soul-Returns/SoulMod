package com.soulreturns.devbridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The dev bridge must only ever read client state (anticheat; rule://backend, Dev bridge). This
 * fails the build when bridge code (or Soul's side of it) references an API that sends a packet,
 * chat or command, clicks, simulates input, moves the player, changes slots or opens/closes
 * screens. Comments and string literals are ignored (mixin targets are strings). Need one of
 * these for a real reason? It does not belong in the bridge.
 */
class DevBridgeReadOnlyTest {
    /** A mixin that only injects can observe; these change, delay or replace what vanilla does. */
    private val mixinChanges = Regex("@(Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|ModifyExpressionValue|ModifyReturnValue|WrapOperation|WrapWithCondition|WrapMethod|Overwrite)\\b")
    private val forbidden =
        listOf(
            "\\.send\\(" to "sends a packet",
            "\\bsend(Packet|Command|Chat|UnsignedCommand|SignedCommand|Position)\\w*\\(" to "sends to the server",
            "\\bServerbound\\w*" to "builds a serverbound packet",
            "\\.drop\\((true|false)\\)|\\bdropItem\\(" to "drops an item",
            "\\bhandle(ChatInput|ContainerInput|InventoryMouseClick|InventoryButtonClick|PickItem\\w*|CreativeModeItem\\w*)\\(" to "clicks / sends chat",
            "\\.(useItem|useItemOn|attack|interact|interactAt|startDestroyBlock|continueDestroyBlock|stopDestroyBlock|releaseUsingItem|startUseItem|startAttack|swing)\\(" to "acts in the world",
            "\\b(setScreen|setScreenAndShow|closeContainer|clientSideCloseContainer|popGuiLayer|pushGuiLayer)\\(" to "opens/closes a screen",
            "\\bKeyMapping\\.(click|set|setAll|releaseAll)\\(" to "simulates a key",
            "\\.setDown\\(" to "simulates a key",
            "\\bglfwSet\\w*\\(" to "changes input state",
            "\\bjava\\.awt\\.Robot\\b|\\bRobot\\(" to "simulates input",
            "\\b(setSelectedSlot|setYRot|setXRot|setPos|moveTo|absMoveTo|setDeltaMovement|teleportTo|snapTo)\\(" to "moves the player / changes the slot",
            "\\.selected\\s*=" to "changes the hotbar slot",
            "\\b(performCommand|executeCommand)\\(|dispatcher\\.execute\\(|getActiveDispatcher\\(" to "runs a command",
            "\\.disconnect\\(" to "drops the connection",
            "\\.respawn\\(|\\bperformRespawn\\(" to "acts on the server",
        )

    /** A cancelling inject changes vanilla behavior; only the test client's toast drop may. */
    private val cancelAllowed = setOf("ToastManagerMixin.java")

    private fun sources(): List<File> {
        val roots =
            listOf(
                File("src/devbridge"),
                File("src/main/kotlin/com/soulreturns/features/dev/DevBridgeApi.kt"),
                File("src/main/kotlin/com/soulreturns/features/dev/DevState.kt"),
                // The outbound tap: sees every packet the client is about to send; must stay an observer.
                File("src/main/kotlin/com/soulreturns/features/dev/PacketTap.kt"),
                File("src/main/java/com/soulreturns/mixin/ConnectionMixin.java"),
            )
        return roots.flatMap { r -> r.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
    }

    /** Code only: comments and string/char literals blanked, line structure kept. */
    private fun code(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (c == '/' && next == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && next == '*') {
                i += 2
                while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                    if (text[i] == '\n') out.append('\n')
                    i++
                }
                i += 2
            } else if (c == '"' || c == '\'') {
                val quote = c
                val triple = c == '"' && text.startsWith("\"\"\"", i)
                i += if (triple) 3 else 1
                while (i < text.length) {
                    if (triple && text.startsWith("\"\"\"", i)) { i += 3; break }
                    if (!triple && text[i] == '\\') { i += 2; continue }
                    if (!triple && text[i] == quote) { i++; break }
                    if (text[i] == '\n') out.append('\n')
                    i++
                }
                out.append("\"\"")
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun violations(): List<String> {
        val found = ArrayList<String>()
        for (file in sources()) {
            val lines = code(file.readText()).lines()
            for ((n, line) in lines.withIndex()) {
                if (file.extension == "java" && mixinChanges.containsMatchIn(line)) found += "${file.path}:${n + 1}: mixin changes vanilla instead of observing: ${line.trim()}"
                for ((pattern, why) in forbidden) {
                    if (Regex(pattern).containsMatchIn(line)) found += "${file.path}:${n + 1}: $why (${pattern}): ${line.trim()}"
                }
                if (file.name !in cancelAllowed && Regex("\\.cancel\\(\\)|cancellable\\s*=\\s*true").containsMatchIn(line)) {
                    found += "${file.path}:${n + 1}: cancels a vanilla handler: ${line.trim()}"
                }
            }
        }
        return found
    }

    @Test
    fun bridgeOnlyReads() {
        assertTrue(sources().size >= 10, "bridge sources not found (run from the project dir)")
        val found = violations()
        if (found.isNotEmpty()) fail("The dev bridge must only read client state. Violations:\n" + found.joinToString("\n"))
    }
}
