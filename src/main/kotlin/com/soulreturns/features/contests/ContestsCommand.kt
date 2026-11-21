package com.soulreturns.features.contests

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.FriendsBody
import com.soulreturns.features.contests.model.Roles
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/**
 * `/soul friends [add|accept|decline <name>]` and `/soul contests [open|join|watch|decline <id> | results [id] | history]`.
 * The chat buttons run these. Screens open deferred (chat closes first).
 */
object ContestsCommand {
    private fun later(block: () -> Unit) = Minecraft.getInstance().execute(block)

    fun friendsNode(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("friends")
            .executes {
                later { ContestsNav.openFriends() }
                1
            }
            .then(ClientCommands.literal("add").then(ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                val name = StringArgumentType.getString(ctx, "name")
                ContestsStore.addFriend(name) { r ->
                    ContestsChat.info(
                        if (!r.ok) r.message ?: "Could not send the request."
                        else if (r.friendResult == "accepted") "You and $name are Soul friends now."
                        else "Request sent to $name. They see it the next time they play.",
                    )
                }
                1
            }))
            .then(ClientCommands.literal("accept").then(ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                answer(StringArgumentType.getString(ctx, "name"), accept = true)
                1
            }))
            .then(ClientCommands.literal("decline").then(ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                answer(StringArgumentType.getString(ctx, "name"), accept = false)
                1
            }))

    /** Finds the incoming request by name (refetching the list first when it is not loaded), then answers it. */
    private fun answer(name: String, accept: Boolean) {
        fun act(body: FriendsBody?) {
            val row = body?.incoming?.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (row == null) {
                ContestsChat.info("No friend request from $name.")
                return
            }
            val done: (ActionResult) -> Unit = { r ->
                ContestsChat.info(if (!r.ok) r.message ?: "That did not work." else if (accept) "You and ${row.name} are Soul friends now." else "Declined ${row.name}'s request.")
            }
            if (accept) ContestsStore.acceptFriend(row.uuid, done) else ContestsStore.declineFriend(row.uuid, done)
        }
        val held = ContestsStore.friends.value
        if (held != null && held.incoming.any { it.name.equals(name, ignoreCase = true) }) act(held)
        else ContestsStore.refreshFriends { act(ContestsStore.friends.value) }
    }

    fun contestsNode(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("contests")
            .executes {
                later {
                    val running = ContestsStore.runningSnapshots().firstOrNull()
                    if (running != null) ContestsNav.openContest(running.contest.id) else ContestsNav.openHome()
                }
                1
            }
            .then(ClientCommands.literal("history").executes {
                later { ContestsNav.openHistory() }
                1
            })
            .then(ClientCommands.literal("results")
                .executes {
                    later {
                        val id = latestFinished()
                        if (id != null) ContestsNav.openResults(id) else ContestsNav.openHistory()
                    }
                    1
                }
                .then(idArg().executes { ctx ->
                    val id = LongArgumentType.getLong(ctx, "id")
                    later { ContestsNav.openResults(id) }
                    1
                }))
            .then(ClientCommands.literal("open").then(idArg().executes { ctx ->
                val id = LongArgumentType.getLong(ctx, "id")
                later { ContestsNav.openContest(id) }
                1
            }))
            .then(ClientCommands.literal("join").then(idArg().executes { ctx ->
                respond(LongArgumentType.getLong(ctx, "id"), Roles.PARTICIPANT)
                1
            }))
            .then(ClientCommands.literal("watch").then(idArg().executes { ctx ->
                respond(LongArgumentType.getLong(ctx, "id"), Roles.SPECTATOR)
                1
            }))
            .then(ClientCommands.literal("decline").then(idArg().executes { ctx ->
                val id = LongArgumentType.getLong(ctx, "id")
                ContestsStore.declineInvite(id) { r -> ContestsChat.info(if (r.ok) "Invitation declined." else r.message ?: "That did not work.") }
                1
            }))

    private fun idArg() = ClientCommands.argument("id", LongArgumentType.longArg(1))

    private fun respond(id: Long, role: String) {
        ContestsStore.join(id, role) { r ->
            if (!r.ok) {
                ContestsChat.info(r.message ?: "That did not work.")
                return@join
            }
            val snap = ContestsStore.snapshot(id).value
            val name = snap?.contest?.name ?: "the contest"
            val text = when {
                snap?.me?.status == com.soulreturns.features.contests.model.MemberStatuses.REQUESTED -> "Asked to join $name. The owner or a manager has to accept."
                role == Roles.SPECTATOR -> "You are watching $name."
                else -> "You joined $name with this SkyBlock profile."
            }
            ContestsChat.info(text)
        }
    }

    /** Your most recently ended contest: a held snapshot or the list's history. */
    private fun latestFinished(): Long? {
        val held = ContestsStore.heldSnapshots().filter { it.contest.state == ContestStates.FINISHED }
            .maxByOrNull { it.contest.endedAt ?: 0 }
        val listed = ContestsStore.contests.value?.history?.filter { it.state == ContestStates.FINISHED }
            ?.maxByOrNull { it.endedAt ?: 0 }
        if (held == null) return listed?.id
        if (listed == null) return held.contest.id
        return if ((held.contest.endedAt ?: 0) >= (listed.endedAt ?: 0)) held.contest.id else listed.id
    }
}
