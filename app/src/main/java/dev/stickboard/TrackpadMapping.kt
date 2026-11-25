package dev.stickboard

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Trackpad mode: every stick position (angle and distance from center) maps to exactly one point
 * on the keyboard half, the way a finger's position on the Steam Deck trackpad does.
 *
 * A stick moves inside a round gate but the keyboard half is a rectangle. Scaling and clamping
 * would squash the whole rim onto the edges, so many angles would land on the same key. Instead the
 * disc is stretched onto the square with the elliptical grid mapping (Fong, "Analytical methods for
 * squaring the disc"): it's one-to-one, keeps the center in the center, and takes the rim to the
 * square's outline, so pushing the stick fully toward a corner reaches the corner key.
 */
object TrackpadMapping {
    /** Inner dead zone, so a stick resting slightly off center still points at the center. */
    const val DEADZONE = 0.12f

    /**
     * How far into the outermost keys full tilt reaches, as a share of their size. It has to clear
     * the key-switch hysteresis (KeyboardController.STICKY_MARGIN, 0.2) with some room left, or
     * edge keys would be hard to land on.
     */
    const val EDGE_DEPTH = 0.35f

    /** Deflection treated as the rim. Most sticks don't quite reach 1.0 on the diagonals. */
    private const val SATURATION = 0.92f

    /**
     * Maps raw stick axes (-1..1, y down) to a normalized point on the half, inside [reach]
     * (left, top, right, bottom, restX, restY; see Half.trackpadReach). A centered stick lands on
     * the rest point and each direction is scaled separately to its edge. Returns the result in
     * [out] to avoid allocating per motion event.
     */
    fun map(x: Float, y: Float, reach: FloatArray, out: FloatArray) {
        val m = hypot(x, y)
        var u = 0f
        var v = 0f
        if (m > DEADZONE) {
            val r = ((m - DEADZONE) / (SATURATION - DEADZONE)).coerceIn(0f, 1f)
            u = x / m * r
            v = y / m * r
        }
        out[0] = axis(discToSquareX(u, v), reach[0], reach[4], reach[2])
        out[1] = axis(discToSquareX(v, u), reach[1], reach[5], reach[3])
    }

    fun isResting(x: Float, y: Float) = hypot(x, y) <= DEADZONE

    /** Maps s in -1..1 to lo..hi with 0 landing on [rest]. */
    private fun axis(s: Float, lo: Float, rest: Float, hi: Float): Float {
        val v = if (s < 0) rest + s * (rest - lo) else rest + s * (hi - rest)
        return v.coerceIn(0f, Half.MAX_COORD)
    }

    // x = ½√(2 + u² − v² + 2√2·u) − ½√(2 + u² − v² − 2√2·u); y is the same with u and v swapped.
    private fun discToSquareX(u: Float, v: Float): Float {
        val base = 2f + u * u - v * v
        val t = 2f * SQRT2 * u
        return 0.5f * sqrt((base + t).coerceAtLeast(0f)) - 0.5f * sqrt((base - t).coerceAtLeast(0f))
    }

    private const val SQRT2 = 1.4142135f
}
