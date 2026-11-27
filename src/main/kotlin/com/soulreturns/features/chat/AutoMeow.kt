package com.soulreturns.features.chat

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.Shapes
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.party.PartyManager
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.HudElement
import com.soulreturns.ui.hud.SoulHud
import com.soulreturns.util.MessageDetector
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import kotlin.math.roundToInt

/**
 * Auto meow (S-70, spec `mocks/meow/`): when someone writes exactly "meow" in a chat the player picked,
 * Soul answers "meow" there (rules in [MeowResponder]). Always the bare word: no outgoing `[Soul]`
 * prefix. A secret card: it exists nowhere in the menu until found by typing "meow" into the `/soul`
 * search ([unlockBySearch], the menu plays the reveal) or with `/soul meow` ([node]; the reveal plays
 * over the game, and every later use replays it).
 */
object AutoMeow {
    private const val PREFIX = "§9§lSoul §8» "
    private const val UNLOCK_LINE = "${PREFIX}§7Meow! Secret found: §bAuto meow§7. It's in §e/soul§7 now."
    private const val REPLAY_LINE = "${PREFIX}§7Meow again! §8(/soul meow replays the reveal)"

    private val responder = MeowResponder(System::currentTimeMillis)

    fun register() {
        Events.subscribe<ChatMessage> { onChat(it.raw) }
        SoulHud.add(MeowOverlay)
        // Until found, the `meow` node requires the unlock, so no tab completion, usage or "unknown argument"
        // hint lists it. Typed in full it still opens the door: this runs before Fabric's client commands
        // (message API HEAD 800 < command API 1000, rule://mc-api) and swallows the command.
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            if (cfg.meow.unlocked || command.trim().split(WHITESPACE) != listOf("soul", "meow")) return@register true
            reveal()
            false
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun onChat(raw: String) {
        val c = cfg.meow
        if (!c.unlocked || !c.enabled) return
        val player = Minecraft.getInstance().player ?: return
        val channel = responder.answer(
            MessageDetector.stripColorCodes(raw),
            player.gameProfile.name,
            { ch -> enabled(ch) },
            PartyManager.isInParty(),
        ) ?: return
        player.connection.sendCommand("${channel.command} meow")
    }

    private fun enabled(ch: MeowChannel): Boolean {
        val c = cfg.meow
        return when (ch.name) {
            "COOP" -> c.coop
            "PARTY" -> c.party
            "PRIVATE" -> c.privateMessages
            "GUILD" -> c.guild
            else -> c.all
        }
    }

    private fun unlock() = ConfigManager.update { copy(meow = meow.copy(unlocked = true, enabled = true)) }

    /** The `/soul` search: exactly "meow" (any case, spaces trimmed) unlocks the card. True = just unlocked, play the reveal. */
    fun unlockBySearch(query: String): Boolean {
        if (cfg.meow.unlocked || !query.trim().equals("meow", ignoreCase = true)) return false
        unlock()
        return true
    }

    /** `/soul meow` once found (suggested from then on); [register] handles it while still secret. */
    fun node(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("meow").requires { cfg.meow.unlocked }.executes {
            reveal()
            1
        }

    /** The second door: unlocks on first use; always plays the reveal over the game with its chat line. */
    private fun reveal() {
        val first = !cfg.meow.unlocked
        if (first) unlock()
        val mc = Minecraft.getInstance()
        mc.execute {
            mc.player?.sendSystemMessage(Component.literal(if (first) UNLOCK_LINE else REPLAY_LINE))
            MeowOverlay.play(first)
        }
    }
}

/**
 * The reveal over the game (mock scenes "/soul meow"): a small card near the top center at 1.3×, the
 * prints walking up to it and the cat peeking from behind; the card fades last. Starts once no screen
 * is open (the chat the command was typed in has closed), hides while one is, never takes input.
 */
object MeowOverlay : HudElement("meow_reveal") {
    /** Mock frame: the card's center line and the scale origin's height. */
    private const val ORIGIN_Y = 150f
    private const val SCALE = 1.3f
    private const val CARD_W = 220
    private const val CARD_H = 44
    private const val CARD_Y = 128

    private var pending: Boolean? = null
    private var playing = false
    private var first = true
    private var startedAt = 0L

    override val foreground: Boolean get() = true

    fun play(first: Boolean) {
        pending = first
    }

    private fun t(): Double = (System.currentTimeMillis() - startedAt) / 1000.0

    override fun isEnabled(): Boolean {
        if (Minecraft.getInstance().gui.screen() != null) return false
        pending?.let {
            first = it
            startedAt = System.currentTimeMillis()
            playing = true
            pending = null
        }
        if (playing && t() >= MeowReveal.FADE_AT + MeowReveal.FADE_LEN) playing = false
        return playing
    }

    override fun extract(g: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        val f = SoulUi.factor()
        val cx = (mc.window.guiScaledWidth / f / 2f).roundToInt()
        val t = t()
        val alpha = MeowReveal.fadeAlpha(t)
        val feather = 1f / (SoulUi.scale * SCALE)
        g.pose().pushMatrix()
        g.pose().translate(0f, MeowReveal.fadeLift(t))
        g.pose().translate(cx.toFloat(), ORIGIN_Y)
        g.pose().scale(SCALE, SCALE)
        g.pose().translate(-cx.toFloat(), -ORIGIN_Y)
        MeowReveal.trail(g, t, cx - 310f, 380f, cx - 40f, 178f, 9, feather, alpha)
        MeowReveal.cat(g, t, cx - MeowReveal.CAT_W / 2, CARD_Y + MeowReveal.CAT_OVERLAP - MeowReveal.CAT_H, feather)
        card(g, t, cx, alpha * MeowReveal.popAlpha(t), feather)
        g.pose().popMatrix()
    }

    private fun card(g: GuiGraphicsExtractor, t: Double, cx: Int, a: Float, feather: Float) {
        if (a <= 0.004f) return
        val x0 = cx - CARD_W / 2
        val x1 = x0 + CARD_W
        val y0 = CARD_Y
        val y1 = y0 + CARD_H
        val s = MeowReveal.popScale(t)
        g.pose().pushMatrix()
        g.pose().translate(cx.toFloat(), y1.toFloat())
        g.pose().scale(s, s)
        g.pose().translate(-cx.toFloat(), -y1.toFloat())
        SoulPanel.draw(g, x0, y0, x1, y1, SoulPanel.LARGE, fade(0xEB181818.toInt(), a), fade(Theme.ACCENT, a))
        val cy = (y0 + y1) / 2
        rect(g, x0 + 10, cy - 15, x0 + 40, cy + 15, fade(Theme.ACCENT_TINT, a), SoulRenderPipelines.ROUND_MEDIUM)
        Shapes(feather / s).also { MeowReveal.paw(it, x0 + 25f, cy.toFloat(), 18f, 0.0, fade(MeowReveal.ACCENT_LIGHT, a)) }.submit(g)
        val semi = MsdfFonts.menuSemibold
        val name = 13f * MsdfFont.LEGACY_EM
        val sub = 10f * MsdfFont.LEGACY_EM
        SoulText.drawVCentered(g, "Auto meow", x0 + 50, cy - 7, name, fade(Theme.TEXT, a), semi, shadow = false)
        SoulText.drawVCentered(g, if (first) "Secret found. It is in /soul now." else "Meow again!", x0 + 50, cy + 8, sub, fade(Theme.TEXT_DIM, a), MsdfFonts.menuRegular, shadow = false)
        secretChip(g, x1 - 8, y0 - 8 + 6, a, feather / s)
        g.pose().popMatrix()
    }

    private val CHIP_SIZE = 8.5f * MsdfFont.LEGACY_EM
    private val CHIP_TRACKING = 0.08f * CHIP_SIZE

    /** The paw + SECRET chip with its right edge at [right], centered on [cy]. */
    private fun secretChip(g: GuiGraphicsExtractor, right: Int, cy: Int, a: Float, feather: Float) {
        val semi = MsdfFonts.menuSemibold
        val textW = kotlin.math.ceil(SoulText.widthTracked("SECRET", CHIP_SIZE, semi, CHIP_TRACKING)).toInt()
        val w = 4 + 9 + 3 + textW + 4
        val x0 = right - w
        // The menu's NEW_BG (accent 16 %) over the card's #181818, opaque: half of it sits over the world.
        rect(g, x0, cy - 6, right, cy + 6, fade(0xFF1E293B.toInt(), a), SoulRenderPipelines.ROUND_SMALL)
        Shapes(feather).also { MeowReveal.paw(it, x0 + 8.5f, cy.toFloat(), 9f, 0.0, fade(MeowReveal.ACCENT_LIGHT, a)) }.submit(g)
        SoulText.drawCenteredTracked(g, "SECRET", x0 + 16 + textW / 2, cy, CHIP_SIZE, fade(MeowReveal.ACCENT_LIGHT, a), semi, CHIP_TRACKING, shadow = false)
    }

    private fun rect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, pipeline: RenderPipeline) {
        if (color ushr 24 == 0) return
        g.guiRenderState.addGuiElement(RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null))
    }

    private fun fade(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)
}
