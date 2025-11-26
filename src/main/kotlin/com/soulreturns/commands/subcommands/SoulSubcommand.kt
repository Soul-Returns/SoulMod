package com.soulreturns.commands.subcommands

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

/**
 * Interface for Soul command subcommands.
 * Implement this interface to easily create new subcommands that can be registered to /soul.
 *
 * ## How to create a new subcommand:
 *
 * 1. Create a new object in the `com.soulreturns.commands.subcommands` package
 * 2. Implement this interface
 * 3. Override the `register()` method to return a LiteralArgumentBuilder
 * 4. Register the subcommand in `SoulCommand.kt` by adding `.then(YourSubcommand.register())`
 *
 * ## Example:
 * ```kotlin
 * object MySubcommand : SoulSubcommand {
 *     override fun register(): LiteralArgumentBuilder<FabricClientCommandSource> {
 *         return literal("mycommand") {
 *             runs { context ->
 *                 // Your command logic here
 *             }
 *         }
 *     }
 * }
 * ```
 * ```
 *
 * Then in SoulCommand.kt:
 * ```kotlin
 * .then(MySubcommand.register())
 * ```
 *
 * This will make your subcommand available as `/soul mycommand`
 */
interface SoulSubcommand {
    /**
     * Returns the LiteralArgumentBuilder for this subcommand.
     * This will be registered as a child of the main /soul command.
     */
    fun register(): LiteralArgumentBuilder<FabricClientCommandSource>

    /**
     * Helper method to create a literal command node with a cleaner syntax.
     */
    fun literal(name: String, init: LiteralArgumentBuilder<FabricClientCommandSource>.() -> Unit = {}): LiteralArgumentBuilder<FabricClientCommandSource> {
        return ClientCommandManager.literal(name).apply(init)
    }

    /**
     * Helper method to add an executor to a command builder.
     */
    fun LiteralArgumentBuilder<FabricClientCommandSource>.runs(
        executor: (CommandContext<FabricClientCommandSource>) -> Unit
    ): LiteralArgumentBuilder<FabricClientCommandSource> {
        return this.executes { context ->
            executor(context)
            1 // Success
        }
    }

    /**
     * Helper method to add a string argument with an executor.
     */
    fun LiteralArgumentBuilder<FabricClientCommandSource>.stringArg(
        name: String,
        executor: (CommandContext<FabricClientCommandSource>, String) -> Unit
    ): LiteralArgumentBuilder<FabricClientCommandSource> {
        return this.then(
            ClientCommandManager.argument(name, StringArgumentType.greedyString())
                .executes { context ->
                    val value = StringArgumentType.getString(context, name)
                    executor(context, value)
                    1
                }
        )
    }
}

