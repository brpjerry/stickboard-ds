package dev.stickboard

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Console mode: each stick picks one of eight directions, and the selection on that half moves one
 * key per step, like a D-pad on a console keyboard. Pushing the stick steps once straight away;
 * holding it steps again after [delayMs] and then every [intervalMs].
 */
class ConsoleNavigator {
    /** Current direction per side: 0 = right, then clockwise in 45° steps (y points down); -1 = none. */
    private val sector = intArrayOf(-1, -1)
    private val nextRepeatAt = LongArray(2)

    /** Horizontal position to aim for when moving between rows, like a text editor's goal column. */
    val goalX = FloatArray(2)

    fun isHeld(side: Int) = sector[side] >= 0

    fun anyHeld() = sector[0] >= 0 || sector[1] >= 0

    fun reset() {
        sector.fill(-1)
    }

    /**
     * Feeds the stick position for [side] at time [now] (uptime ms). Returns the direction to step
     * now, or -1 for no step.
     */
    fun update(side: Int, x: Float, y: Float, now: Long, delayMs: Long, intervalMs: Long): Int {
        val s = sectorOf(side, x, y)
        if (s != sector[side]) {
            sector[side] = s
            if (s < 0) return -1
            nextRepeatAt[side] = now + delayMs
            return s
        }
        if (s >= 0 && now >= nextRepeatAt[side]) {
            // Keep a steady rhythm, but don't burst to catch up after a stall.
            val next = nextRepeatAt[side] + intervalMs
            nextRepeatAt[side] = if (next <= now) now + intervalMs else next
            return s
        }
        return -1
    }

    /** Quantizes the stick to eight directions, with hysteresis so noise on a boundary doesn't flip it. */
    private fun sectorOf(side: Int, x: Float, y: Float): Int {
        val current = sector[side]
        val m = hypot(x, y)
        if (m < if (current >= 0) RELEASE else ENGAGE) return -1
        val angle = atan2(y, x).toDouble()
        if (current >= 0) {
            var diff = angle - current * SECTOR
            while (diff > PI) diff -= 2 * PI
            while (diff < -PI) diff += 2 * PI
            if (abs(diff) <= SECTOR / 2 + HYSTERESIS) return current
        }
        return Math.floorMod((angle / SECTOR).roundToInt(), 8)
    }

    companion object {
        /** Step offsets per direction: column change and row change. */
        val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        private const val ENGAGE = 0.5f
        private const val RELEASE = 0.35f
        private const val SECTOR = PI / 4
        private const val HYSTERESIS = 10 * PI / 180
    }
}
