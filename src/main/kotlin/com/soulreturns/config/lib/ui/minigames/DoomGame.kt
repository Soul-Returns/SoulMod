package com.soulreturns.config.lib.ui.minigames

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal DOOM-style raycasting "engine" used by the config GUI Minigames tab.
 *
 * Now with a slightly larger map, simple enemies, and a very lightweight
 * "gun" mode where firing a shot along the center of the view will hit the
 * first enemy in that direction.
 */
class DoomGame {
    enum class Action {
        MOVE_FORWARD,
        MOVE_BACKWARD,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        TURN_LEFT,
        TURN_RIGHT,
        FIRE,
        RESET,
    }

    /** What a ray has hit. */
    private enum class HitKind { NONE, WALL, ENEMY }

    private data class RayHit(
        val kind: HitKind,
        val distance: Double,
        val side: Int,
        val wallType: Int,
        val enemyIndex: Int = -1,
    )

    private data class Enemy(
        var x: Double,
        var y: Double,
        var isAlive: Boolean = true,
        val color: Int,
    )

    /**
     * Column sample describing a single vertical slice of the viewport.
     *
     * [wallTopNorm] and [wallBottomNorm] are normalized (0..1) vertical
     * positions in the viewport; the renderer maps them to pixel Y
     * coordinates inside its content rect.
     */
    data class ColumnSample(
        var wallTopNorm: Float = 0.4f,
        var wallBottomNorm: Float = 0.6f,
        var wallColor: Int = 0xFF666666.toInt(),
        var ceilingColor: Int = 0xFF101010.toInt(),
        var floorColor: Int = 0xFF303030.toInt(),
    )

    // --- Map, enemies and player state ----------------------------------------

