package com.soulreturns.features.dev

import com.soulreturns.util.soulChat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.Packet
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Packet debug mode (`/soul dev packets`): writes every packet the client receives, and optionally
 * every packet it sends, as one JSON object per line into `logs/soul-packets/<time>.jsonl` (plus a
 * `latest.jsonl` link), so an agent can tail the file while the player plays.
 *
 * **Read-only by design.** It is one [PacketTap] sink; nothing is changed, dropped or sent. The
 * file stays local: it holds chat and other players' data.
 *
 * Packets are turned into JSON ([PacketJson]) on the network thread (later the game may mutate
 * objects they reference, e.g. item stacks), then handed to one writer thread that flushes whenever
 * it runs dry, so `tail -f` sees lines within milliseconds.
 */
object PacketLogger {
    private const val MAX_FILE_BYTES = 1L shl 30

    private class Session(
        val file: Path,
        val writer: BufferedWriter,
        val all: Boolean,
        val outbound: Boolean,
        val only: List<String>,
        val startedAt: Long,
    ) : PacketTap.Sink {
        val queue = LinkedBlockingQueue<String>()
        val lines = AtomicLong()
        val bytes = AtomicLong()
        val counts = ConcurrentHashMap<String, AtomicLong>()

        @Volatile var running = true

        override fun packet(inbound: Boolean, packet: Packet<*>) {
            if (!inbound && !outbound) return
            record(this, if (inbound) "in" else "out", packet)
        }
    }

    @Volatile
    private var session: Session? = null

    private fun record(s: Session, dir: String, packet: Packet<*>) {
        try {
            val id = packet.type().id()
            val path = id.path
            val channel = PacketJson.customPayload(packet)?.type()?.id()?.toString()
            // Filter words pick types explicitly, noisy ones included; otherwise noisy types need `all`.
            if (s.only.isNotEmpty()) {
                if (s.only.none { path.contains(it) || channel?.contains(it) == true }) return
            } else if (!s.all && path in PacketJson.NOISY) {
                return
            }
            val line = PacketJson.packet(dir, packet).toString()
            s.counts.computeIfAbsent(if (channel != null) "$id $channel" else id.toString()) { AtomicLong() }.incrementAndGet()
            s.queue.add(line)
        } catch (t: Throwable) {
            s.queue.add(JsonObject(mapOf("dir" to JsonPrimitive(dir), "error" to JsonPrimitive(t.toString()))).toString())
        }
    }

    fun start(args: String) {
        if (session != null) stop(quiet = true)
        val tokens = args.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        val all = "all" in tokens
        val outbound = "out" in tokens
        val only = tokens.filter { it != "all" && it != "out" }
        val dir = FabricLoader.getInstance().gameDir.resolve("logs").resolve("soul-packets")
        Files.createDirectories(dir)
        val file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl")
        val s = Session(file, Files.newBufferedWriter(file), all, outbound, only, System.currentTimeMillis())
        val latest = dir.resolve("latest.jsonl")
        try {
            Files.deleteIfExists(latest)
            Files.createSymbolicLink(latest, file.fileName)
        } catch (_: Exception) {
            // No symlinks (Windows without the privilege): the status names the file instead.
        }
        val mc = Minecraft.getInstance()
        s.queue.add(
            JsonObject(
                mapOf(
                    "t" to JsonPrimitive(s.startedAt),
                    "session" to JsonPrimitive(file.fileName.toString()),
                    "mc" to JsonPrimitive(SharedConstants.getCurrentVersion().name()),
                    "server" to JsonPrimitive(mc.currentServer?.ip),
                    "player" to JsonPrimitive(mc.user.name),
                    "all" to JsonPrimitive(all),
                    "out" to JsonPrimitive(outbound),
                    "only" to JsonArray(only.map { JsonPrimitive(it) }),
                ),
            ).toString(),
        )
        session = s
        PacketTap.register(s)
        Thread({ writeLoop(s) }, "Soul packet log").apply { isDaemon = true }.start()
    }

    fun stop(quiet: Boolean = false): Boolean {
        val s = session ?: return false
        session = null
        PacketTap.unregister(s)
        s.running = false
        if (!quiet) soulChat("§7Packet log stopped: §f${s.lines.get()}§7 packets.")
        return true
    }

    private fun writeLoop(s: Session) {
        try {
            s.writer.use { w ->
                while (s.running || s.queue.isNotEmpty()) {
                    val line = s.queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    write(s, w, line)
                    while (true) write(s, w, s.queue.poll() ?: break)
                    w.flush()
                    if (s.bytes.get() > MAX_FILE_BYTES && session === s) {
                        stop(quiet = true)
                        Minecraft.getInstance().execute { soulChat("§ePacket log stopped at its 1 GB cap.") }
                    }
                }
            }
        } catch (t: Throwable) {
            if (session === s) stop(quiet = true)
            Minecraft.getInstance().execute { soulChat("§cPacket log failed: ${t.message}") }
        }
    }

    private fun write(s: Session, w: BufferedWriter, line: String) {
        w.write(line)
        w.write('\n'.code)
        s.lines.incrementAndGet()
        s.bytes.addAndGet(line.length + 1L)
    }

    /** The `/soul dev packets` dump. */
    fun status() {
        DevOutput.begin("/soul dev packets")
        val s = session
        if (s == null) {
            DevOutput.line("§7Packet log: §coff")
            DevOutput.line("§7Start: §f/soul dev packets start [all] [out] [filter…]")
            DevOutput.line("§8  all = keep chunk/light/movement/time/keep-alive/particle packets; out = also log sent packets;")
            DevOutput.line("§8  filters = only packet types or channels containing one of the words (noisy ones included)")
            DevOutput.line("§7Files: §f${FabricLoader.getInstance().gameDir.resolve("logs/soul-packets")}")
            return
        }
        val secs = (System.currentTimeMillis() - s.startedAt) / 1000
        DevOutput.line("§7Packet log: §aon §8for ${secs}s")
        DevOutput.line("§7File: §f${s.file}")
        DevOutput.line("§7Lines: §f${s.lines.get()} §8(${s.bytes.get() / 1024} KiB, ${s.queue.size} queued)")
        DevOutput.line("§7Mode: §f${if (s.all) "all" else "noisy types skipped"}${if (s.outbound) " + outbound" else ""}" +
            if (s.only.isEmpty()) "" else " §7only §f${s.only.joinToString(" ")}")
        DevOutput.line("§7Top types:")
        for ((type, count) in s.counts.entries.sortedByDescending { it.value.get() }.take(25)) {
            DevOutput.line("  §f${count.get()} §7$type")
        }
    }
}
