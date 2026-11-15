package com.soulreturns.devbridge

import com.soulreturns.devbridge.world.WorldCapture
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes of the enabled bridge. Starting/stopping a stream or recording, a mark or a screenshot
 * only changes what the bridge observes and writes locally; nothing reaches the game or server.
 */
object Routes {
    private val ids = AtomicInteger(1000)

    fun handle(req: Request, socket: Socket, out: OutputStream): Boolean {
        when (req.path) {
            "/events" -> stream(req, socket, out)
            "/snapshot" -> {
                val filter = Filter.parse(req.query)
                val names = req.query["providers"]?.split(',')?.map { it.trim() }?.filter { Providers.get(it) != null }?.ifEmpty { null } ?: Providers.names()
                ok(out, Bridge.line("snapshot", Providers.snapshot(filter, names)))
            }
            "/providers" -> ok(out, json(mapOf("providers" to Providers.describe(), "soul" to com.soulreturns.features.dev.DevBridgeApi.stateNames())).toString())
            "/record/start" -> recordStart(req, socket, out)
            "/record/stop" -> ok(out, json(Recorder.stop(req.query["reason"] ?: "stopped by agent") ?: mapOf("error" to "not recording")).toString())
            "/record/status" -> ok(out, json(Recorder.describe() ?: mapOf("recording" to false)).toString())
            "/mark" -> {
                val mark = onClient { Marks.drop(req.query["label"], "agent") }
                ok(out, (mark ?: json(mapOf("error" to "no player in a world"))).toString())
            }
            "/screenshot" -> ok(out, json(mapOf("file" to Screenshots.take().toString())).toString())
            "/world/start" -> ok(out, json(worldStart(req)).toString())
            "/world/stop" -> ok(out, json(WorldCapture.stop(req.query["reason"] ?: "stopped by agent") ?: mapOf("error" to "not capturing")).toString())
            "/world/status" -> ok(out, json(onClient { WorldCapture.status() }).toString())
            else -> return false
        }
        return true
    }

    private fun worldStart(req: Request): Map<String, Any?> =
        try {
            val options = WorldCapture.Options.parse(req.query)
            onClient {
                val (session, error) = WorldCapture.start(options)
                if (session == null) mapOf("error" to error) else session.describe()
            }
        } catch (e: IllegalArgumentException) {
            mapOf("error" to (e.message ?: e.toString()))
        }

    /** `tick_budget_ms`: the average cost per client tick above which the bridge sheds packet types (default 2). */
    private fun tickBudget(req: Request) {
        req.query["tick_budget_ms"]?.toDoubleOrNull()?.let { PacketPipe.cost.budgetMs = it.coerceIn(0.2, 50.0) }
    }

    private fun ok(out: OutputStream, body: String) = BridgeServer.respond(out, 200, body, "application/json")

    private fun <T> onClient(block: () -> T): T {
        val mc = Minecraft.getInstance()
        return if (mc.isSameThread) block() else java.util.concurrent.CompletableFuture.supplyAsync({ block() }, mc).get(5, TimeUnit.SECONDS)
    }

    /**
     * NDJSON stream: `hello`, the backlog (`backlog=1`), a full `snapshot` of every provider
     * (`snapshot=0` skips it), then live events until the client hangs up. A full queue counts a
     * drop and the stream gets a `dropped` event; a `heartbeat` every 15 s on a quiet stream.
     */
    private fun stream(req: Request, socket: Socket, out: OutputStream) {
        tickBudget(req)
        val listener = Listener(ids.incrementAndGet(), Filter.parse(req.query), "stream")
        BridgeServer.startStream(out)
        out.write(BridgeServer.line(Bridge.line("hello", listener.describe())))
        Bridge.add(listener)
        try {
            if (listener.filter.backlog) {
                val backlog = Backlog.lines()
                out.write(BridgeServer.line(Bridge.line("backlog", json(mapOf("events" to backlog.size, "note" to "events from before this stream")))))
                for (l in backlog) out.write(BridgeServer.line(l))
            }
            if (req.query["snapshot"] != "0") out.write(BridgeServer.line(Bridge.line("snapshot", Providers.snapshot(listener.filter))))
            out.flush()
            BridgeServer.watchEof(socket.getInputStream(), Thread.currentThread())
            pump(listener, out)
        } finally {
            Bridge.remove(listener)
        }
    }

    private fun pump(listener: Listener, out: OutputStream) {
        try {
            while (true) {
                val first = listener.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS)
                // A heartbeat on a quiet stream also catches a peer that vanished without a FIN.
                out.write(BridgeServer.line(first ?: Bridge.line("heartbeat", JsonObject(emptyMap()))))
                if (first != null) listener.sent.incrementAndGet()
                while (true) {
                    out.write(BridgeServer.line(listener.poll() ?: break))
                    listener.sent.incrementAndGet()
                }
                val dropped = listener.dropped.getAndSet(0)
                if (dropped > 0) out.write(BridgeServer.line(Bridge.line("dropped", json(mapOf("count" to dropped, "total" to listener.droppedTotal.get())))))
                out.flush()
            }
        } catch (_: InterruptedException) {
            // Hung up.
        }
    }

    /**
     * Starts a recording. `hold=1`: the response stays open with a status line every 2 s and the
     * recording ends when this connection closes (the agent's `listen` died or was stopped), so an
     * agent can never leave one running by accident. Without `hold` it runs detached until
     * `/record/stop`, the in-game stop, or its caps (`max_min`, `max_mb`).
     */
    private fun recordStart(req: Request, socket: Socket, out: OutputStream) {
        tickBudget(req)
        val hold = req.query["hold"] == "1"
        val (rec, error) = Recorder.start(Recorder.Options.parse(req.query, if (hold) "agent (held)" else "agent (detached)"))
        if (rec == null) return BridgeServer.respond(out, 409, json(mapOf("error" to error)).toString(), "application/json")
        if (!hold) return ok(out, json(rec.describe()).toString())
        BridgeServer.startStream(out)
        out.write(BridgeServer.line(Bridge.line("record", json(rec.describe()))))
        out.flush()
        BridgeServer.watchEof(socket.getInputStream(), Thread.currentThread())
        try {
            while (Recorder.current === rec) {
                Thread.sleep(2_000)
                out.write(BridgeServer.line(Bridge.line("record", json(rec.describe()))))
                out.flush()
            }
            out.write(BridgeServer.line(Bridge.line("record", json(rec.describe() + ("stopped" to rec.stopReason)))))
            out.flush()
        } catch (_: InterruptedException) {
            if (Recorder.current === rec) Recorder.stop("agent connection closed")
        } catch (_: java.io.IOException) {
            if (Recorder.current === rec) Recorder.stop("agent connection closed")
        }
    }

    private const val HEARTBEAT_SECONDS = 15L
}
