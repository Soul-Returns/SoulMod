package com.soulreturns.features.inventorybuttons

import com.soulreturns.api.LocationApi
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.InventoryButton
import com.soulreturns.config.InventoryButtonsConfig
import com.soulreturns.config.InventoryButtonsConfig.Companion.DEFAULT_ID
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.dungeons.DungeonCommandGuard
import com.soulreturns.features.inventory.EquipmentDisplay
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.ui.SoulUi
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Draws the configured buttons on container screens and runs a button's command on left click.
 * Right click opens the editor with that button selected.
 *
 * - **Profiles**: the set shown is the profile the active-profile mapping names for where the player
 *   is ([InventoryButtonProfiles.resolve]: dungeon-run slot, area slot, Everywhere, Default). Chosen
 *   when a container opens and again when the area or the dungeon-run state changes while it is open.
 * - **Spots**: each button sits on its grid spot ([InventoryButtonSpots]) for this screen's size and
 *   frame; a spot that does not exist here hides the button.
 * - **Hidden buttons** (player inventory only): a notice at the top offers Move…, which opens the
 *   [HiddenButtonsTray]; a button dragged out of it and dropped on a free spot moves there for good.
 *   Drawn at `afterExtract`, over everything; the tray closes with the inventory.
 * - **Drawn at `afterBackground`**: after the container texture, before the slots' items, the
 *   carried stack and the tooltip, so a held item and a hover tooltip both render over a button.
 * - **Every container but creative.** The creative inventory has its own layout and tabs where
 *   buttons would land; Hypixel never opens it. [InventoryButton.inventoryOnly] narrows a button
 *   to the player inventory.
 * - **A click on a button is consumed** (`allowMouseClick` returns false), or it would also reach
 *   the slot or the "click outside drops the held item" path behind it.
 * - Commands take the same route as command keybinds — `sendCommand`, so Fabric client commands
 *   such as `/soul` work too.
 * - **Menu icon pick** ([startPick]): the next *physical* middle click on an item in any container
 *   copies its look onto the waiting button and reopens the editor. It reads the raw mouse button
 *   in `allowMouseClick`, before any slot-click handling, so features that rewrite a left click
 *   into a middle click at the slot level (other mods' "middle click in SkyBlock GUIs", or ours
 *   later) never trigger it. The pick click is consumed, so the menu never sees it.
 */
object InventoryButtons {
    private const val SIZE = InventoryButtonSpots.SIZE
    private val PICK_NOTICE = 0xFFFFD744.toInt()
    const val DROP_FILL = 0x407EE08A
    val DROP_EDGE = 0xE67EE08A.toInt()

    /** Notice + tray over the player inventory. Render thread only. */
    private val tray = HiddenButtonsTray()

    /** Index of the button waiting for a middle-clicked icon, and its profile ([DEFAULT_ID] = Default). Render thread only. */
    private var pickTarget = -1
    private var pickProfile = DEFAULT_ID

    /** The profile whose buttons show. */
    private var activeId = DEFAULT_ID

    /** True while a middle click is reserved for the icon pick; other middle-click features stand down. */
    val picking: Boolean get() = pickTarget >= 0

    fun startPick(profileId: String, index: Int) {
        pickProfile = profileId
        pickTarget = index
    }

    fun cancelPick() {
        pickTarget = -1
    }

    /** The profile that applies where the player is right now ([DEFAULT_ID] for Default). */
    fun liveProfileId(): String =
        InventoryButtonProfiles.resolve(cfg.inventoryButtons, SkyblockLocation.area, LocationApi.inDungeon)

    fun buttonsOf(config: InventoryButtonsConfig, profileId: String): List<InventoryButton>? =
        if (profileId == DEFAULT_ID) config.buttons else config.profiles.firstOrNull { it.id == profileId }?.buttons

    private fun activeButtons(): List<InventoryButton> {
        val config = cfg.inventoryButtons
        return buttonsOf(config, activeId) ?: config.buttons
    }

