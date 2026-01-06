package com.soulreturns.config.lib.ui.minigames

import kotlin.random.Random

/**
 * Tetris game model used by the config GUI's Minigames tab.
 *
 * This class is UI-agnostic and exposes simple grid-based state
 * that the screen can render however it likes.
 */
class TetrisGame(
    val columns: Int = 10,
    val rows: Int = 20,
    private val gravityMillisStart: Long = 800L,
) {
    enum class Tetromino { I, O, T, S, Z, J, L }

    data class Cell(val x: Int, val y: Int)

    private data class ActivePiece(
        val type: Tetromino,
        val rotation: Int,
        val x: Int,
        val y: Int,
    )

    private val random = Random.Default

    // Board: 0 = empty, >0 = filled with a tetromino id (1..7)
    private val board: Array<IntArray> = Array(rows) { IntArray(columns) }

    private var active: ActivePiece? = null
    private var next: Tetromino = randomPiece()

    var score: Int = 0
        private set

    var linesCleared: Int = 0
        private set

    val level: Int
        get() = 1 + linesCleared / 10

    var isGameOver: Boolean = false
        private set

    private var lastFallTime: Long = 0L

    /** Returns an immutable view of the board grid. */
    fun getBoardSnapshot(): Array<IntArray> = board.map { it.clone() }.toTypedArray()

    fun getActiveCells(): List<Cell> {
        val p = active ?: return emptyList()
        return shapeCells(p.type, p.rotation).map { (dx, dy) ->
            Cell(p.x + dx, p.y + dy)
        }
    }

    fun getNextPiece(): Tetromino = next

    /** Cells for the next piece, relative to an origin at (0, 0). */
    fun getNextPieceCells(): List<Cell> {
        return shapeCells(next, 0).map { (dx, dy) -> Cell(dx, dy) }
    }

    fun reset(nowMillis: Long = System.currentTimeMillis()) {
        for (r in 0 until rows) {
            board[r].fill(0)
        }
        score = 0
        linesCleared = 0
        isGameOver = false
        lastFallTime = nowMillis
        active = null
        next = randomPiece()
        spawnPiece()
    }

    /**
     * Advance gravity based on time; call regularly from the render loop.
     */
    fun update(nowMillis: Long) {
        if (isGameOver) return

        if (lastFallTime == 0L || active == null) {
            reset(nowMillis)
            return
        }

        val interval = currentGravityInterval()
        if (nowMillis - lastFallTime >= interval) {
            tickDown()
            lastFallTime = nowMillis
        }
    }

    fun moveLeft() = moveHorizontal(-1)
    fun moveRight() = moveHorizontal(1)

    fun softDrop() {
        if (isGameOver) return
        if (!tickDown()) return
        // Soft drop bonus
        score += 1
    }

    fun hardDrop() {
        if (isGameOver) return
        var cellsDropped = 0
        while (tickDown()) {
            cellsDropped++
        }
        if (cellsDropped > 0) {
            score += cellsDropped * 2
        }
    }

    fun rotateCW() = rotate(1)
    fun rotateCCW() = rotate(3) // 3 * 90° clockwise == 90° counterclockwise

    // ---------- Internal logic ----------

    private fun randomPiece(): Tetromino {
        val values = Tetromino.values()
        return values[random.nextInt(values.size)]
    }

    private fun spawnPiece() {
        val type = next
        next = randomPiece()
        val startX = columns / 2 - 2
        val startY = 0
        val piece = ActivePiece(type, rotation = 0, x = startX, y = startY)
        if (!fits(piece)) {
            isGameOver = true
            active = null
        } else {
            active = piece
        }
    }

    private fun currentGravityInterval(): Long {
        // Simple speed curve: every level reduces gravity by 10% down to a floor.
        val lvl = level.coerceAtMost(15)
        var factor = 1.0
        repeat(lvl - 1) {
            factor *= 0.9
        }
        val minInterval = 80L
        return (gravityMillisStart * factor).toLong().coerceAtLeast(minInterval)
    }

    private fun moveHorizontal(dx: Int) {
        if (isGameOver) return
        val p = active ?: return
        val candidate = p.copy(x = p.x + dx)
        if (fits(candidate)) {
            active = candidate
        }
    }

    /**
     * Returns true if the piece moved down, false if it locked in place.
     */
    private fun tickDown(): Boolean {
        val p = active ?: return false
        val candidate = p.copy(y = p.y + 1)
        return if (fits(candidate)) {
            active = candidate
            true
        } else {
            lockPiece(p)
            clearLines()
            spawnPiece()
            false
        }
    }

    private fun rotate(stepsCW: Int) {
        if (isGameOver) return
        val p = active ?: return
        val newRot = (p.rotation + stepsCW) and 3
        val candidate = p.copy(rotation = newRot)
        // Basic wall kicks: try original X, then small shifts left/right
        val kicks = listOf(0, -1, 1, -2, 2)
        for (dx in kicks) {
            val kicked = candidate.copy(x = candidate.x + dx)
            if (fits(kicked)) {
                active = kicked
                return
            }
        }
    }

    private fun fits(piece: ActivePiece): Boolean {
        for ((dx, dy) in shapeCells(piece.type, piece.rotation)) {
            val x = piece.x + dx
            val y = piece.y + dy
            if (x !in 0 until columns || y !in 0 until rows) return false
            if (board[y][x] != 0) return false
        }
        return true
    }

    private fun lockPiece(piece: ActivePiece) {
        val id = when (piece.type) {
            Tetromino.I -> 1
            Tetromino.O -> 2
            Tetromino.T -> 3
            Tetromino.S -> 4
            Tetromino.Z -> 5
            Tetromino.J -> 6
            Tetromino.L -> 7
        }
        for ((dx, dy) in shapeCells(piece.type, piece.rotation)) {
            val x = piece.x + dx
            val y = piece.y + dy
            if (y in 0 until rows && x in 0 until columns) {
                board[y][x] = id
            }
        }
        active = null
    }

    private fun clearLines() {
        var cleared = 0
        var r = rows - 1
        while (r >= 0) {
            if (board[r].all { it != 0 }) {
                // Shift everything above down
                for (y in r downTo 1) {
                    board[y] = board[y - 1].clone()
                }
                board[0].fill(0)
                cleared++
                // Stay on same row index to check new content
            } else {
                r--
            }
        }
        if (cleared > 0) {
            linesCleared += cleared
            // Basic Tetris scoring
            val base = when (cleared) {
                1 -> 100
                2 -> 300
                3 -> 500
                4 -> 800
                else -> cleared * 200
            }
            score += base * level
        }
    }

    // --- Tetromino shapes ---

    /**
     * Return relative (dx, dy) cells for the given piece and rotation.
     * Rotation is 0..3 clockwise.
     */
    private fun shapeCells(type: Tetromino, rotation: Int): List<Pair<Int, Int>> {
        val r = rotation and 3
        return when (type) {
            Tetromino.I -> when (r) {
                0 -> listOf(-1 to 0, 0 to 0, 1 to 0, 2 to 0)
                1 -> listOf(1 to -1, 1 to 0, 1 to 1, 1 to 2)
                2 -> listOf(-1 to 1, 0 to 1, 1 to 1, 2 to 1)
                else -> listOf(0 to -1, 0 to 0, 0 to 1, 0 to 2)
            }
            Tetromino.O -> listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
            Tetromino.T -> when (r) {
                0 -> listOf(-1 to 0, 0 to 0, 1 to 0, 0 to 1)
                1 -> listOf(0 to -1, 0 to 0, 0 to 1, 1 to 0)
                2 -> listOf(-1 to 0, 0 to 0, 1 to 0, 0 to -1)
                else -> listOf(0 to -1, 0 to 0, 0 to 1, -1 to 0)
            }
            Tetromino.S -> when (r) {
                0 -> listOf(0 to 0, 1 to 0, -1 to 1, 0 to 1)
                1 -> listOf(0 to -1, 0 to 0, 1 to 0, 1 to 1)
                2 -> listOf(0 to 0, 1 to 0, -1 to 1, 0 to 1)
                else -> listOf(0 to -1, 0 to 0, 1 to 0, 1 to 1)
            }
            Tetromino.Z -> when (r) {
                0 -> listOf(-1 to 0, 0 to 0, 0 to 1, 1 to 1)
                1 -> listOf(1 to -1, 0 to 0, 1 to 0, 0 to 1)
                2 -> listOf(-1 to 0, 0 to 0, 0 to 1, 1 to 1)
                else -> listOf(1 to -1, 0 to 0, 1 to 0, 0 to 1)
            }
            Tetromino.J -> when (r) {
                0 -> listOf(-1 to 0, 0 to 0, 1 to 0, -1 to 1)
                1 -> listOf(0 to -1, 0 to 0, 0 to 1, 1 to 1)
                2 -> listOf(-1 to 0, 0 to 0, 1 to 0, 1 to -1)
                else -> listOf(0 to -1, 0 to 0, 0 to 1, -1 to -1)
            }
            Tetromino.L -> when (r) {
                0 -> listOf(-1 to 0, 0 to 0, 1 to 0, 1 to 1)
                1 -> listOf(0 to -1, 0 to 0, 0 to 1, 1 to -1)
                2 -> listOf(-1 to 0, 0 to 0, 1 to 0, -1 to -1)
                else -> listOf(0 to -1, 0 to 0, 0 to 1, -1 to 1)
            }
        }
    }
}
