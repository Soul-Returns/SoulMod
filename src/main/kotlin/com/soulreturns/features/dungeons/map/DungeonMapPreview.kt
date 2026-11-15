package com.soulreturns.features.dungeons.map

import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonPlayer

/**
 * A synthetic [DungeonMapHud.MapScene] for the config live-preview: a full F7 (6×6) layout based on
 * a real run, tuned to exercise **every** state the renderer can show. It's a single-path tree (no
 * impossible loops): the yellow miniboss and the Entrance each have exactly one door; the route to
 * Blood runs Entrance→Andesite→Lots Of Floors→Fairy→Rails→Pirate→Melon→Blood, and the rooms feeding
 * Blood (Andesite, Rails, Pirate, Melon) are cleared so their wither/blood doors read as open.
 * Pirate + Melon are the two L-shapes.
 *
 * Clear-state semantics: **green** = cleared with ALL secrets (`n/n`); **white** = cleared but not
 * all secrets (`m/n`, m<n); **no check** = in progress (gray `m/n`). Failed (red) only applies to
 * puzzles — a normal room is never red. The lone solo player is "self"; two example teammates are
 * added so head/border/name options are previewable.
 *
 * Rooms are addressed in `(col, row)` on the 6×6 grid (row 0 = top); a cell maps to component
 * `(col*2, row*2)`. Multi-cell rooms list all their cells (an L is three cells).
 */