    fun register() {
        Events.subscribe<AreaChanged> { event ->
            activeId = liveProfileId()
            remember(event.current)
        }
        // An area entered before the connection's network was known is remembered once it is production.
        Events.subscribe<PlayerDataset.Resolved> { if (it.dataset == PlayerDataset.PRODUCTION) remember(SkyblockLocation.area) }
        Events.subscribe<LocationApi.DungeonStateEvent> { activeId = liveProfileId() }
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is AbstractContainerScreen<*> || screen is CreativeModeInventoryScreen) return@AfterInit
                activeId = liveProfileId()
                ScreenEvents.afterBackground(screen).register(
                    ScreenEvents.AfterBackground { _, g, mouseX, mouseY, _ -> draw(screen, g, mouseX, mouseY) },
                )
                ScreenMouseEvents.allowMouseClick(screen).register(
                    ScreenMouseEvents.AllowMouseClick { _, click -> !click(screen, click) },
                )
                if (screen is InventoryScreen) registerTray(screen)
            },
        )
    }

    private fun registerTray(screen: InventoryScreen) {
        tray.reset()
        ScreenEvents.afterExtract(screen).register(
            ScreenEvents.AfterExtract { _, g, mouseX, mouseY, _ -> drawTray(screen, g, mouseX, mouseY) },
        )
        ScreenEvents.remove(screen).register(ScreenEvents.Remove { _ -> tray.reset() })
        ScreenMouseEvents.allowMouseDrag(screen).register(ScreenMouseEvents.AllowMouseDrag { _, _, _, _ -> !tray.busy })
        ScreenMouseEvents.allowMouseRelease(screen).register(
            ScreenMouseEvents.AllowMouseRelease { _, click ->
                if (!tray.busy) return@AllowMouseRelease true
                val index = tray.release()
                val target = layout(screen).spotAt(click.x(), click.y())?.id
                val buttons = activeButtons()
                if (index in buttons.indices && target != null && buttons.none { it.spot == target }) {
                    updateButton(activeId, index) { copy(spot = target) }
                }
                false
            },
        )
    }

    /**
     * Add [area] to the picker's list the first time the client is there; not in a run, which is its own condition.
     * `config.json` is shared by both datasets, so an Alpha (or not yet confirmed) area is not stored.
     */
    private fun remember(area: String?) {
        if (area.isNullOrBlank() || LocationApi.inDungeon || area in cfg.inventoryButtons.seenAreas) return
        if (!PlayerDataset.confirmedProduction()) return
        ConfigManager.update { copy(inventoryButtons = inventoryButtons.copy(seenAreas = inventoryButtons.seenAreas + area)) }
    }

    /**
     * GUI-px rects (x, y, width, height) of the buttons drawn on [boxesScreen] in its last frame, a new list
     * only when one moves: REI keeps its item list off them (`InventoryReiPlugin`).
     */
    private var boxes: List<IntArray> = emptyList()
    private var boxesScreen: AbstractContainerScreen<*>? = null

    /** The rects of the buttons drawn on [screen] (none before its first frame). */
    fun drawnBoxes(screen: AbstractContainerScreen<*>): List<IntArray> = if (screen === boxesScreen) boxes else emptyList()

    private fun draw(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (pickTarget >= 0) {
            g.centeredText(Minecraft.getInstance().font, "Middle-click an item to use it as the button icon", screen.width / 2, 4, PICK_NOTICE)
        }
        boxesScreen = screen
        val buttons = activeButtons()
        if (buttons.isEmpty()) {
            boxes = emptyList()
            return
        }
        val layout = layout(screen)
        val hovered = hit(screen, layout, buttons, mouseX.toDouble(), mouseY.toDouble())
        var drawn = 0
        var moved = false
        for (index in buttons.indices) {
            val button = buttons[index]
            if (!shows(screen, button)) continue
            val spot = layout.at(button.spot) ?: continue
            InventoryButtonPainter.draw(g, button, spot.x, spot.y, hovered == index)
            val box = boxes.getOrNull(drawn++)
            if (box == null || box[0] != spot.x || box[1] != spot.y) moved = true
        }
        if (moved || drawn != boxes.size) {
            boxes = buttons.mapNotNull { button ->
                if (!shows(screen, button)) null else layout.at(button.spot)?.let { intArrayOf(it.x, it.y, SIZE, SIZE) }
            }
        }
        // Like a slot, no tooltip while an item is on the cursor: it would sit on top of the stack.
        if (hovered >= 0 && !tray.busy && Minecraft.getInstance().player?.containerMenu?.carried?.isEmpty != false) {
            g.setComponentTooltipForNextFrame(Minecraft.getInstance().font, InventoryButtonPainter.tooltip(buttons[hovered]), mouseX, mouseY)
        }
    }

    /** The hidden-buttons notice or tray, the free spots while a button is dragged out, and the dragged button. */
    private fun drawTray(screen: InventoryScreen, g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val buttons = activeButtons()
        val layout = layout(screen)
        val hidden = buttons.withIndex().filter { layout.isHidden(it.value.spot) }.map { HiddenButtonsTray.Entry(it.index, it.value) }
        val dragged = buttons.getOrNull(tray.dragged)
        if (dragged != null) {
            for (spot in layout.spots) {
                if (buttons.none { it.spot == spot.id }) InventoryButtonPainter.spot(g, spot.x, spot.y, DROP_FILL, DROP_EDGE)
            }
        }
        val f = SoulUi.factor()
        g.pose().pushMatrix()
        g.pose().scale(f, f)
        tray.draw(
            g, hidden, Minecraft.getInstance().window.guiScale, 0, (screen.width / f).toInt(), 0, (screen.height / f).toInt(),
            (mouseX / f).roundToInt(), (mouseY / f).roundToInt(),
        )
        g.pose().popMatrix()
        if (dragged != null && tray.dragged >= 0) {
            InventoryButtonPainter.draw(g, dragged, mouseX - SIZE / 2, mouseY - SIZE / 2, hovered = false, outline = DROP_EDGE)
        }
    }

    /** True when the click hit a button and must not reach the screen. */
    private fun click(screen: AbstractContainerScreen<*>, click: MouseButtonEvent): Boolean {
        if (screen is InventoryScreen && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            val f = SoulUi.factor()
            if (tray.click((click.x() / f).roundToInt(), (click.y() / f).roundToInt())) return true
        }
        if (pickTarget >= 0 && click.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            val stack = screen.hoveredSlot?.item
            if (stack != null && !stack.isEmpty) {
                pick(screen, stack)
                return true
            }
        }
        val buttons = activeButtons()
        val index = hit(screen, layout(screen), buttons, click.x(), click.y())
        if (index < 0) return false
        val mc = Minecraft.getInstance()
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            val command = buttons[index].command.trim().removePrefix("/").trimStart()
            val connection = mc.connection
            if (command.isNotEmpty() && connection != null && !DungeonCommandGuard.blocks(command)) {
                AbstractWidget.playButtonClickSound(mc.soundManager)
                connection.sendCommand(command)
            }
        } else if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            openEditor(screen, activeId, index)
        }
        return true
    }

    /** Copy [stack]'s look onto the waiting button, save, and go back to the editor. */
    private fun pick(screen: AbstractContainerScreen<*>, stack: ItemStack) {
        val target = pickTarget
        val profileId = pickProfile
        pickTarget = -1
        if (updateButton(profileId, target) { InventoryButtonIcons.capture(this, stack) }) openEditor(screen, profileId, target)
    }

    /** Rewrite button [index] of [profileId] in the saved config; false when it no longer exists. */
    private inline fun updateButton(profileId: String, index: Int, change: InventoryButton.() -> InventoryButton): Boolean {
        val config = cfg.inventoryButtons
        val buttons = buttonsOf(config, profileId) ?: return false
        if (index !in buttons.indices) return false
        val updated = buttons.toMutableList().also { it[index] = it[index].change() }
        val next = if (profileId == DEFAULT_ID) {
            config.copy(buttons = updated)
        } else {
            config.copy(profiles = config.profiles.map { if (it.id == profileId) it.copy(buttons = updated) else it })
        }
        ConfigManager.update { copy(inventoryButtons = next) }
        return true
    }

    /**
     * Close the container properly first: switching screens straight from a chest leaves the server
     * believing it is still open. Only the player inventory can be reopened client-side, so that is
     * the one the editor returns to.
     */
    private fun openEditor(screen: AbstractContainerScreen<*>, profileId: String, index: Int) {
        val player = Minecraft.getInstance().player
        val back = if (screen is InventoryScreen && player != null) InventoryScreen(player) else null
        player?.closeContainer()
        InventoryButtonsScreen.open(back, profileId, index)
    }

    /** Topmost button under the cursor, or -1. Later buttons draw over earlier ones. */
    private fun hit(screen: AbstractContainerScreen<*>, layout: InventoryButtonSpots.Layout, buttons: List<InventoryButton>, mx: Double, my: Double): Int {
        for (index in buttons.lastIndex downTo 0) {
            val button = buttons[index]
            if (!shows(screen, button)) continue
            val spot = layout.at(button.spot) ?: continue
            if (spot.contains(mx, my)) return index
        }
        return -1
    }

    private fun shows(screen: AbstractContainerScreen<*>, button: InventoryButton): Boolean =
        !button.inventoryOnly || screen is InventoryScreen

    private fun layout(screen: AbstractContainerScreen<*>): InventoryButtonSpots.Layout =
        InventoryButtonSpots.layout(
            screen.width, screen.height, screen.leftPos, screen.topPos, screen.imageWidth, screen.imageHeight,
            cfg.inventoryButtons.showOuterButtons,
            if (screen is InventoryScreen) EquipmentDisplay.occupiedRect(screen.width, screen.leftPos, screen.topPos, screen.imageWidth) else null,
        )
}
