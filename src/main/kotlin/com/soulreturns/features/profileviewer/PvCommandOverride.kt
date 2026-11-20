package com.soulreturns.features.profileviewer

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.Suggestions
import java.util.concurrent.CompletableFuture

internal object PvCommandOverride {
    fun handles(command: String, enabled: Boolean): Boolean =
        enabled && (command == "pv" || command.startsWith("pv "))

    fun <S> dispatcher(
        original: CommandDispatcher<S>,
        soul: CommandDispatcher<S>,
        input: String,
        enabled: Boolean,
    ): CommandDispatcher<S> = if (handles(input.removePrefix("/"), enabled)) soul else original

    fun completeRoot(
        original: CompletableFuture<Suggestions>,
        input: String,
        cursor: Int,
        enabled: Boolean,
    ): CompletableFuture<Suggestions> {
        if (!enabled) return original
        val start = if (input.startsWith("/")) 1 else 0
        if (cursor < start || cursor > input.length) return original
        val typed = input.substring(start, cursor)
        if (!"pv".startsWith(typed)) return original
        val range = StringRange.between(start, cursor)
        val pv = Suggestions(range, listOf(Suggestion(range, "pv")))
        return original.thenApply { Suggestions.merge(input, listOf(it, pv)) }
    }
}