    // Simple hardcoded map: 0 = empty, >0 = wall type.
    // Slightly larger now so you can move around a bit more.
    private val map: Array<IntArray> = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 2, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 4, 4, 0, 0, 0, 0, 0, 4, 4, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 4, 4, 0, 0, 0, 0, 0, 4, 4, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 5, 0, 0, 0, 6, 0, 0, 0, 5, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 2, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 4, 4, 0, 0, 0, 0, 0, 4, 4, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 4, 4, 0, 0, 0, 0, 0, 4, 4, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
    )

    private val mapWidth = map[0].size
    private val mapHeight = map.size

    private val enemies = mutableListOf<Enemy>()

    // Player position is stored in map-space coordinates; we start in an open
    // cell near the center of the map so movement is immediately possible.
    private var posX = 8.5
    private var posY = 8.5
    private var angle = 0.0 // radians

    // Movement tuning. Values are intentionally gentle because this runs in a GUI.
    private val moveSpeedPerSecond = 3.0
    private val strafeSpeedPerSecond = 2.5
    private val turnSpeedPerSecond = Math.toRadians(90.0)

    private var lastUpdateTime: Long = 0L

    // Simple "flash" timer for FIRE action.
    private var fireFlashUntil: Long = 0L

    // Reusable column buffer to avoid per-frame allocations.
    private var columnBuffer: Array<ColumnSample> = emptyArray()

    init {
        reset(System.currentTimeMillis())
    }

    fun reset(nowMillis: Long = System.currentTimeMillis()) {
        posX = 8.5
        posY = 8.5
        angle = 0.0
        lastUpdateTime = nowMillis
        fireFlashUntil = 0L
        initEnemies()
    }

    private fun initEnemies() {
        enemies.clear()
        // A few colorful, stationary enemies placed close to the player so
        // they are easy to spot when you first open DOOM.
        // Straight ahead in the same corridor.
        enemies += Enemy(11.5, 8.5, isAlive = true, color = 0xFFFF5555.toInt()) // red
        // Slightly above and below the center line.
        enemies += Enemy(11.5, 6.5, isAlive = true, color = 0xFF55FF55.toInt()) // green
        enemies += Enemy(11.5, 10.5, isAlive = true, color = 0xFF5599FF.toInt()) // blue
        // Behind the player so turning around reveals another.
        enemies += Enemy(5.5, 8.5, isAlive = true, color = 0xFFFFAA00.toInt()) // orange
    }

    /** Apply a discrete input event. */
    fun applyAction(action: Action, nowMillis: Long = System.currentTimeMillis()) {
        if (action == Action.RESET) {
            reset(nowMillis)
            return
        }

        // FIRE triggers a brief flash and performs a hitscan along the
        // center of the screen to shoot the first enemy in that direction.
        if (action == Action.FIRE) {
            shoot(nowMillis)
            return
        }

        // Movement is scaled by time since lastUpdateTime, but we clamp the
        // delta so that a long pause does not launch the player across the map.
        val dt = computeDeltaSeconds(nowMillis)

        when (action) {
            Action.MOVE_FORWARD -> move(forward = 1.0, strafe = 0.0, dt = dt)
            Action.MOVE_BACKWARD -> move(forward = -1.0, strafe = 0.0, dt = dt)
            Action.STRAFE_LEFT -> move(forward = 0.0, strafe = -1.0, dt = dt)
            Action.STRAFE_RIGHT -> move(forward = 0.0, strafe = 1.0, dt = dt)
            Action.TURN_LEFT -> turn(-1.0, dt)
            Action.TURN_RIGHT -> turn(1.0, dt)
            Action.FIRE, Action.RESET -> Unit // handled above
        }
    }

    /**
     * Update time-based state; currently just keeps lastUpdateTime in sync so
     * that the next movement action has a sensible delta.
     */
    fun update(nowMillis: Long) {
        if (lastUpdateTime == 0L) {
            lastUpdateTime = nowMillis
        }
    }

    /**
     * Variant of [update] that also applies continuous movement/turning input
     * based on the current key state. This lets you hold W to move forward
     * while turning left/right at the same time.
     */
    fun updateWithInput(
        nowMillis: Long,
        moveForward: Boolean,
        moveBackward: Boolean,
        strafeLeft: Boolean,
        strafeRight: Boolean,
        turnLeft: Boolean,
        turnRight: Boolean,
    ) {
        val dt = computeDeltaSeconds(nowMillis)
        if (dt <= 0.0) return

        val forward = (if (moveForward) 1.0 else 0.0) + (if (moveBackward) -1.0 else 0.0)
        val strafe = (if (strafeRight) 1.0 else 0.0) + (if (strafeLeft) -1.0 else 0.0)
        val turn = (if (turnRight) 1.0 else 0.0) + (if (turnLeft) -1.0 else 0.0)

        if (forward != 0.0 || strafe != 0.0) {
            move(forward, strafe, dt)
        }
        if (turn != 0.0) {
            turn(turn, dt)
        }
    }

    /**
     * Whether the muzzle flash from the last shot should still be visible.
     */
    fun isMuzzleFlashActive(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis < fireFlashUntil
    }

    /** Remaining (alive) enemies. */
    fun getRemainingEnemies(): Int = enemies.count { it.isAlive }

    /**
     * Compute [columnCount] column samples representing the current scene.
     */
    fun computeColumns(columnCount: Int, nowMillis: Long = System.currentTimeMillis()): Array<ColumnSample> {
        if (columnCount <= 0) return emptyArray()

        // Ensure buffer capacity.
        if (columnBuffer.size != columnCount) {
            columnBuffer = Array(columnCount) { ColumnSample() }
        }

        // Basic camera vectors.
        val dirX = cos(angle)
        val dirY = sin(angle)

        // FOV ~70 degrees.
        val fov = Math.toRadians(70.0)
        val planeLength = kotlin.math.tan(fov / 2.0)
        val planeX = -dirY * planeLength
        val planeY = dirX * planeLength

        val now = nowMillis
        val flashActive = now < fireFlashUntil

        for (x in 0 until columnCount) {
            val cameraX = if (columnCount == 1) 0.0 else 2.0 * x / (columnCount - 1) - 1.0
            val rayDirX = dirX + planeX * cameraX
            val rayDirY = dirY + planeY * cameraX

            val hit = traceRay(rayDirX, rayDirY)
            val sample = columnBuffer[x]

            if (hit.kind == HitKind.NONE) {
                // No wall/enemy: just fill with sky and floor gradient.
                sample.wallTopNorm = 0.5f
                sample.wallBottomNorm = 0.5f
                sample.wallColor = 0xFF000000.toInt()
                sample.ceilingColor = 0xFF101010.toInt()
                sample.floorColor = 0xFF303030.toInt()
                continue
            }

            val perpDist = hit.distance.coerceAtLeast(0.0001)

            // Projected wall/enemy height in normalized units: closer hits are taller.
            val inv = 1.0 / perpDist
            val wallHeightNorm = (inv * 1.2).coerceIn(0.1, 1.5)
            val center = 0.5
            var top = (center - wallHeightNorm / 2.0).toFloat()
            var bottom = (center + wallHeightNorm / 2.0).toFloat()
            if (top < 0f) top = 0f
            if (bottom > 1f) bottom = 1f

            val baseColor = when (hit.kind) {
                HitKind.WALL -> when (hit.wallType) {
                    1 -> 0xFF606060.toInt()
                    2 -> 0xFF8B3A3A.toInt() // reddish
                    3 -> 0xFF3A8B8B.toInt() // teal
                    4 -> 0xFF707020.toInt() // yellow-ish
                    5 -> 0xFF5A2A8B.toInt() // purple
                    6 -> 0xFF8B7A3A.toInt() // brown
                    else -> 0xFF606060.toInt()
                }
                HitKind.ENEMY -> {
                    val idx = hit.enemyIndex
                    if (idx in enemies.indices) enemies[idx].color else 0xFFFF0000.toInt()
                }
                HitKind.NONE -> 0xFF606060.toInt()
            }

            // Distance-based shading.
            val shade = (1.0 / (1.0 + perpDist * 0.4)).coerceIn(0.2, 1.0)
            val sideFactor = if (hit.side == 1) 0.8 else 1.0
            val shadedColor = shadeColor(baseColor, shade * sideFactor)

            val ceilingColor = 0xFF101010.toInt()
            // Floor slightly lighter than ceiling.
            val floorColor = 0xFF303030.toInt()

            sample.wallTopNorm = top
            sample.wallBottomNorm = bottom
            sample.wallColor = if (flashActive && hit.kind == HitKind.WALL) {
                // Emphasise muzzle flash on walls but leave enemies readable.
                brightenColor(shadedColor, 1.4)
            } else {
                shadedColor
            }
            sample.ceilingColor = ceilingColor
            sample.floorColor = floorColor
        }

        return columnBuffer
    }

    // --- Internal helpers -----------------------------------------------------

    private fun shoot(nowMillis: Long) {
        fireFlashUntil = nowMillis + 120L

        // Center-of-screen hitscan: cameraX = 0 so the ray direction is just
        // the current forward vector.
        val dirX = cos(angle)
        val dirY = sin(angle)
        val hit = traceRay(dirX, dirY)

        if (hit.kind == HitKind.ENEMY && hit.enemyIndex in enemies.indices) {
            enemies[hit.enemyIndex].isAlive = false
        }
    }

    private fun traceRay(rayDirX: Double, rayDirY: Double): RayHit {
        // DDA setup.
        var mapX = posX.toInt()
        var mapY = posY.toInt()

        val deltaDistX = if (rayDirX == 0.0) Double.POSITIVE_INFINITY else sqrt(1.0 + (rayDirY * rayDirY) / (rayDirX * rayDirX))
        val deltaDistY = if (rayDirY == 0.0) Double.POSITIVE_INFINITY else sqrt(1.0 + (rayDirX * rayDirX) / (rayDirY * rayDirY))

        var stepX: Int
        var stepY: Int
        var sideDistX: Double
        var sideDistY: Double

        if (rayDirX < 0) {
            stepX = -1
            sideDistX = (posX - mapX) * deltaDistX
        } else {
            stepX = 1
            sideDistX = (mapX + 1.0 - posX) * deltaDistX
        }

        if (rayDirY < 0) {
            stepY = -1
            sideDistY = (posY - mapY) * deltaDistY
        } else {
            stepY = 1
            sideDistY = (mapY + 1.0 - posY) * deltaDistY
        }

        var hitKind = HitKind.NONE
        var wallType = 0
        var enemyIndex = -1
        var side = 0 // 0 = X, 1 = Y

        var steps = 0
        val maxSteps = 96
        while (hitKind == HitKind.NONE && steps < maxSteps) {
            if (sideDistX < sideDistY) {
                sideDistX += deltaDistX
                mapX += stepX
                side = 0
            } else {
                sideDistY += deltaDistY
                mapY += stepY
                side = 1
            }

            if (mapX < 0 || mapX >= mapWidth || mapY < 0 || mapY >= mapHeight) {
                break
            }

            val enemyIdx = enemyAtCell(mapX, mapY)
            if (enemyIdx != -1) {
                hitKind = HitKind.ENEMY
                enemyIndex = enemyIdx
                break
            }

            val cell = map[mapY][mapX]
            if (cell != 0) {
                hitKind = HitKind.WALL
                wallType = cell
                break
            }

            steps++
        }

        if (hitKind == HitKind.NONE) {
            return RayHit(HitKind.NONE, distance = Double.POSITIVE_INFINITY, side = 0, wallType = 0, enemyIndex = -1)
        }

        val perpWallDist = when (side) {
            0 -> (sideDistX - deltaDistX)
            else -> (sideDistY - deltaDistY)
        }

        return RayHit(hitKind, perpWallDist, side, wallType, enemyIndex)
    }

    private fun enemyAtCell(cellX: Int, cellY: Int): Int {
        for (i in enemies.indices) {
            val e = enemies[i]
            if (!e.isAlive) continue
            if (e.x.toInt() == cellX && e.y.toInt() == cellY) {
                return i
            }
        }
        return -1
    }

    private fun computeDeltaSeconds(nowMillis: Long): Double {
        if (lastUpdateTime == 0L) {
            lastUpdateTime = nowMillis
            return 0.0
        }
        val raw = (nowMillis - lastUpdateTime).coerceAtMost(200L)
        lastUpdateTime = nowMillis
        return raw / 1000.0
    }

    private fun move(forward: Double, strafe: Double, dt: Double) {
        if (dt <= 0.0) return

        val forwardSpeed = moveSpeedPerSecond * forward * dt
        val strafeSpeed = strafeSpeedPerSecond * strafe * dt

        val dirX = cos(angle)
        val dirY = sin(angle)

        // Forward/backward.
        var dx = dirX * forwardSpeed
        var dy = dirY * forwardSpeed

        // Strafing: perpendicular to direction.
        dx += -dirY * strafeSpeed
        dy += dirX * strafeSpeed

        val newX = posX + dx
        val newY = posY + dy

        // Simple collision: only move if both X and Y targets are empty.
        if (isWalkable(newX, posY)) posX = newX
        if (isWalkable(posX, newY)) posY = newY
    }

    private fun turn(sign: Double, dt: Double) {
        if (dt <= 0.0) return
        val delta = turnSpeedPerSecond * sign * dt
        angle += delta
        // Keep angle in a sane range.
        if (angle > Math.PI) angle -= 2.0 * Math.PI
        if (angle < -Math.PI) angle += 2.0 * Math.PI
    }

    private fun isWalkable(x: Double, y: Double): Boolean {
        val ix = x.toInt()
        val iy = y.toInt()
        if (ix < 0 || ix >= mapWidth || iy < 0 || iy >= mapHeight) return false
        return map[iy][ix] == 0
    }

    private fun shadeColor(color: Int, factor: Double): Int {
        val a = color ushr 24 and 0xFF
        val r = color ushr 16 and 0xFF
        val g = color ushr 8 and 0xFF
        val b = color and 0xFF

        val f = factor.coerceIn(0.0, 1.5)
        val nr = (r * f).toInt().coerceIn(0, 255)
        val ng = (g * f).toInt().coerceIn(0, 255)
        val nb = (b * f).toInt().coerceIn(0, 255)

        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    private fun brightenColor(color: Int, factor: Double): Int {
        val a = color ushr 24 and 0xFF
        val r = color ushr 16 and 0xFF
        val g = color ushr 8 and 0xFF
        val b = color and 0xFF

        val f = factor.coerceAtLeast(1.0)
        val nr = (r * f).toInt().coerceIn(0, 255)
        val ng = (g * f).toInt().coerceIn(0, 255)
        val nb = (b * f).toInt().coerceIn(0, 255)

        return (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}