object DungeonMapPreview {
    /** Build the preview scene. [selfName] marks which marker is "you" (cyan arrow / own-head). */
    fun build(selfName: String?): DungeonMapHud.MapScene {
        val self = selfName ?: "You"
        val rooms = arrayOfNulls<DungeonRoom>(36)
        val doors = arrayOfNulls<DungeonDoor>(60)

        fun cell(col: Int, row: Int) = ComponentPos(col * 2, row * 2)

        fun room(cells: List<Pair<Int, Int>>, block: DungeonRoom.() -> Unit): DungeonRoom {
            val comps = cells.map { cell(it.first, it.second) }
            val r = DungeonRoom(comps[0]).apply { explored = true }
            for (i in 1 until comps.size) r.addComponent(comps[i])
            r.apply(block)
            for (c in comps) rooms[c.roomIndex()] = r
            return r
        }

        fun single(col: Int, row: Int, block: DungeonRoom.() -> Unit) = room(listOf(col to row), block)

        // --- rooms (names/positions from the reference run; states cover every case) ---
        room(listOf(0 to 0, 1 to 0)) { type = RoomType.NORMAL; roomName = "Gold"; secretsTotal = 1; secretsFound = 0; checkmark = Checkmark.WHITE } // cleared (not all secrets)
        room(listOf(2 to 0, 3 to 0)) { type = RoomType.NORMAL; roomName = "Bridges"; secretsTotal = 6; secretsFound = 3 } // in progress
        single(5, 0) { type = RoomType.BLOOD; roomName = "Blood" }
        single(0, 1) { type = RoomType.MINIBOSS; roomName = "Shadow Assassin"; checkmark = Checkmark.GREEN } // miniboss cleared
        single(1, 1) { type = RoomType.NORMAL; roomName = "Cell"; secretsTotal = 1; secretsFound = 1; checkmark = Checkmark.GREEN } // cleared, all secrets
        // Melon — L-shape (top-left cell (4,1) missing), under Blood. Cleared (not all secrets) → white.
        room(listOf(5 to 1, 4 to 2, 5 to 2)) { type = RoomType.NORMAL; roomName = "Melon"; secretsTotal = 7; secretsFound = 5; checkmark = Checkmark.WHITE }
        // Buttons — 2x2. Cleared with all secrets → green.
        room(listOf(0 to 2, 1 to 2, 0 to 3, 1 to 3)) { type = RoomType.NORMAL; roomName = "Buttons"; secretsTotal = 5; secretsFound = 5; checkmark = Checkmark.GREEN }
        // Pirate — L-shape (arm up at (2,1)). Cleared (not all secrets) → white.
        room(listOf(2 to 1, 2 to 2, 3 to 2)) { type = RoomType.NORMAL; roomName = "Pirate"; secretsTotal = 6; secretsFound = 3; checkmark = Checkmark.WHITE }
        // Rails — 2x2. Cleared (not all secrets) → white.
        room(listOf(2 to 3, 3 to 3, 2 to 4, 3 to 4)) { type = RoomType.NORMAL; roomName = "Rails"; secretsTotal = 9; secretsFound = 4; checkmark = Checkmark.WHITE }
        single(4, 3) { type = RoomType.NORMAL; roomName = "Red Green"; secretsTotal = 3; secretsFound = 0 } // not started
        single(5, 3) { type = RoomType.PUZZLE; roomName = "Teleport Maze" } // puzzle unsolved
        single(0, 4) { type = RoomType.NORMAL; roomName = "Beams"; secretsTotal = 2; secretsFound = 2; checkmark = Checkmark.GREEN } // cleared, all secrets
        single(1, 4) { type = RoomType.NORMAL; roomName = "Duncan"; secretsTotal = 1; secretsFound = 0 } // not started
        single(4, 4) { type = RoomType.FAIRY; roomName = "Fairy" }
        single(0, 5) { type = RoomType.PUZZLE; roomName = "Creeper Beams"; checkmark = Checkmark.FAILED } // puzzle failed (red)
        single(1, 5) { type = RoomType.TRAP; roomName = "New Trap"; secretsTotal = 3; secretsFound = 2 } // in progress (trap)
        single(2, 5) { type = RoomType.ENTRANCE; roomName = "Entrance" }
        single(3, 5) { type = RoomType.NORMAL; roomName = "Andesite"; secretsTotal = 2; secretsFound = 2; checkmark = Checkmark.GREEN } // cleared (feeds Blood)
        single(4, 5) { type = RoomType.NORMAL; roomName = "Lots Of Floors"; secretsTotal = 3; secretsFound = 1; checkmark = Checkmark.WHITE } // cleared (on the way to Blood)
        single(5, 5) { type = RoomType.PUZZLE; roomName = "Tic Tac Toe"; checkmark = Checkmark.GREEN } // puzzle solved (green)
        // Added: puzzle in Pirate's L-corner, 1x1 in Melon's L-corner, puzzle right of Bridges, rare right of Fairy.
        single(3, 1) { type = RoomType.PUZZLE; roomName = "Boulder" } // puzzle in Pirate's corner (unsolved)
        single(4, 1) { type = RoomType.NORMAL; roomName = "Teeth"; secretsTotal = 2; secretsFound = 0 } // in Melon's L-corner
        single(4, 0) { type = RoomType.PUZZLE; roomName = "Quiz"; checkmark = Checkmark.GREEN } // right of Bridges (solved)
        single(5, 4) { type = RoomType.RARE; roomName = "Rare"; secretsTotal = 2; secretsFound = 1 } // rare room (gold), right of Fairy

        // --- doors: a single-path tree (each comp cell is unique → no index collisions) ---
        fun hDoor(col: Int, row: Int, t: DoorType, opened: Boolean = true) {
            val comp = ComponentPos(col * 2 + 1, row * 2) // between (col,row) and (col+1,row)
            doors[comp.doorIndex()] = DungeonDoor(comp).apply { type = t; this.opened = opened }
        }
        fun vDoor(col: Int, row: Int, t: DoorType, opened: Boolean = true) {
            val comp = ComponentPos(col * 2, row * 2 + 1) // between (col,row) and (col,row+1)
            doors[comp.doorIndex()] = DungeonDoor(comp).apply { type = t; this.opened = opened }
        }

        // Top cluster
        hDoor(1, 0, DoorType.NORMAL) // Gold → Bridges
        vDoor(1, 0, DoorType.NORMAL) // Gold → Cell
        hDoor(0, 1, DoorType.NORMAL) // Cell → Shadow Assassin   (yellow's ONLY door)
        vDoor(1, 1, DoorType.NORMAL) // Cell → Buttons
        // Left / bottom-left
        vDoor(0, 3, DoorType.NORMAL) // Buttons → Beams
        vDoor(0, 4, DoorType.NORMAL) // Beams → Creeper Beams
        hDoor(0, 4, DoorType.NORMAL) // Beams → Duncan
        vDoor(1, 4, DoorType.NORMAL) // Duncan → New Trap
        hDoor(1, 3, DoorType.NORMAL) // Buttons → Rails
        // Route to Blood (wither doors → blood door); the rooms feeding these are cleared above.
        vDoor(2, 2, DoorType.WITHER) // Rails → Pirate
        hDoor(3, 2, DoorType.WITHER) // Pirate → Melon
        vDoor(5, 0, DoorType.BLOOD) //  Melon → Blood
        // Right branch off Melon
        vDoor(4, 2, DoorType.NORMAL) // Melon → Red Green
        hDoor(4, 3, DoorType.NORMAL) // Red Green → Teleport Maze
        // Bottom-right chain: Entrance → Andesite → Lots Of Floors → Fairy → Rails (Fairy is a pass-through)
        hDoor(2, 5, DoorType.NORMAL) // Entrance → Andesite  (Entrance's ONLY door)
        hDoor(3, 5, DoorType.NORMAL) // Andesite → Lots Of Floors
        hDoor(4, 5, DoorType.NORMAL) // Lots Of Floors → Tic Tac Toe
        vDoor(4, 4, DoorType.NORMAL) // Lots Of Floors → Fairy   (Fairy entrance)
        hDoor(3, 4, DoorType.NORMAL) // Rails → Fairy            (Fairy exit)
        // Added rooms' connections
        vDoor(3, 1, DoorType.NORMAL) // Boulder (Pirate corner) → Pirate
        vDoor(4, 1, DoorType.NORMAL) // Teeth (Melon corner) → Melon
        hDoor(3, 0, DoorType.NORMAL) // Bridges → Quiz
        hDoor(4, 4, DoorType.NORMAL) // Fairy → Rare

        // Markers: solo self (cyan, at Cell) + two example teammates for head/border/name preview.
        fun marker(name: String, x: Double, z: Double, rot: Double, dc: DungeonClass): DungeonPlayer =
            DungeonPlayer(name).apply {
                dungeonClass = dc
                pushPosition(MapPlayerPos(x, z, rot))
            }

        val players = listOf(
            marker(self, 2.9, 2.9, 2.4, DungeonClass.MAGE), // Cell
            marker("Teammate1", 4.9, 8.9, 0.4, DungeonClass.ARCHER), // Rails
            marker("Teammate2", 8.9, 4.9, -1.4, DungeonClass.HEALER), // Melon
        )

        return DungeonMapHud.MapScene(rooms, doors, players, DungeonFloor.F7, self, preview = true)
    }
}
