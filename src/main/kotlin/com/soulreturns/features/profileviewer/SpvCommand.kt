package com.soulreturns.features.profileviewer

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.soulreturns.data.hypixel.PlayerIdentityResolver
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/**
 * `/spv` — the SkyBlock Profile Viewer.
 *
 * - `/spv` — you
 * - `/spv <name>` — that player's selected profile
 * - `/spv <name> <profile>` — that player's named profile (`cute_name`, e.g. `Banana`)
 *
 * Registered twice: as the top-level `/spv` everyone already types, and as `/soul spv` so it is
 * discoverable from the mod's own command tree. [node] builds a fresh subtree per call — brigadier
 * builders are consumed by `register`, so the two registrations must not share one.
 */
object SpvCommand {
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(node())
        }
    }

    /**
     * The `spv` subtree. Also grafted under `/soul` by
     * [com.soulreturns.commands.SoulCommand], which is why this is exposed rather than inlined.
     */
    fun node(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("spv")
            .executes {
                openSelf()
                1
            }
            .then(
                ClientCommands.argument("name", StringArgumentType.word())
                    .suggests(nameSuggestions)
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

    private fun openSelf() {
        val self = Minecraft.getInstance().user.name
        if (self.isNullOrBlank()) {
            soulChat("§cCould not work out your own username.")
            return
        }
        ProfileViewerService.open(self)
    }

    /**
     * Completions for the name argument: you, your party, then anyone looked up this session. All
     * three are names the player is plausibly about to type, and none of them costs a request.
     */
    private val nameSuggestions = SuggestionProvider<FabricClientCommandSource> { _, builder ->
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
        builder.buildFuture()
    }
}
