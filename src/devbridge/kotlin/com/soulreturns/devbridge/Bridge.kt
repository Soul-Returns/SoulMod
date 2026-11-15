package com.soulreturns.devbridge

import com.soulreturns.devbridge.world.WorldCapture
import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.world.BossEvent
import java.util.Optional
import java.util.UUID

/**
 * Event fan-out: [emit] builds an event only when an attached stream or recording wants its kind
 * (or it belongs in the always-on [Backlog]) and offers it to each bounded queue; [tick] reads
 * the subscribed providers. With nothing attached ([Gate.listening] false) nothing is built per
 * tick and no packet is serialized: the packet observer is only registered while a listener
 * wants raw packets.
 *
 * Every event is `{t, tick, ping, kind, data}` (recording format v2): wall ms, the server tick
 * ([Ticks.tick]: `set_time` game time interpolated between packets, the clock to use), the raw
 * ping count ([Ticks.ping], diagnostic only).
 */
object Bridge {
    @Volatile
    private var listeners: Array<Listener> = emptyArray()

    private var packetSinkOn = false
    private var clientTicks = 0L
    private var lastSidebar: String? = null
    private var lastTab: String? = null
    private var lastHeld: Pair<String, Int>? = null
    private val bars = HashMap<UUID, Pair<String, Float>>()

    fun currentListeners(): List<Listener> = listeners.toList()

    @Synchronized
    fun add(listener: Listener) {
        if (listeners.isEmpty()) PacketPipe.cost.reset()
        listeners += listener
        lastHeld = null
        Gate.listening = true
        updatePacketSink()
    }

    @Synchronized
    fun remove(listener: Listener) {
        listeners = listeners.filter { it !== listener }.toTypedArray()
        Gate.listening = listeners.isNotEmpty()
        updatePacketSink()
    }

    private fun updatePacketSink() {
        val want = listeners.any { it.filter.wants("packet") }
        if (want == packetSinkOn) return
        packetSinkOn = want
        if (want) PacketPipe.start() else PacketPipe.stop()
        DevBridgeApi.setPacketSink(if (want) ::onPacket else null)
    }

    /** `{t, tick, ping, kind, data}` around an already serialized [data]. */
    fun line(kind: String, data: String, t: Long = System.currentTimeMillis(), tick: Long? = Ticks.tick(), ping: Long? = Ticks.ping()): String =
        "{\"t\":$t,\"tick\":$tick,\"ping\":$ping,\"kind\":\"$kind\",\"data\":$data}"

    /** The bridge talking to whoever is attached (shed packet types, …): every listener gets it, whatever its kinds. */
    fun notice(op: String, fields: Map<String, Any?>) {
        val line = line("bridge", json(fields + ("op" to op)))
        for (l in listeners) l.offer(line)
    }

    fun line(kind: String, data: JsonElement, t: Long = System.currentTimeMillis()): String = line(kind, data.toString(), t)

    /** [data] runs only when a listener wants [kind] or it goes to the backlog; any thread. */
    fun emit(kind: String, backlog: Boolean = kind in Backlog.KINDS, data: () -> JsonElement) {
        if (!Gate.on) return
        val targets = if (Gate.listening) listeners.filter { it.filter.wants(kind) } else emptyList()
        if (targets.isEmpty() && !backlog) return
        val start = System.nanoTime()
        try {
            emitTo(kind, backlog, targets, data)
        } finally {
            if (Gate.listening) PacketPipe.cost.add(System.nanoTime() - start)
        }
    }

    private fun emitTo(kind: String, backlog: Boolean, targets: List<Listener>, data: () -> JsonElement) {
        val start = System.nanoTime()
        val t = System.currentTimeMillis()
        val line =
            try {
                line(kind, data(), t)
            } catch (e: LinkageError) {
                throw e
            } catch (e: Throwable) {
                line("error", json(mapOf("kind" to kind, "error" to e.toString())), t)
            }
        if (backlog) {
            Backlog.add(t, line)
            Backlog.costNanos += System.nanoTime() - start
        }
        for (l in targets) l.offer(line)
    }

    // ---------- provider frames ----------

    /** END_CLIENT_TICK. Idle: one volatile read. */
    fun tick() {
        clientTicks++
        if (!Gate.listening) return
        val ls = listeners
        val due = LinkedHashMap<String, MutableList<Pair<Listener, StateSub>>>()
        for (l in ls) {
            for (s in l.filter.state) {
                if (clientTicks % s.intervalTicks != 0L) continue
                val key =
                    if (s.provider == "entities") "entities|${l.filter.entities.key}" else if (s.provider == "soul") "soul|${l.filter.soul}" else s.provider
                due.getOrPut(key) { ArrayList() } += l to s
            }
        }
        for ((key, subs) in due) {
            val (first, sub) = subs[0]
            val data = Providers.read(sub.provider, first.filter).toString()
            val hash = data.hashCode()
            val line = line(sub.provider, data)
            for ((l, s) in subs) {
                if (s.onChange) {
                    if (l.lastHash[key] == hash) continue
                    l.lastHash[key] = hash
                }
                l.offer(line)
            }
        }
        if (clientTicks % BOARD_TICKS == 0L) pollBoards(ls)
        if (ls.any { it.filter.wants("held") }) pollHeld()
    }

