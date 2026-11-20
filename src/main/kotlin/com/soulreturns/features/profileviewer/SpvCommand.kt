package com.soulreturns.features.profileviewer

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.ParseResults
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.soulreturns.config.cfg
import com.soulreturns.data.hypixel.PlayerIdentityResolver
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import java.util.concurrent.CompletableFuture

/** `/sp [name] [profile]` and `/soul sp [name] [profile]` share a fresh subtree. */
object SpvCommand {
    // Never merge /pv into Fabric's dispatcher: Brigadier cannot restore another mod's merged node.
    private val pvDispatcher by lazy {
        CommandDispatcher<FabricClientCommandSource>().apply { register(node("pv")) }
    }

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(node())
        }
    }

    fun node(): LiteralArgumentBuilder<FabricClientCommandSource> = node("sp")

    private fun node(literal: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal(literal)
            .executes {
                openSelf()
                1
            }
            .then(
                ClientCommands.argument("name", StringArgumentType.word())
                    .suggests { _, builder -> suggestNames(builder) }
                    .executes { ctx ->
                        ProfileViewerService.open(StringArgumentType.getString(ctx, "name"))
                        1
                    }
                    .then(
                        ClientCommands.argument("profile", StringArgumentType.word())
                            .executes { ctx ->
                                ProfileViewerService.open(
                                    StringArgumentType.getString(ctx, "name"),
                                    StringArgumentType.getString(ctx, "profile"),
                                )
                                1
                            },
                    ),
            )

    private fun usePv(): Boolean = cfg.profileViewer.enabled && cfg.profileViewer.pvCommand

    @JvmStatic
    fun interceptPv(command: String): Boolean {
        if (!PvCommandOverride.handles(command, usePv())) return false
        val source = Minecraft.getInstance().connection?.suggestionsProvider as? FabricClientCommandSource ?: return false
        try {
            pvDispatcher.execute(command, source)
        } catch (e: CommandSyntaxException) {
            soulChat("§c${e.message}")
        }
        return true
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <S> suggestionDispatcher(original: CommandDispatcher<S>, input: String): CommandDispatcher<S> {
        if (!usePv()) return original
        return PvCommandOverride.dispatcher(original, pvDispatcher as CommandDispatcher<S>, input, true)
    }

    @JvmStatic
    fun completePvRoot(
        original: CompletableFuture<Suggestions>,
        parse: ParseResults<*>,
        cursor: Int,
    ): CompletableFuture<Suggestions> =
        PvCommandOverride.completeRoot(original, parse.reader.string, cursor, usePv())

    private fun openSelf() {
        val self = Minecraft.getInstance().user.name
        if (self.isNullOrBlank()) {
            soulChat("§cCould not work out your own username.")
            return
        }
        ProfileViewerService.open(self)
    }

    private fun suggestNames(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val candidates = LinkedHashSet<String>()
        try {
            candidates.add(Minecraft.getInstance().user.name)
            PartyManager.getMembers().forEach { candidates.add(it.name) }
            candidates.addAll(PlayerIdentityResolver.cachedNames())
        } catch (_: Throwable) {
            // Suggestions are a convenience; never let one break the command.
        }
        val typed = builder.remaining.lowercase()
        candidates.filter { it.lowercase().startsWith(typed) }.forEach(builder::suggest)
        return builder.buildFuture()
    }
}
