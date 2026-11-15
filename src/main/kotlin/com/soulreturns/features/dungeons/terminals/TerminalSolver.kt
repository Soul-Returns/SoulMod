package com.soulreturns.features.dungeons.terminals

import com.soulreturns.config.TerminalSolverConfig
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.inventory.Slot

/**
 * F7/M7 terminal solver. A chest menu whose title and size match a terminal gets a
 * [TerminalSession]; with the custom GUI on, [TerminalScreen] replaces the vanilla screen while the
 * real menu stays open underneath, otherwise highlights are drawn over the vanilla slots.
 *
 * Anti-cheat: nothing here ever clicks on its own. Each physical click sends at most one container
 * click on the slot under the cursor, at once, with the player's button ([TerminalSession.click]).
 */
object TerminalSolver {
    private var registered = false

    /** The open terminal, if any. A new menu replaces it; a closed one is dropped on the next open. */
    var session: TerminalSession? = null
        private set

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ -> onInit(screen) })
        Events.subscribe<ChatMessage> { onChat(it) }
    }

    /** Lines that come with the same pling or error sound as a melody lock's answer (anyone's completions, refusals). */
    private val COMPLETION = Regex("(activated|completed) a (terminal|lever|device)!|already activated|already been completed")

    private fun onChat(msg: ChatMessage) {
        if (msg.source != ChatMessage.Source.SERVER) return
        val s = session ?: return
        if (s.live && COMPLETION.containsMatchIn(MessageDetector.stripColorCodes(msg.raw))) s.onCompletionChat()
    }

    /** `ClientPacketListenerMixin.handleSoundEvent` HEAD, both passes; only the client-thread pass counts. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!Minecraft.getInstance().isSameThread) return
        val s = session ?: return
        if (s.live) s.onSound(packet.sound.value().location().path)
    }

    fun typeOn(c: TerminalSolverConfig, type: TerminalType): Boolean = when (type) {
        TerminalType.StartsWith -> c.startsWith
        TerminalType.Select -> c.selectColor
        TerminalType.Panes -> c.panes
        TerminalType.Order -> c.order
        TerminalType.Rubix -> c.sameColor
        TerminalType.Melody -> c.melody
    }

    private fun onInit(screen: Screen) {
        if (screen !is ContainerScreen) return
        val existing = session
        if (existing != null && existing.menu === screen.menu) {
            // Re-init of a known menu (resize, or our GUI handing it back): never take it over twice.
            if (existing.state != TerminalSession.FAILED && !cfg.dungeons.terminals.customGui) attachHighlights(screen, existing)
            return
        }
        session = null
        val c = cfg.dungeons.terminals
        if (!c.enabled) return
        val spec = TerminalType.recognize(MessageDetector.stripColorCodes(screen.title.string), screen.menu.rowCount * 9) ?: return
        if (!typeOn(c, spec.type)) return
        val s = TerminalSession(screen.menu, spec)
        session = s
        s.poll()
        if (c.customGui) {
            // Not inside init: the vanilla screen finishes opening first, then ours replaces it
            // before the next frame.
            val mc = Minecraft.getInstance()
            mc.execute { if (mc.gui.screen() === screen && s.live) mc.gui.setScreen(TerminalScreen(screen, s)) }
        } else {
            attachHighlights(screen, s)
        }
    }

    // ---------------------------------------------------------------- highlight-only mode

    private var blockedButton = -1

    /** The vanilla terminal screen that gets highlights, drawn per slot from [onExtractSlot]. */
    private var highlighted: ContainerScreen? = null

    private fun attachHighlights(screen: ContainerScreen, s: TerminalSession) {
        highlighted = screen
        ScreenEvents.afterBackground(screen).register(ScreenEvents.AfterBackground { _, _, _, _, _ -> s.poll() })
        ScreenEvents.remove(screen).register(ScreenEvents.Remove { if (highlighted === screen) highlighted = null })
        ScreenMouseEvents.allowMouseClick(screen).register(
            ScreenMouseEvents.AllowMouseClick { _, click ->
                val slot = slotAt(screen, click)
                if (slot >= 0 && click.button() == LEFT && s.state == TerminalSession.ACTIVE && cfg.dungeons.terminals.leftAsMiddle) {
                    // Sent by us as a middle click (or blocked); vanilla must not also send its left click.
                    s.click(slot, LEFT)
                    blockedButton = LEFT
                    return@AllowMouseClick false
                }
                if (slot >= 0 && !s.allowVanillaClick(slot, click.button())) {
                    blockedButton = click.button()
                    return@AllowMouseClick false
                }
                blockedButton = -1
                s.beginVanillaClick()
                true
            },
        )
        ScreenMouseEvents.afterMouseClick(screen).register(ScreenMouseEvents.AfterMouseClick { _, _, consumed -> s.endVanillaClick(); consumed })
        ScreenMouseEvents.allowMouseRelease(screen).register(
            ScreenMouseEvents.AllowMouseRelease { _, click ->
                if (click.button() == blockedButton) {
                    blockedButton = -1
                    return@AllowMouseRelease false
                }
                s.beginVanillaClick()
                true
            },
        )
        ScreenMouseEvents.afterMouseRelease(screen).register(ScreenMouseEvents.AfterMouseRelease { _, _, consumed -> s.endVanillaClick(); consumed })
    }

    /** The terminal board slot under the click, or -1 (vanilla's hover box: the 16 px slot plus 1 px). */
    private fun slotAt(screen: ContainerScreen, click: MouseButtonEvent): Int {
        val x = click.x() - screen.leftPos
        val y = click.y() - screen.topPos
        val s = session ?: return -1
        for (i in s.spec.type.board) {
            val slot = screen.menu.getSlot(i)
            if (x >= slot.x - 1 && x < slot.x + 17 && y >= slot.y - 1 && y < slot.y + 17) return i
        }
        return -1
    }

    /**
     * Highlight-only mode, called after vanilla draws each slot (`AbstractContainerScreenMixin`, pose at
     * the screen's top left): below tooltips and the carried item, above the slot's item.
     */
    @JvmStatic
    fun onExtractSlot(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, slot: Slot) {
        if (screen !== highlighted) return
        val s = session ?: return
        if (s.state != TerminalSession.ACTIVE || s.menu !== screen.menu) return
        val cell = s.solution?.cells?.get(slot.index) ?: return
        val type = s.spec.type
        val x = slot.x
        val y = slot.y
        val fill = TerminalColors.overlay(cell, type)
        if (fill != 0) g.fill(x, y, x + 16, y + 16, fill)
        if (cell.state == TerminalTracker.TARGET && type !== TerminalType.Rubix) outline(g, x - 1, y - 1, x + 17, y + 17, if (type === TerminalType.Melody) TerminalColors.NOW else TerminalColors.TARGET_EDGE)
        if (type === TerminalType.Rubix && cell.clicks != 0) {
            // The action over the pane, as in Soul's window: signed count, minus = right clicks.
            val font = Minecraft.getInstance().font
            val text = cell.clicks.toString()
            g.text(font, text, x + (16 - font.width(text) + 1) / 2, y + 4, 0xFFFFFFFF.toInt(), true)
        }
        val f = s.flash(slot.index)
        if (f > 0f) {
            g.fill(x, y, x + 16, y + 16, TerminalColors.flashFill(f))
            outline(g, x, y, x + 16, y + 16, TerminalColors.flashEdge(f))
        }
    }

    private fun outline(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.fill(x0, y0, x1, y0 + 1, color)
        g.fill(x0, y1 - 1, x1, y1, color)
        g.fill(x0, y0, x0 + 1, y1, color)
        g.fill(x1 - 1, y0, x1, y1, color)
    }
}