    /**
     * The held slot or item changing, every client tick (self frames at 10 Hz miss short switches:
     * the superboom before a gate was in 3-6 of them). `initial` is the state when the first listener asked.
     */
    private fun pollHeld() {
        val p = Minecraft.getInstance().player ?: return
        val slot = p.inventory.selectedSlot
        val stack = p.mainHandItem
        val sig = slot.toString() + "|" + (if (stack.isEmpty) "" else Providers.brief(stack).toString())
        val old = lastHeld
        if (old != null && old.first == sig) return
        lastHeld = sig to slot
        val op = if (old == null) "initial" else if (old.second != slot) "switch" else "item"
        emit("held", backlog = false) {
            json(linkedMapOf("op" to op, "slot" to slot, "from" to old?.second, "item" to Providers.brief(stack)))
        }
    }

    /** Sidebar and tab changes as their own events, only while someone wants them. */
    private fun pollBoards(ls: Array<Listener>) {
        if (ls.any { it.filter.wants("sidebar") }) {
            val now = Providers.sidebar()?.toString()
            if (now != null && now != lastSidebar) emitRaw("sidebar", now)
            lastSidebar = now
        }
        if (ls.any { it.filter.wants("tab") }) {
            val now = Providers.tab()?.toString()
            if (now != null && now != lastTab) emitRaw("tab", now)
            lastTab = now
        }
    }

    private fun emitRaw(kind: String, data: String) {
        val line = line(kind, data)
        for (l in listeners) if (l.filter.wants(kind)) l.offer(line)
    }

    // ---------- chat, titles, boss bars, packets ----------

    /** Fabric message events (cancelled ones included), client thread. */
    fun onMessage(component: Component, overlay: Boolean) {
        emit(if (overlay) "actionbar" else "chat") { text(component) }
    }

    fun onTitle(type: String, component: Component?) {
        emit("title") { json(mapOf("type" to type, "text" to component?.let(DevBridgeApi::legacy))) }
    }

    /** `set_titles_animation`: fade-in, stay and fade-out in ticks. */
    fun onTitleTimes(fadeIn: Int, stay: Int, fadeOut: Int) {
        emit("title") { json(mapOf("type" to "times", "fade_in" to fadeIn, "stay" to stay, "fade_out" to fadeOut)) }
    }

    /**
     * After vanilla applied a boss event. The progress comes from the packet itself: the overlay's
     * bar interpolates over 100 ms (`LerpingBossEvent.getProgress` lerps from the old value), so reading
     * it right after the packet returned the old value and the change only showed up at the next boss
     * packet, about a second later (names and removals did not lag: no interpolation).
     */
    fun onBossUpdate(packet: ClientboundBossEventPacket) {
        packet.dispatch(
            object : ClientboundBossEventPacket.Handler {
                override fun add(id: UUID, name: Component, progress: Float, color: BossEvent.BossBarColor, overlay: BossEvent.BossBarOverlay, darken: Boolean, music: Boolean, fog: Boolean) {
                    val legacy = DevBridgeApi.legacy(name)
                    bars[id] = legacy to progress
                    emit("bossbar") { json(mapOf("op" to "add", "id" to id.toString(), "name" to legacy, "progress" to progress)) }
                }

                override fun updateName(id: UUID, name: Component) {
                    val legacy = DevBridgeApi.legacy(name)
                    val old = bars[id]
                    bars[id] = legacy to (old?.second ?: Float.NaN)
                    emit("bossbar") { json(mapOf("op" to "name", "id" to id.toString(), "name" to legacy, "progress" to old?.second?.takeUnless { it.isNaN() })) }
                }

                override fun updateProgress(id: UUID, progress: Float) {
                    // A bar added before the bridge was listening is not in the map: its name is on the overlay.
                    val name = bars[id]?.first ?: Minecraft.getInstance().gui.hud.bossOverlay.events[id]?.name?.let(DevBridgeApi::legacy)
                    if (bars[id]?.second == progress) return
                    bars[id] = (name ?: "") to progress
                    emit("bossbar", backlog = false) { json(mapOf("op" to "progress", "id" to id.toString(), "name" to name, "progress" to progress)) }
                }

                override fun remove(id: UUID) {
                    val old = bars.remove(id)
                    emit("bossbar") { json(mapOf("op" to "remove", "id" to id.toString(), "name" to old?.first)) }
                }
            },
        )
    }

    private fun onPacket(inbound: Boolean, packet: Packet<*>) = PacketPipe.accept(inbound, packet, listeners)

    /** Legacy text plus every hover tooltip in it (Hypixel puts detail there, e.g. the `[Sacks]` item list). */
    private fun text(component: Component): JsonElement {
        val hovers = LinkedHashSet<String>()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, _ ->
                val hover = style.hoverEvent
                if (hover is HoverEvent.ShowText) hovers += DevBridgeApi.legacy(hover.value())
                Optional.empty()
            },
            Style.EMPTY,
        )
        val fields = LinkedHashMap<String, JsonElement>()
        fields["text"] = JsonPrimitive(DevBridgeApi.legacy(component))
        if (hovers.isNotEmpty()) fields["hover"] = JsonArray(hovers.map { JsonPrimitive(it) })
        return JsonObject(fields)
    }

    fun clearWorld() {
        bars.clear()
        lastSidebar = null
        lastTab = null
        lastHeld = null
    }

    fun status(): Map<String, Any?> =
        linkedMapOf(
            "listeners" to JsonArray(listeners.map { it.describe() }),
            "packet_observer" to packetSinkOn,
            "provider_reads_total" to Providers.reads.get(),
            "backlog" to Backlog.describe(),
            "ticks" to Ticks.describe(),
            "recording" to Recorder.describe(),
            "marks" to Marks.describe(),
            "world_capture" to WorldCapture.describeBrief(),
            "cost" to PacketPipe.cost.describe(),
            "packets" to PacketPipe.describe(),
        )

    private const val BOARD_TICKS = 10L
}
