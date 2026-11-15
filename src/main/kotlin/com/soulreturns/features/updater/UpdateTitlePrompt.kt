package com.soulreturns.features.updater

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateService
import com.soulreturns.platform.updater.UpdateSnapshot
import com.soulreturns.platform.updater.UpdateStateChanged
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.GameLoadCookie
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen

/**
 * What the updater says before and on the vanilla title screen: first the one-time "turn on
 * automatic updates?" question, then the update window for a pending offer or automatic download
 * (once per session per version, see [TitlePromptPolicy]). An offer shown here counts as that
 * version's unasked announcement ([UpdateNotices.markSaid]), so joining a server adds no second chat
 * line. Silent in a development run ([UpdateService.automatic]).
 *
 * Two entry points cover every order of events:
 * - [beforeTitle]: wraps vanilla's initial screens (`Gui.buildInitialScreens`, the chain that shows
 *   the accessibility onboarding / ban notice before the main menu). A result that is already final
 *   when loading ends opens the window as the first screen, and vanilla's title screen follows when
 *   the player leaves it. Quick play (a launcher "join server on launch") skips it.
 * - [tick]: whenever a title screen is current. A result that arrives later (or a window the player
 *   left for the menu) is shown there. No overlay check: with Remove Reloading Screen the title
 *   screen is usable while the loading overlay is still up, so waiting for the overlay to go lets
 *   the player leave the menu first.
 */
object UpdateTitlePrompt {
    private val logger = SoulLogger("Soul/Updater")

    // Client thread only.
    private val shown = HashSet<String>()

    /** Vanilla's own next step (the title screen) while the initial window is up. */
    private var after: Runnable? = null

    fun register() {
        InGameUpdatePrompt.register()
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick(it) })
        Events.subscribe<UpdateStateChanged> { onChange(it.snapshot) }
    }

    /** `Gui.buildInitialScreens` returns the head of the screen chain; this runs the update window ahead of it when one is due. */
    @JvmStatic
    fun beforeTitle(vanilla: Runnable, cookie: GameLoadCookie?): Runnable = Runnable {
        val mc = Minecraft.getInstance()
        val quickPlay = cookie?.quickPlayData()?.isEnabled == true
        val window = if (quickPlay) null else try {
            next(null, initial = true)
        } catch (e: Throwable) {
            logger.warn("Update window before the main menu failed", e)
            null
        }
        if (window == null) {
            vanilla.run()
        } else {
            after = vanilla
            mc.setScreenAndShow(window)
        }
    }

    /** The player left the initial window (or it was replaced): on with vanilla's flow, once. */
    fun finishInitial() {
        val next = after ?: return
        after = null
        next.run()
    }

    /** [UpdateScreen.removed]: another mod's screen took over without going through [finishInitial]; run it once nothing else is open. */
    fun finishInitialIfIdle() {
        if (after == null) return
        val mc = Minecraft.getInstance()
        mc.execute { if (mc.gui.screen() == null) finishInitial() }
    }

    private fun tick(mc: Minecraft) {
        if (!UpdateService.automatic) return
        val screen = mc.gui.screen()
        // The initial window was dismissed by something that did not go through it.
        if (screen == null && mc.level == null && after != null) return finishInitial()
        if (screen !is TitleScreen) return
        next(screen, initial = false)?.let { mc.setScreenAndShow(it) }
    }

    /** The window due over [parent] now (null = before the main menu), or null: the question first, then the offer. */
    fun next(parent: Screen?, initial: Boolean = false): UpdateScreen? {
        if (!UpdateService.automatic) return null
        if (cfg.updates.autoInstall && !cfg.updates.autoInstallAsked) markAsked()
        val s = UpdateService.snapshot
        val due = TitlePromptPolicy.due(
            automatic = true,
            autoInstall = cfg.updates.autoInstall,
            autoInstallAsked = cfg.updates.autoInstallAsked,
            canInstallUpdates = UpdateService.canInstallUpdates,
            s = s,
            shown = shown,
        )
        if (due == TitleDue.ASK_AUTO) return UpdateScreen(parent, UpdateScreen.ASK_AUTO, initial)
        if (due != TitleDue.UPDATE) return null
        val r = s.release ?: return null
        shown.add(r.version)
        UpdateNotices.markSaid("available:${r.version}")
        markOutcomeSaid(s)
        return UpdateScreen(parent, UpdateScreen.AUTO, initial)
    }

    /** The question's answer: remembered either way; "Turn on" also starts a pending download. */
    fun answerAutoInstall(turnOn: Boolean) {
        ConfigManager.update { copy(updates = updates.copy(autoInstall = updates.autoInstall || turnOn, autoInstallAsked = true)) }
        if (turnOn) UpdateService.startPendingAutoInstall()
    }

    private fun markAsked() {
        ConfigManager.update { copy(updates = updates.copy(autoInstallAsked = true)) }
    }

    /** An automatic download's outcome reached while this window is open on the title screen. */
    private fun onChange(s: UpdateSnapshot) {
        val r = s.release ?: return
        if (r.version in shown && UpdateScreen.isOpen()) markOutcomeSaid(s)
    }

    /** The window shows an automatic download's "restart required" / failure, so chat and toast stay quiet about it. */
    private fun markOutcomeSaid(s: UpdateSnapshot) {
        val r = s.release ?: return
        if (!s.autoInstall) return
        if (s.phase == UpdatePhase.READY_RESTART) UpdateNotices.markSaid("ready:${r.version}")
        if (s.phase == UpdatePhase.ERROR) UpdateNotices.markSaid("error:${r.version}:${s.error}")
    }
}
