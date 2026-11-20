package com.soulreturns.features.profileviewer

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.suggestion.Suggestions
import com.soulreturns.config.ProfileViewerConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PvCommandOverrideTest {
    @Test
    fun `new and existing configs enable pv by default and preserve explicit off`() {
        assertTrue(ProfileViewerConfig().pvCommand)
        assertTrue(Json.decodeFromString<ProfileViewerConfig>("{}").pvCommand)
        assertFalse(Json.decodeFromString<ProfileViewerConfig>("{\"pvCommand\":false}").pvCommand)
    }

    @Test
    fun `matches only exact lowercase pv and its argument boundary`() {
        listOf("pv", "pv Alice", "pv Alice Banana", "pv ", "pv  Alice", "pv Alice extra args").forEach {
            assertTrue(PvCommandOverride.handles(it, true), it)
            assertFalse(PvCommandOverride.handles(it, false), it)
        }
        listOf("", "p", "pvp", "pvname", "PV Alice", "/pv", " pv", "sp", "spv", "soul pv", "pv\tAlice").forEach {
            assertFalse(PvCommandOverride.handles(it, true), it)
        }
    }

    private fun soul(opened: MutableList<String>): CommandDispatcher<Unit> = CommandDispatcher<Unit>().apply {
        register(
            literal<Unit>("pv").executes { opened.add("self"); 1 }
                .then(argument<Unit, String>("name", StringArgumentType.word())
                    .suggests { _, b -> b.suggest("Alice").buildFuture() }
                    .executes { opened.add(StringArgumentType.getString(it, "name")); 1 }
                    .then(argument<Unit, String>("profile", StringArgumentType.word()).executes {
                        opened.add("${StringArgumentType.getString(it, "name")}:${StringArgumentType.getString(it, "profile")}")
                        1
                    })),
        )
    }

    private fun competitor(shared: CommandDispatcher<Unit>, calls: MutableList<String>) {
        shared.register(literal<Unit>("pv").executes { calls.add("other:self"); 2 }
            .then(argument<Unit, String>("otherName", StringArgumentType.greedyString()).executes {
                calls.add("other:${StringArgumentType.getString(it, "otherName")}"); 2
            }))
    }

    private fun conflict(otherFirst: Boolean) {
        val calls = mutableListOf<String>()
        val shared = CommandDispatcher<Unit>()
        if (otherFirst) competitor(shared, calls)
        val own = soul(calls)
        if (!otherFirst) competitor(shared, calls)
        val otherNode = shared.root.getChild("pv")
        for (command in listOf("pv", "pv Alice", "pv Alice Banana")) {
            val selected = PvCommandOverride.dispatcher(shared, own, command, true)
            assertSame(own, selected)
            assertEquals(1, selected.execute(command, Unit))
        }
        assertEquals(listOf("self", "Alice", "Alice:Banana"), calls)
        assertSame(otherNode, shared.root.getChild("pv"))
        assertEquals(2, PvCommandOverride.dispatcher(shared, own, "pv", false).execute("pv", Unit))
        assertEquals(2, PvCommandOverride.dispatcher(shared, own, "pv Alice", false).execute("pv Alice", Unit))
        assertEquals(listOf("other:self", "other:Alice"), calls.takeLast(2))
        assertEquals(1, PvCommandOverride.dispatcher(shared, own, "pv", true).execute("pv", Unit))
    }

    @Test fun `competing literal registered before Soul stays intact across live toggle`() = conflict(true)
    @Test fun `competing literal registered after Soul stays intact across live toggle`() = conflict(false)

    @Test
    fun `off with no competitor falls through to the server dispatcher`() {
        val shared = CommandDispatcher<Unit>()
        val own = soul(mutableListOf())
        assertSame(shared, PvCommandOverride.dispatcher(shared, own, "/pv Alice", false))
        assertEquals(null, shared.root.getChild("pv"))
        assertFailsWith<CommandSyntaxException> { shared.execute("pv Alice", Unit) }
        assertSame(shared, PvCommandOverride.dispatcher(shared, own, "/spv Alice", true))
    }

    @Test
    fun `argument completion uses Soul without adding a shared literal`() {
        val shared = CommandDispatcher<Unit>()
        val own = soul(mutableListOf())
        val input = "/pv "
        val selected = PvCommandOverride.dispatcher(shared, own, input, true)
        val reader = StringReader(input).apply { skip() }
        val suggestions = selected.getCompletionSuggestions(selected.parse(reader, Unit)).join()
        assertEquals(listOf("Alice"), suggestions.list.map { it.text })
        assertEquals(4, suggestions.range.start)
        assertEquals(null, shared.root.getChild("pv"))
    }

    @Test
    fun `root completions merge pv without losing another command`() {
        val shared = CommandDispatcher<Unit>().apply { register(literal<Unit>("party")) }
        for (input in listOf("/", "/p", "/pv")) {
            val reader = StringReader(input).apply { skip() }
            val original = shared.getCompletionSuggestions(shared.parse(reader, Unit))
            val suggestions = PvCommandOverride.completeRoot(original, input, input.length, true).join()
            assertTrue(suggestions.list.any { it.text == "pv" }, input)
            if (input != "/pv") assertTrue(suggestions.list.any { it.text == "party" }, input)
            assertSame(original, PvCommandOverride.completeRoot(original, input, input.length, false))
        }
    }

    @Test
    fun `root completion does not offer pv inside arguments or unrelated commands`() {
        val original = Suggestions.empty()
        for (input in listOf("/pv Alice", "/sp", "/pvp", "/party p", "hello")) {
            assertSame(original, PvCommandOverride.completeRoot(original, input, input.length, true))
        }
        assertSame(original, PvCommandOverride.completeRoot(original, "/", 0, true))
    }

    @Test
    fun `malformed Soul arguments stay on Soul while enabled`() {
        val shared = CommandDispatcher<Unit>()
        val calls = mutableListOf<String>()
        competitor(shared, calls)
        val own = soul(calls)
        val command = "pv Alice Banana extra"
        assertFailsWith<CommandSyntaxException> {
            PvCommandOverride.dispatcher(shared, own, command, true).execute(command, Unit)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `Brigadier merges commands last-wins but preserves original requirements and different children`() {
        val dispatcher = CommandDispatcher<Unit>()
        val first = dispatcher.register(literal<Unit>("pv").requires { false }.executes { 1 }
            .then(argument<Unit, String>("first", StringArgumentType.word()).executes { 3 }))
        dispatcher.register(literal<Unit>("pv").requires { true }.executes { 2 }
            .then(argument<Unit, String>("second", StringArgumentType.word()).executes { 4 }))
        assertEquals(2, first.command.run(com.mojang.brigadier.context.CommandContextBuilder(dispatcher, Unit, dispatcher.root, 0).build("pv")))
        assertFalse(first.canUse(Unit))
        assertEquals(listOf("first", "second"), first.children.map { it.name })
    }
}
