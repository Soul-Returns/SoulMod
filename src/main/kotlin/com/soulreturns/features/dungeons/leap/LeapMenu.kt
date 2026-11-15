package com.soulreturns.features.dungeons.leap

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.features.dungeons.map.DungeonRoom
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.component.ResolvableProfile
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Soul's Spirit Leap menu (card Dungeons › Runs & party › Leap menu, spec `mocks/leap-menu/SPEC.md`). A chest menu
 * that [LeapMenuReader] recognizes gets a [LeapSession] and [LeapScreen] replaces the vanilla screen while the real
 * menu stays open underneath. Also keeps [locations] (where each teammate is) up to date all run long.
 *
 * Anti-cheat: nothing here ever clicks on its own. One physical press sends at most one container click, at once,
 * on the head of the teammate the player chose ([LeapSession.leap]).
 */
object LeapMenu {
    private var registered = false

    /** The open leap menu, if any. */
    var session: LeapSession? = null
        private set

    val locations = TeammateLocations()

    private val ghostRegex = Regex("☠ (\\w{1,16}|You) .*became a ghost")
    private val reviveRegex = Regex("❣ (\\w{1,16}) was revived")

    /** A map marker older than this is not "seen now". */
    private const val MAP_FRESH_MS = 2000L

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ -> onInit(screen) })
        Events.subscribe<ChatMessage> { onChat(it) }
        ClientTickEvents.END_CLIENT_TICK.register { onTick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> locations.clear() }
    }

    private fun onInit(screen: Screen) {
        if (screen !is ContainerScreen) return
        val existing = session
        // Re-init of a known menu (resize, or our screen handing it back): never take it over twice.
        if (existing != null && existing.menu === screen.menu) return
        session = null
        if (!cfg.dungeons.leap.enabled) return
        if (!LeapMenuReader.isLeapMenu(MessageDetector.stripColorCodes(screen.title.string), screen.menu.rowCount * 9)) return
        val s = LeapSession(screen.menu)
        session = s
        s.poll()
        // Not inside init: the vanilla screen finishes opening first, then ours replaces it in the same task run, before
        // the next frame (the timing log counts vanilla frames: 0 on the test server).
        val mc = Minecraft.getInstance()
        ScreenEvents.beforeExtract(screen).register(ScreenEvents.BeforeExtract { _, _, _, _, _ -> if (session === s) s.vanillaFrames++ })
        mc.execute {
            if (mc.gui.screen() === screen && s.live) {
                s.screenAt = System.currentTimeMillis()
                mc.gui.setScreen(LeapScreen(screen, s))
            }
        }
    }

    // ---------------------------------------------------------------- where teammates are

    private fun selfName(): String? = Minecraft.getInstance().player?.gameProfile?.name

    private fun onTick() {
        if ((!cfg.dungeons.leap.enabled && !cfg.dungeons.roles.enabled) || !LocationApi.inDungeon) return
        val now = System.currentTimeMillis()
        val me = selfName()
        for (p in DungeonRunState.players) {
            if (p == null || p.name == me) continue
            val seen = seen(p.name)
            locations.locate(p.name, seen?.label, now, seen?.remember ?: false)
        }
    }

    private fun onChat(msg: ChatMessage) {
        if (!cfg.dungeons.leap.enabled && !cfg.dungeons.roles.enabled) return
        val stripped = MessageDetector.stripColorCodes(msg.raw).trim()
        val now = System.currentTimeMillis()
        PartyCallouts.partyLine(stripped)?.let { (who, body) ->
            if (!who.equals(selfName(), ignoreCase = true)) locations.onPartyLine(who, body, now) { where(it, now) }
            return
        }
        if (PartyCallouts.isPhaseLine(stripped)) {
            locations.onPhaseLine()
            return
        }
        (ghostRegex.find(stripped) ?: reviveRegex.find(stripped))?.let { locations.forget(it.groupValues[1]) }
    }

    /** Where [name] is right now as a label, or null when unknown (for a leap line's target). */
    private fun where(name: String, now: Long): String? {
        val seen = seen(name)
        val loc = locations.locate(name, seen?.label, now, seen?.remember ?: false)
        return if (loc.source == TeammateLocations.Source.NONE) null else loc.label
    }

    /** A tracked teammate's place: [remember] = a named place worth keeping as "last seen" (a bare distance is not). */
    class Seen(val label: String, val remember: Boolean, val room: Boolean = false)

    /**
     * Where a teammate is while the client sees them: the tracked player in the F7/M7 boss room → its named spot;
     * while clearing → the map room under the player or their map marker (≤ [MAP_FRESH_MS] old); else the distance.
     */
    fun seen(name: String): Seen? {
        val mc = Minecraft.getInstance()
        val me = mc.player ?: return null
        val e = mc.level?.players()?.firstOrNull { !it.isRemoved && it.gameProfile.name.equals(name, ignoreCase = true) }
        val f = DungeonRunState.floor
        val f7 = f == DungeonFloor.F7 || f == DungeonFloor.M7
        if (e != null) {
            if (f7 && e.x >= -10 && e.z >= -10) PartyCallouts.spotAt(e.x, e.y, e.z)?.let { return Seen(it, true) }
            DungeonGrid.roomAtWorld(e.x, e.z)?.let { return Seen(roomLabel(it), true, room = true) }
            val dx = e.x - me.x
            val dz = e.z - me.z
            return Seen("${sqrt(dx * dx + dz * dz + (e.y - me.y) * (e.y - me.y)).roundToInt()}m", false)
        }
        val p = DungeonRunState.players.firstOrNull { it?.name.equals(name, ignoreCase = true) } ?: return null
        val w = p.mapWorld ?: return null
        if (System.currentTimeMillis() - p.mapAt > MAP_FRESH_MS) return null
        return DungeonGrid.roomAtWorld(w[0], w[1])?.let { Seen(roomLabel(it), true, room = true) }
    }

    private fun roomLabel(room: DungeonRoom): String = room.roomName ?: when (room.type.name) {
        "BLOOD" -> "Blood"
        "PUZZLE" -> "Puzzle"
        "TRAP" -> "Trap"
        "FAIRY" -> "Fairy"
        "ENTRANCE" -> "Entrance"
        "MINIBOSS" -> "Miniboss"
        else -> "Room"
    }

    // ---------------------------------------------------------------- the four places

    /**
     * One teammate as a tile shows them. [slot] = their head's slot, null while it has not arrived (the tile is drawn
     * from the party roster but not clickable yet). [skin]: their tab-list skin (already loaded), else the head's.
     */
    class Tile(
        val name: String,
        val cls: DungeonClass,
        val slot: Int?,
        val dead: Boolean,
        val tabIndex: Int,
        val skin: PlayerSkin?,
        val profile: ResolvableProfile?,
        val location: TeammateLocations.Location,
        val mapRoom: Boolean,
    ) {
        val ready: Boolean get() = slot != null
    }

    /**
     * The four places (0 TL, 1 TR, 2 BL, 3 BR) in the configured order, null = empty. Until every slot is there the
     * party roster fills in the teammates whose heads have not arrived, so the tiles stand in their corners from the
     * first frame; once the menu is complete only its heads count.
     */
    fun places(s: LeapSession, now: Long = System.currentTimeMillis()): List<Tile?> {
        val roster = DungeonRunState.players
        val self = selfName()
        val names = LinkedHashSet<String>()
        s.heads.forEach { names.add(it.name) }
        if (s.state != LeapGate.ACTIVE && s.state != LeapGate.SENT) {
            for (p in roster) if (p != null && !p.name.equals(self, ignoreCase = true) && names.none { it.equals(p.name, ignoreCase = true) }) names.add(p.name)
        }
        val conn = Minecraft.getInstance().connection
        val mates = names.map { name ->
            val h = s.gate.head(name)
            val idx = roster.indexOfFirst { it?.name.equals(name, ignoreCase = true) }
            val p = if (idx >= 0) roster[idx] else null
            val seen = seen(name)
            Tile(
                h?.name ?: name, p?.dungeonClass ?: DungeonClass.UNKNOWN, h?.slot, h?.dead == true || p?.isDead == true, if (idx >= 0) idx else 99,
                conn?.getPlayerInfoIgnoreCase(name)?.skin, h?.let { s.profiles[it.slot] }, locations.locate(name, seen?.label, now, seen?.remember ?: false), seen?.room == true,
            )
        }.sortedBy { it.tabIndex }
        return LeapSort.sort(mates, cfg.dungeons.leap.sorting, { it.cls }, { it.name }, { it.slot ?: 99 })
    }
}
