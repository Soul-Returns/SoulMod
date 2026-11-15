package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonElement
import net.minecraft.network.protocol.Packet
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Raw packets for streams and recordings, bounded on every thread.
 *
 * The packet hook (netty thread inbound, game or netty thread outbound) only decides: shed type?
 * wanted by a listener? Then it hands the packet to the worker thread, which serializes it
 * ([PacketDump], hard budget per packet, oversized ones become stubs) and offers the line to the
 * listeners. Packets that carry item stacks (the game mutates them right after) are serialized on
 * the hook instead, under a 2 ms budget. The hook's time is what [cost] measures; when the bridge's
 * average over a second of ticks exceeds the budget it [shed]s the packet type that cost the most,
 * and says so in the stream (`bridge` events) and in `status`.
 */
object PacketPipe {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")

    /** The cost of everything the bridge does on the game's threads; closed per client tick by [Live]. */
    val cost: Cost = Cost(onShed = ::overBudget)

    private var lastNotice = 0L

    /** The window average passed the budget: shed the costliest packet type when packets are what costs, otherwise only say so. */
    private fun overBudget(type: String, avgMs: Double, typeMs: Double) {
        val text = "game threads averaged %.2f ms/tick over the %.2f budget".format(avgMs, cost.budgetMs)
        if (typeMs >= avgMs * 0.25) return shed(type, "$text (this type %.2f ms/tick)".format(typeMs))
        val now = System.currentTimeMillis()
        if (now - lastNotice < 30_000) return
        lastNotice = now
        Bridge.notice("over_budget", mapOf("reason" to "$text, but packets are not the main cost (the costliest type, $type, is %.2f ms/tick): provider frames, events or a world capture; lower --state rates or stop other listeners".format(typeMs), "budget_ms" to cost.budgetMs))
    }

    /** Types whose objects the game keeps and mutates (item stacks): serialized before the game handles them. */
    private val SYNC = setOf("container_set_content", "container_set_slot", "set_cursor_item", "set_player_inventory", "set_equipment", "merchant_offers")

    /** Longest line a listener ever gets; a larger packet is recorded as a stub. */
    const val MAX_LINE = 256 * 1024

    private const val QUEUE = 4096

    class Shed(val reason: String, val at: Long)

    class Job(val inbound: Boolean, val packet: Packet<*>, val path: String, val t: Long, val tick: Long?, val ping: Long?, val seq: Long, val bundle: Long, val targets: List<Listener>)

    private val shedTypes = ConcurrentHashMap<String, Shed>()
    private val queue = ArrayBlockingQueue<Job>(QUEUE)
    private val serialized = AtomicLong()

    /** Hook order of every observed packet (any direction): the netty order the lines' `seq` gives, since the lines themselves reach the file out of order (worker queue vs on-hook). */
    private val seq = AtomicLong()
    private val stubs = AtomicLong()
    private val stubTypes = ConcurrentHashMap<String, AtomicLong>()
    private val shedDropped = AtomicLong()
    private val queueDropped = AtomicLong()
    private val workerNanos = AtomicLong()
    private val workerByType = ConcurrentHashMap<String, AtomicLong>()
    private var droppedAtCheck = 0L
    private var sinceCheck = 0
    private var startedAt = 0L

    @Volatile
    private var worker: Thread? = null

    /** A listener wanting raw packets appeared: a clean slate. */
    @Synchronized
    fun start() {
        if (worker != null) return
        cost.reset()
        shedTypes.clear()
        queue.clear()
        serialized.set(0)
        stubs.set(0)
        stubTypes.clear()
        shedDropped.set(0)
        queueDropped.set(0)
        workerNanos.set(0)
        workerByType.clear()
        droppedAtCheck = 0
        sinceCheck = 0
        startedAt = System.currentTimeMillis()
        val t = Thread(::work, "soul-devbridge-packets")
        t.isDaemon = true
        worker = t
        t.start()
    }

