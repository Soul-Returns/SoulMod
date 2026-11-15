package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors

/**
 * Marks: the user annotates what they are doing ("stand here for leap", "P3 start") by command
 * or key; agents may add their own. Each one records label, time, tick, position, rotation,
 * looked-at block, held item and place, goes to every attached stream/recording and the
 * backlog, and is appended to `<gameDir>/soul-dev/marks.ndjson`, so none is lost while nobody
 * is attached. Client-only: nothing is sent to the server.
 */
object Marks {
    val file = BridgeInfo.dir.resolve("marks.ndjson")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "Soul devbridge marks").apply { isDaemon = true } }

    @Volatile
    private var count = 0

    @Volatile
    private var last: String? = null

    /** Client thread. Blank [label] = auto-numbered. Returns the mark's JSON. */
    fun drop(label: String?, source: String): JsonObject? {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return null
        val n = ++count
        val name = label?.trim()?.takeIf { it.isNotEmpty() } ?: "mark $n"
        val data =
            json(
                linkedMapOf(
                    "n" to n,
                    "label" to name,
                    "source" to source,
                    "pos" to listOf(p.x, p.y, p.z),
                    "block_pos" to listOf(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z)),
                    "yaw" to Mth.wrapDegrees(p.yRot),
                    "pitch" to p.xRot,
                    "looking_at" to Providers.lookingAt(),
                    "held" to Providers.brief(p.mainHandItem),
                    "dimension" to mc.level?.dimension()?.identifier()?.toString(),
                    "location" to DevBridgeApi.location(),
                    "room" to DevBridgeApi.dungeonRoom(p.x, p.z),
                    "server" to mc.currentServer?.ip,
                ),
            ) as JsonObject
        val line = Bridge.line("mark", data)
        last = line
        Bridge.emit("mark") { data }
        io.execute {
            runCatching {
                Files.createDirectories(file.parent)
                Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
        // Local feedback only (the player's own chat HUD); nothing leaves the client.
        if (source != "agent") p.sendSystemMessage(Component.literal("§d[Soul Dev] §fMark $n: §e$name"))
        return data
    }

    fun describe(): Map<String, Any?> = linkedMapOf("this_session" to count, "file" to file.toString(), "last" to last?.let { it.take(300) })
}
