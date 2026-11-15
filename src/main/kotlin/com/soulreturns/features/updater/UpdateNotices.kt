package com.soulreturns.features.updater

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.api.LocationApi
import com.soulreturns.core.events.Events
import com.soulreturns.platform.updater.NotifyGate
import com.soulreturns.platform.updater.UpdateError
import com.soulreturns.platform.updater.UpdateNotice
import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateService
import com.soulreturns.platform.updater.UpdateSnapshot
import com.soulreturns.platform.updater.UpdateStateChanged
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * The updater's chat lines + toasts (spec `mocks/updater/`) and `/soul update`.
 *
 * Unasked notices (an offer, "Updated to vX", a withdrawn version, an automatic install's outcome)
 * wait for [NotifyGate] and are said once per session (per version for offers). Outcomes of a
 * download the player started are said right away, unless the update window is open and shows them.
 * Chat buttons never install: [Update Now] and [What's new] open the update window.
 */
object UpdateNotices {
    const val DOWNLOAD_PAGE = "https://sky.soulreturns.com/download"

    @Volatile
    private var joined = false

    // Client thread only.
    private val said = HashSet<String>()
    private var ticks = 0
    private var lastPhase = UpdatePhase.IDLE

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            joined = true
            ticks = 19 // the next tick checks at once instead of waiting out the 20-tick cadence
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> joined = false }
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick() })
        Events.subscribe<UpdateStateChanged> { onChange(it.snapshot) }
    }

    private fun gateOpen(): Boolean {
        if (Minecraft.getInstance().player == null) return false
        val inKuudra = LocationApi.inSkyblock && LocationApi.mode == "kuudra"
        return NotifyGate.ready(joined, LocationApi.inDungeon, inKuudra)
    }

    private fun tick() {
        if (++ticks % 20 != 0) return
        if (gateOpen()) announce(UpdateService.snapshot)
    }

    /** Outcomes of a click-started download, said as they happen. */
    private fun onChange(s: UpdateSnapshot) {
        val was = lastPhase
        lastPhase = s.phase
        if (was != UpdatePhase.DOWNLOADING || s.autoInstall || UpdateScreen.isOpen()) return
        val r = s.release ?: return
        if (s.phase == UpdatePhase.READY_RESTART) {
            once("ready:${r.version}") { line(text("Soul ").append(ver(r.version)).append(text(" is ready. ")).append(dim("Restart Minecraft to finish."))) }
        } else if (s.phase == UpdatePhase.ERROR) {
            failure(s)
        }
    }

    private fun announce(s: UpdateSnapshot) {
        val updated = s.updated
        if (updated != null) {
            once("updated:${updated.to}") {
                UpdateService.markUpdatedAnnounced()
                line(Component.literal("Updated to v${updated.to}! ").withStyle(ChatFormatting.GREEN).append(button("What's new", "/soul update whatsnew", "What changed since v${updated.from}")))
                UpdateToastHud.show(UpdateToastHud.OK, "Updated to Soul v${updated.to}", "Open /soul for what's new.")
            }
        }
        val urgent = s.urgent
        val r = s.release
        if (urgent != null) {
            once("urgent:${urgent.kind}") {
                val revoked = urgent.kind == UpdateNotice.REVOKED
                val head = if (revoked) "Soul v${s.running} was withdrawn: ${urgent.detail ?: "it has a known problem"}." else "Soul v${s.running} is no longer supported by the backend."
                line(Component.literal(head).withStyle(ChatFormatting.RED))
                if (r != null) line(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY).append(updateButtons(withSkip = false)))
                UpdateToastHud.show(
                    UpdateToastHud.BAD,
                    if (revoked) "Soul v${s.running} was withdrawn" else "Soul v${s.running} is too old",
                    if (r != null) "Update to v${r.version} in chat or /soul." else "Please update Soul.",
                )
            }
        }
        for (n in s.notices) {
            if (n.kind == UpdateNotice.BLOCKED || n.kind == UpdateNotice.OTHER_MINECRAFT || n.kind == UpdateNotice.MESSAGE) {
                once("notice:${n.kind}:${n.message}") {
                    line(Component.literal(n.message).withStyle(if (n.kind == UpdateNotice.BLOCKED) ChatFormatting.RED else ChatFormatting.YELLOW))
                }
            }
        }
        if (r == null) return
        if (s.phase == UpdatePhase.AVAILABLE && !s.skipped && urgent == null) {
            once("available:${r.version}") {
                line(text("Soul ").append(ver(r.version)).append(text(" is out ")).append(dim("(you have v${s.running})")))
                val inc = s.incompatible
                if (inc != null) {
                    line(Component.literal("» $inc. Update it in your launcher. ").withStyle(ChatFormatting.RED).append(button("What's new", "/soul update open", "What changed")))
                    UpdateToastHud.show(UpdateToastHud.UP, "Soul v${r.version} is out", "$inc.")
                } else {
                    line(Component.literal("» ").withStyle(ChatFormatting.DARK_GRAY).append(updateButtons(withSkip = true)))
                    UpdateToastHud.show(UpdateToastHud.UP, "Soul v${r.version} is out", "Click Update Now in chat, or open /soul.")
                }
            }
        } else if (s.phase == UpdatePhase.READY_RESTART && s.autoInstall) {
            once("ready:${r.version}") {
                line(text("Soul ").append(ver(r.version)).append(text(" is downloaded. ")).append(dim("Restart Minecraft to finish. ")).append(button("What's new", "/soul update open", "What changed")))
                UpdateToastHud.show(UpdateToastHud.UP, "Soul v${r.version} downloaded", "Restart Minecraft to finish updating.")
            }
        } else if (s.phase == UpdatePhase.ERROR && s.autoInstall) {
            failure(s)
        }
    }

    private fun failure(s: UpdateSnapshot) {
        val r = s.release ?: return
        once("error:${r.version}:${s.error}") {
            if (s.errorKind == UpdateError.VERIFY) {
                line(Component.literal("Update rejected: the file doesn't match Soul's signed release. Nothing was installed.").withStyle(ChatFormatting.RED))
            } else if (s.errorKind == UpdateError.INCOMPATIBLE) {
                line(Component.literal("Can't install v${r.version}: ${s.error}.").withStyle(ChatFormatting.RED))
            } else {
                line(Component.literal("Update failed: ${s.error}. ").withStyle(ChatFormatting.RED).append(button("Retry", "/soul update retry", "Download v${r.version} again")))
            }
            UpdateToastHud.show(UpdateToastHud.BAD, "Soul update failed", "Retry in chat or /soul.")
        }
    }

    private fun updateButtons(withSkip: Boolean): MutableComponent {
        val c = button("Update Now", "/soul update open", "See what's new, then update", ChatFormatting.GREEN, bold = true)
            .append(" ").append(button("What's new", "/soul update open", "What changed"))
        if (withSkip) c.append(" ").append(button("Skip", "/soul update skip", "No reminders until the next version", ChatFormatting.GRAY))
        return c
    }

    /** The title-screen window already said this (key as in [announce]), so chat and toast stay quiet. */
    fun markSaid(key: String) {
        said.add(key)
    }

    private inline fun once(key: String, block: () -> Unit) {
        if (said.add(key)) block()
    }

    private fun text(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.WHITE)

    private fun dim(s: String): MutableComponent = Component.literal(s).withStyle(ChatFormatting.GRAY)

    private fun ver(v: String): MutableComponent = Component.literal("v$v").withStyle(ChatFormatting.AQUA)

    private fun line(body: Component) {
        Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§8[§6Soul§8]§r ").append(body))
    }

    private fun button(label: String, command: String, hover: String, color: ChatFormatting = ChatFormatting.AQUA, bold: Boolean = false): MutableComponent =
        Component.literal("[$label]").withStyle(
            Style.EMPTY.withColor(color).withBold(bold)
                .withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hover))),
        )

    /** Opens the update window over whatever is on screen (deferred: chat closes first; chat itself is not kept). */
    private fun openWindow(mode: Int) {
        val mc = Minecraft.getInstance()
        mc.execute { mc.setScreenAndShow(UpdateScreen(mc.gui.screen().takeUnless { it is ChatScreen }, mode)) }
    }

    /** `/soul update [check|open|whatsnew|retry|skip|unskip|close]`. Bare: the update window. */
    fun node(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("update")
            .executes {
                openWindow(UpdateScreen.AUTO)
                1
            }
            .then(ClientCommands.literal("check").executes {
                soulChat("§7Checking for updates…")
                UpdateService.checkNow()
                1
            })
            .then(ClientCommands.literal("open").executes {
                openWindow(UpdateScreen.AUTO)
                1
            })
            .then(ClientCommands.literal("whatsnew").executes {
                openWindow(UpdateScreen.WHATS_NEW)
                1
            })
            .then(ClientCommands.literal("retry").executes {
                UpdateService.install()
                openWindow(UpdateScreen.PROGRESS)
                1
            })
            .then(ClientCommands.literal("skip").executes {
                val v = UpdateService.snapshot.release?.version
                UpdateService.skip()
                if (v != null) {
                    line(dim("Skipped v$v. You'll hear about the next version. ").append(button("Undo", "/soul update unskip", "Remind me about v$v again", ChatFormatting.YELLOW)))
                }
                1
            })
            .then(ClientCommands.literal("unskip").executes {
                UpdateService.unskip()
                val v = UpdateService.snapshot.release?.version
                if (v != null) line(dim("v$v is no longer skipped. ").append(updateButtons(withSkip = false)))
                1
            })
            .then(ClientCommands.literal("close").executes {
                UpdateService.closeGame()
                1
            })

    /** `/soul dev update <state>` (dev only): [UpdateService.preview], announced again right away. */
    fun previewNode(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val node = ClientCommands.literal("update")
        for (state in UpdateService.PREVIEW_STATES) {
            node.then(ClientCommands.literal(state).executes {
                said.clear()
                lastPhase = UpdatePhase.IDLE
                joined = true
                UpdateService.preview(state)
                1
            })
        }
        return node
    }
}