    @Synchronized
    fun stop() {
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    /** The packet hook. */
    fun accept(inbound: Boolean, packet: Packet<*>, listeners: Array<Listener>) {
        val start = System.nanoTime()
        val path = packet.type().id().path
        if (shedTypes.containsKey(path)) {
            shedDropped.incrementAndGet()
            return
        }
        val channel = DevBridgeApi.packetChannel(packet)
        val targets = listeners.filter { it.filter.wants("packet") && it.filter.wantsPacket(inbound, path, channel) }
        if (targets.isEmpty()) {
            cost.add(System.nanoTime() - start)
            return
        }
        for (l in targets) l.countPacket(inbound, path)
        val job = Job(inbound, packet, path, System.currentTimeMillis(), Ticks.tick(), Ticks.ping(), seq.incrementAndGet(), DevBridgeApi.packetBundle(), targets)
        if (path in SYNC || worker == null) {
            finish(job, PacketDump.SYNC_MILLIS)
        } else if (!queue.offer(job)) {
            queueDropped.incrementAndGet()
        }
        cost.add(System.nanoTime() - start, path)
    }

    private fun finish(job: Job, millis: Long) {
        val dir = if (job.inbound) "in" else "out"
        val json: JsonElement = PacketDump.packet(dir, job.packet, job.t, millis, job.seq, job.bundle) { reason -> stub(job.path, reason) }
        var line = Bridge.line("packet", json.toString(), job.t, job.tick, job.ping)
        if (line.length > MAX_LINE) {
            stub(job.path, "line")
            line = Bridge.line(
                "packet",
                "{\"t\":${job.t},\"dir\":\"$dir\",\"type\":\"${job.packet.type().id()}\",\"seq\":${job.seq},\"data\":{\"@\":\"stub\",\"reason\":\"line\",\"chars\":${line.length}}}",
                job.t, job.tick, job.ping,
            )
        }
        serialized.incrementAndGet()
        for (l in job.targets) l.offer(line)
    }

    private fun stub(path: String, reason: String) {
        stubs.incrementAndGet()
        stubTypes.computeIfAbsent("$path ($reason)") { AtomicLong() }.incrementAndGet()
    }

    private fun work() {
        try {
            while (true) {
                val job = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val start = System.nanoTime()
                try {
                    finish(job, PacketDump.WORKER_MILLIS)
                } catch (e: LinkageError) {
                    throw e
                } catch (t: Throwable) {
                    logger.warn("[Soul/DevBridge] packet ${job.path} failed: $t")
                }
                val took = System.nanoTime() - start
                workerNanos.addAndGet(took)
                workerByType.computeIfAbsent(job.path) { AtomicLong() }.addAndGet(took)
            }
        } catch (_: InterruptedException) {
            // Stopped.
        } catch (e: LinkageError) {
            Gate.fail(e)
        }
    }

    /** Once per client tick while listening: the cost window, and the worker falling behind sheds too. */
    fun tick() {
        cost.endTick()
        if (++sinceCheck < Cost.WINDOW) return
        sinceCheck = 0
        val drops = queueDropped.get()
        if (drops > droppedAtCheck || queue.remainingCapacity() < QUEUE / 4) {
            val top = workerByType.entries.maxByOrNull { it.value.get() }
            if (top != null) shed(top.key, "the packet worker fell behind (${queue.size} queued, ${drops - droppedAtCheck} dropped in the last second; this type used ${top.value.get() / 1_000_000} ms of it)")
        }
        droppedAtCheck = drops
        workerByType.clear()
    }

    /** Stops serializing [type] until every listener has detached, and tells the streams. */
    fun shed(type: String, reason: String) {
        if (shedTypes.putIfAbsent(type, Shed(reason, System.currentTimeMillis())) != null) return
        logger.warn("[Soul/DevBridge] shedding packet type $type: $reason")
        Bridge.notice("shed", mapOf("type" to type, "reason" to reason, "budget_ms" to cost.budgetMs))
    }

    fun describe(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val wall = (now - startedAt).coerceAtLeast(1)
        return linkedMapOf(
            "serialized" to serialized.get(),
            "stubs" to stubs.get(),
            "stub_types" to stubTypes.mapValues { it.value.get() },
            "max_line_chars" to MAX_LINE,
            "shed" to shedTypes.map { (type, s) -> mapOf("type" to type, "reason" to s.reason, "seconds_ago" to (now - s.at) / 1000) },
            "shed_dropped" to shedDropped.get(),
            "worker" to
                linkedMapOf(
                    "running" to (worker != null),
                    "queued" to queue.size,
                    "queue_dropped" to queueDropped.get(),
                    "busy_pct" to Math.round(workerNanos.get() / 1e4 / wall) / 10.0,
                ),
        )
    }
}
