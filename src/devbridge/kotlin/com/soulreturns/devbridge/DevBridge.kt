package com.soulreturns.devbridge

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Dev-only companion mod `soul-devbridge` (never in the release jar): serves live events, state
 * providers, recordings, marks and screenshots on 127.0.0.1 for `tools/devbridge/listen`.
 * Read-only by construction (see rule://backend, Dev bridge).
 *
 * Listens from client start and stays idle-cheap until an agent attaches. When the installed
 * Soul speaks another bridge API (Soul updates itself, this jar does not), it only serves
 * `/status` with the reason and the fix; no class that links against Soul is loaded then.
 * This class and [BridgeInfo], [BridgeServer], [Gate] reference no Soul code for that reason.
 */
object DevBridge : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")

    /** Set by [Live] once enabled: the live part of `/status`. */
    @Volatile
    var liveStatus: (() -> Map<String, Any?>)? = null

    override fun onInitializeClient() {
        val token = HexFormat.of().formatHex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val port = System.getProperty("soul.devbridge.port")?.toIntOrNull() ?: 0
        val disabled = BridgeInfo.check()
        var server: BridgeServer? = null
        server = BridgeServer(port, token) { status(server!!.port, disabled) }
        server.start()
        val info = BridgeInfo.write(server.port, token, disabled)
        if (disabled != null) {
            logger.error("[Soul/DevBridge] disabled: $disabled")
            registerDisabledCommand(disabled)
            return
        }
        Gate.on = true
        Live.install(server)
        logger.info("[Soul/DevBridge] listening on 127.0.0.1:${server.port} ($info), Soul ${BridgeInfo.soulVersion}, bridge API ${BridgeInfo.BUILT_API}")
    }

    private fun status(port: Int, disabled: String?): String {
        val out = LinkedHashMap<String, Any?>(BridgeInfo.describe(port, disabled))
        out["on"] = Gate.on
        out["listening"] = Gate.listening
        out["failure"] = Gate.failure
        if (Gate.on) runCatching { liveStatus?.invoke() }.getOrNull()?.let(out::putAll)
        return json(out).toString()
    }

    /** `/soul dev bridge` while disabled: the reason, in the local chat HUD. */
    private fun registerDisabledCommand(reason: String) {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("soul").then(
                    ClientCommands.literal("dev").then(
                        ClientCommands.literal("bridge").executes {
                            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§c[Soul Dev Bridge] disabled: §f$reason"))
                            1
                        },
                    ),
                ),
            )
        }
    }
}
