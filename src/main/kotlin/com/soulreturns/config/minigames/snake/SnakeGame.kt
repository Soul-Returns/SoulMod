package com.soulreturns.config.minigames.snake

import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import org.lwjgl.glfw.GLFW
import kotlin.random.Random

/**
 * Simple Snake game model and rendering used by the config GUI's Minigames tab.
 *
 * This class keeps the core grid/logic separate from the config screen itself
 * so the lib code doesn't need to know about minigame details.
 */
data class GridPos(val x: Int, val y: Int)

class SnakeGame(
    val columns: Int = 24,
    val rows: Int = 18,
    private val tickMillis: Long = 150L,
) {
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val random = Random.Default
    private val body: ArrayDeque<GridPos> = ArrayDeque()

    private var direction: Direction = Direction.RIGHT
    private var pendingDirection: Direction? = null
    private var lastUpdateTime: Long = 0L

    var food: GridPos = GridPos(0, 0)
        private set

    var isAlive: Boolean = true
        private set

    var score: Int = 0
        private set

    /** Public view of the snake segments from tail to head. */
    val segments: List<GridPos>
        get() = body.toList()

    /** Reset the game to a fresh state. */
    fun reset(nowMillis: Long = System.currentTimeMillis()) {
        body.clear()
        val startX = columns / 2
        val startY = rows / 2

        // Start with a short horizontal snake moving to the right.
        body.addLast(GridPos(startX - 1, startY))
        body.addLast(GridPos(startX, startY))

        direction = Direction.RIGHT
        pendingDirection = null
        score = 0
        isAlive = true
        lastUpdateTime = nowMillis
        spawnFood()
    }

    /**
     * Advance the game state at a fixed tick rate. Should be called
     * regularly from the render loop while the game is visible.
     */
    fun update(nowMillis: Long) {
        if (!isAlive) return

        // Lazily initialize if never updated before.
        if (lastUpdateTime == 0L || body.isEmpty()) {
            reset(nowMillis)
            return
        }

        if (nowMillis - lastUpdateTime < tickMillis) return
        lastUpdateTime = nowMillis

        // Apply any pending direction change that isn't a 180° turn.
        pendingDirection?.let { newDir ->
            if (!isOpposite(newDir, direction)) {
                direction = newDir
            }
            pendingDirection = null
        }

        val head = body.last()
        val next = when (direction) {
            Direction.UP -> GridPos(head.x, head.y - 1)
            Direction.DOWN -> GridPos(head.x, head.y + 1)
            Direction.LEFT -> GridPos(head.x - 1, head.y)
            Direction.RIGHT -> GridPos(head.x + 1, head.y)
        }

        // Wall collision ends the game.
        if (next.x !in 0 until columns || next.y !in 0 until rows) {
            isAlive = false
            return
        }

        // Self-collision ends the game.
        if (body.any { it.x == next.x && it.y == next.y }) {
            isAlive = false
            return
        }

        body.addLast(next)

        if (next.x == food.x && next.y == food.y) {
            score += 1
            spawnFood()
        } else {
            // Move forward: drop tail.
            body.removeFirst()
        }
    }

    /** Queue a direction change, applied on the next tick. */
    fun changeDirection(newDirection: Direction) {
        if (!isAlive) return
        pendingDirection = newDirection
    }

    private fun isOpposite(a: Direction, b: Direction): Boolean {
        return (a == Direction.UP && b == Direction.DOWN) ||
            (a == Direction.DOWN && b == Direction.UP) ||
            (a == Direction.LEFT && b == Direction.RIGHT) ||
            (a == Direction.RIGHT && b == Direction.LEFT)
    }

    private fun spawnFood() {
        // If the snake fills the board, just pin food to the head.
        if (body.size >= columns * rows) {
            food = body.last()
            return
        }

        var attempts = 0
        while (attempts < 1000) {
            val x = random.nextInt(columns)
            val y = random.nextInt(rows)
            val candidate = GridPos(x, y)
            if (body.none { it.x == candidate.x && it.y == candidate.y }) {
                food = candidate
                return
            }
            attempts++
        }

        // Fallback: place food on the head if random placement fails.
        food = body.last()
    }

    // ---------- Rendering & input ----------

    fun render(
        context: DrawContext,
        contentX: Int,
        contentY: Int,
        contentWidth: Int,
        contentHeight: Int,
        theme: Theme,
        contentPadding: Int,
        textRenderer: TextRenderer,
    ) {
        val now = System.currentTimeMillis()
        update(now)

        val cols = columns
        val rows = rows
        if (cols <= 0 || rows <= 0) return

        val cellSize = (minOf(contentWidth / cols, contentHeight / rows)).coerceAtLeast(4)
        if (cellSize <= 0) return

        val boardWidth = cols * cellSize
        val boardHeight = rows * cellSize
        val boardX = contentX + (contentWidth - boardWidth) / 2
        val boardY = contentY + (contentHeight - boardHeight) / 2

        // Board background card
        if (theme.useCardStyle) {
            RenderHelper.drawRect(context, boardX - 4, boardY - 4, boardWidth + 8, boardHeight + 8, theme.optionCardBackground)
        }

        // Draw grid contents
        val head = segments.lastOrNull()
        for (segment in segments) {
            val segX = boardX + segment.x * cellSize
            val segY = boardY + segment.y * cellSize
            val isHead = head != null && segment == head
            val color = if (isHead) theme.widgetActive else theme.widgetBackground
            RenderHelper.drawRect(context, segX, segY, cellSize - 1, cellSize - 1, color)
        }

        val foodPos = food
        val foodX = boardX + foodPos.x * cellSize
        val foodY = boardY + foodPos.y * cellSize
        val foodColor = 0xFFFF5555.toInt()
        RenderHelper.drawRect(context, foodX, foodY, cellSize - 1, cellSize - 1, foodColor)

        // HUD text (score and instructions)
        val hudX = contentX + contentPadding
        val hudY = contentY + contentPadding
        val scoreText = "Score: $score"
        val infoText = if (isAlive) {
            "Use WASD or arrow keys to move. Press R to restart."
        } else {
            "Game over! Press R to play again."
        }
        context.drawText(textRenderer, scoreText, hudX, hudY, theme.textPrimary, false)
        context.drawText(textRenderer, infoText, hudX, hudY + textRenderer.fontHeight + 4, theme.textSecondary, false)
    }

    fun handleKeyPressed(keyCode: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> changeDirection(Direction.UP)
            GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> changeDirection(Direction.DOWN)
            GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> changeDirection(Direction.LEFT)
            GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> changeDirection(Direction.RIGHT)
            GLFW.GLFW_KEY_R -> reset()
            else -> return false
        }
        return true
    }

    fun handleKeyReleased(@Suppress("UNUSED_PARAMETER") keyCode: Int): Boolean {
        // No key-up behaviour for Snake at the moment.
        return false
    }
}
