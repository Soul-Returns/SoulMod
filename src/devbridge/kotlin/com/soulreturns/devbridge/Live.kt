package com.soulreturns.devbridge

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.devbridge.world.WorldCapture
import com.soulreturns.features.dev.DevBridgeApi
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * The enabled bridge's wiring: client commands (`/soul dev bridge|mark|record`, client-side
 * only, never sent), two unbound key mappings (drop a mark, start/stop a recording), the tick
 * sampler and the always-on cheap hooks (chat, place changes, joins) that feed the backlog.
 */
object Live {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("soul-devbridge", "dev"))
    private val markKey = KeyMapping("key.soul-devbridge.mark", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category)
    private val recordKey = KeyMapping("key.soul-devbridge.record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category)

    fun install(server: BridgeServer) {
        port = server.port
        server.routes = Routes::handle
        // Location readers are plain fields; reading them off the client thread is a racy but harmless snapshot.
        DevBridge.liveStatus = { Bridge.status() + ("place" to DevBridgeApi.location()) + ("providers" to Providers.describe()) }
        KeyMappingHelper.registerKeyMapping(markKey)
        KeyMappingHelper.registerKeyMapping(recordKey)
        ClientTickEvents.END_CLIENT_TICK.register { Gate.run { tick() } }
        ClientReceiveMessageEvents.GAME.register { message, overlay -> Gate.run { Bridge.onMessage(message, overlay) } }
        ClientReceiveMessageEvents.GAME_CANCELED.register { message, overlay -> Gate.run { Bridge.onMessage(message, overlay) } }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> Gate.run { Bridge.onMessage(message, false) } }
        ClientReceiveMessageEvents.CHAT_CANCELED.register { message, _, _, _, _ -> Gate.run { Bridge.onMessage(message, false) } }
        DevBridgeApi.onPlaceChanged { Gate.run { Bridge.emit("location") { DevBridgeApi.location() } } }
        ClientPlayConnectionEvents.JOIN.register { _, _, mc ->
            Gate.run {
                Tracking.clear()
                Bridge.clearWorld()
                Bridge.emit("connection") { json(mapOf("op" to "join", "server" to mc.currentServer?.ip)) }
            }
        }
        // DISCONNECT fires on the netty thread; the event build reads nothing from the world.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Gate.run { Bridge.emit("connection") { json(mapOf("op" to "disconnect")) } }
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Brigadier merges this into Soul's `/soul dev` tree; keep the same requirement.
            dispatcher.register(
                ClientCommands.literal("soul").then(
                    ClientCommands.literal("dev").requires { DevBridgeApi.devUnlocked() }
                        .then(ClientCommands.literal("bridge").executes { statusWindow(); 1 })
                        .then(
                            ClientCommands.literal("mark")
                                .executes { Gate.run { Marks.drop(null, "command") }; 1 }
                                .then(
                                    ClientCommands.argument("label", StringArgumentType.greedyString()).executes { ctx ->
                                        val label = StringArgumentType.getString(ctx, "label")
                                        Gate.run { Marks.drop(label, "command") }
                                        1
                                    },
                                ),
                        )
                        .then(recordCommand()),
                ),
            )
        }
    }

    private fun recordCommand(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("record")
            .executes { chat(recordLine()); 1 }
            .then(
                ClientCommands.literal("start")
                    .executes { startRecording(null); 1 }
                    .then(ClientCommands.argument("label", StringArgumentType.greedyString()).executes { ctx -> startRecording(StringArgumentType.getString(ctx, "label")); 1 }),
            )
            .then(ClientCommands.literal("stop").executes { stopRecording(); 1 })

    private fun tick() {
        val listening = Gate.listening
        if (listening) PacketPipe.tick()
        val start = System.nanoTime()
        try {
            sample()
        } finally {
            if (listening) PacketPipe.cost.add(System.nanoTime() - start)
        }
    }

    private fun sample() {
        Bridge.tick()
        if (WorldCapture.active) WorldCapture.tick()
        val unlocked = DevBridgeApi.devUnlocked()
        while (markKey.consumeClick()) if (unlocked) Marks.drop(null, "key")
        while (recordKey.consumeClick()) if (unlocked) { if (Recorder.current == null) startRecording(null) else stopRecording() }
    }

    private fun startRecording(label: String?) {
        Gate.run {
            val (rec, error) = Recorder.start(Recorder.Options.parse(if (label == null) emptyMap() else mapOf("label" to label), "game"))
            chat(if (rec == null) "§c$error" else "§aRecording §f#${rec.id}§a to §f${rec.file.fileName}§7 (rolls to a new file every ${rec.options.maxMinutes.toInt()} min, ends after ${rec.options.maxTotalMinutes.toInt()} min; recompressed to zstd when installed)")
        }
    }

    private fun stopRecording() {
        Gate.run {
            val result = Recorder.stop("stopped in game")
            chat(if (result == null) "§7Not recording." else "§aRecording stopped: §f${result["file"]}§7 (${result["compressed_bytes"]} bytes)")
        }
    }

    private fun recordLine(): String {
        val rec = Recorder.current ?: return "§7Not recording. §f/soul dev record start [label]§7 or the key in Controls → Soul Dev Bridge."
        return "§aRecording §f#${rec.id}§a, ${(System.currentTimeMillis() - rec.started) / 1000}s, ${rec.compressedBytes / 1024} KiB: §f${rec.file.fileName}"
    }

    /** Local chat HUD only. */
    private fun chat(text: String) {
        Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§d[Soul Dev] §r$text"))
    }

    private fun statusWindow() {
        val lines = ArrayList<String>()
        lines += "§7Dev bridge: §a127.0.0.1:$port §8(token in ${BridgeInfo.dir.resolve("bridge.json")})"
        lines += "§7Soul §f${BridgeInfo.soulVersion}§7, bridge §f${BridgeInfo.bridgeVersion}§7, API §f${BridgeInfo.BUILT_API}§7, instance §f${BridgeInfo.instance}"
        lines += "§7Log: §f${BridgeInfo.logFile}"
        lines += "§7Agent CLI: §ftools/devbridge/listen status|snapshot|listen|record|replay|marks|log|shot"
        lines += ""
        lines += "§bProviders §7(snapshot, stream state=…, recordings)"
        Providers.describe().forEach { lines += "  §f${it["name"]} §8(${it["default"]}) §7${it["about"]}" }
        lines += "  §7soul: §f${DevBridgeApi.stateNames().joinToString(", ")}"
        lines += ""
        lines += "§bServer ticks §7${Ticks.describe().entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        lines += "§bBacklog §7${Backlog.describe().entries.joinToString(" ") { "${it.key}=${it.value}" }}"
        lines += "§bMarks §7this session ${Marks.describe()["this_session"]}, file ${Marks.file}"
        lines += "§bRecording §7" + (Recorder.current?.let { "#${it.id} ${it.file.fileName}, ${it.compressedBytes / 1024} KiB, owner ${it.options.owner}" } ?: "none")
        lines += "§bIdle cost §7provider reads total ${Providers.reads.get()}, packet observer ${if (Bridge.status()["packet_observer"] == true) "on" else "off"}"
        val listeners = Bridge.currentListeners()
        lines += "§bListeners §f${listeners.size}"
        val now = System.currentTimeMillis()
        for (l in listeners) {
            lines += "  §f#${l.id} ${l.label} §7for ${(now - l.since) / 1000}s, sent §f${l.sent.get()}§7, queued §f${l.queue.size}§7, dropped " +
                (if (l.droppedTotal.get() > 0) "§c" else "§f") + l.droppedTotal.get()
            lines += "    §8" + l.filter.describe().entries.joinToString(" ") { "${it.key}=${it.value}" }
        }
        DevBridgeApi.output("/soul dev bridge", lines)
    }

    private var port = 0
}
