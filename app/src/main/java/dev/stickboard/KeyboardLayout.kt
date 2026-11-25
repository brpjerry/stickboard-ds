package dev.stickboard

enum class KeyType { CHAR, SPACE, BACKSPACE, ENTER, SHIFT, LAYER }

class Key(
    val label: String,
    val type: KeyType = KeyType.CHAR,
    val text: String = label,
    /** What the key types while shift is on. Null means the upper-case form of [text]. */
    val shifted: String? = null,
    val weight: Float = 1f,
) {
    val isModifier get() = type == KeyType.SHIFT || type == KeyType.LAYER
}

/**
 * One half of the split keyboard. Geometry is normalized: x and y both run 0..1 across the
 * half, rows share the height equally and each row is stretched to the full width by weight.
 */
class Half(val rows: List<List<Key>>) {
    private val rowWeights = rows.map { row -> row.sumOf { it.weight.toDouble() }.toFloat() }

    fun keyAt(x: Float, y: Float): Key? {
        if (rows.isEmpty()) return null
        val r = (y.coerceIn(0f, MAX_COORD) * rows.size).toInt()
        val row = rows[r]
        val target = x.coerceIn(0f, MAX_COORD) * rowWeights[r]
        var acc = 0f
        for (key in row) {
            acc += key.weight
            if (target < acc) return key
        }
        return row.lastOrNull()
    }

    /** Calls [block] with each key and its normalized bounds (left, top, right, bottom). */
    fun forEachKey(block: (key: Key, l: Float, t: Float, r: Float, b: Float) -> Unit) {
        val rowH = 1f / rows.size
        rows.forEachIndexed { ri, row ->
            val total = rowWeights[ri]
            var acc = 0f
            for (key in row) {
                val l = acc / total
                acc += key.weight
                block(key, l, ri * rowH, acc / total, (ri + 1) * rowH)
            }
        }
    }

    /**
     * The area trackpad mode spans, as (left, top, right, bottom, restX, restY). Full tilt reaches
     * only [TrackpadMapping.EDGE_DEPTH] of the way into the outermost keys instead of the half's
     * edge, which leaves more stick travel for the keys inside. Rows whose edge key is wider than
     * the narrowest one are reached with room to spare.
     *
     * The rest point is where a centered stick points: the middle row, or with an even number of
     * rows the upper of the two middle ones (the home row when the number row is hidden), so it
     * never sits on a border between rows.
     */
    val trackpadReach: FloatArray by lazy {
        val depth = TrackpadMapping.EDGE_DEPTH
        var minFirstRight = 1f
        var maxLastLeft = 0f
        rows.forEachIndexed { ri, row ->
            if (row.isEmpty()) return@forEachIndexed
            minFirstRight = minOf(minFirstRight, row.first().weight / rowWeights[ri])
            maxLastLeft = maxOf(maxLastLeft, 1f - row.last().weight / rowWeights[ri])
        }
        val rowH = 1f / rows.size
        floatArrayOf(
            minFirstRight * (1f - depth),
            rowH * (1f - depth),
            maxLastLeft + (1f - maxLastLeft) * depth,
            1f - rowH * (1f - depth),
            0.5f,
            ((rows.size - 1) / 2 + 0.5f) * rowH,
        )
    }

    /** Row and index within the row of [key], or null if it isn't in this half. */
    fun positionOf(key: Key): Pair<Int, Int>? {
        rows.forEachIndexed { r, row ->
            val i = row.indexOfFirst { it === key }
            if (i >= 0) return r to i
        }
        return null
    }

    /** Index of the key in [row] whose span contains [x]. */
    fun indexAt(row: Int, x: Float): Int {
        val target = x.coerceIn(0f, MAX_COORD) * rowWeights[row]
        var acc = 0f
        rows[row].forEachIndexed { i, key ->
            acc += key.weight
            if (target < acc) return i
        }
        return rows[row].lastIndex
    }

    /** Writes the normalized center of the key at ([row], [index]) into [out]. */
    fun centerOf(row: Int, index: Int, out: FloatArray) {
        val keys = rows[row]
        val before = keys.subList(0, index).sumOf { it.weight.toDouble() }.toFloat()
        out[0] = (before + keys[index].weight / 2) / rowWeights[row]
        out[1] = (row + 0.5f) / rows.size
    }

    fun keyLabeled(label: String): Key? = rows.firstNotNullOfOrNull { row -> row.firstOrNull { it.label == label } }

    /** Normalized vertical center of the row holding the key labeled [label], or null. */
    fun rowCenterOf(label: String): Float? {
        val r = rows.indexOfFirst { row -> row.any { it.label == label } }
        return if (r < 0) null else (r + 0.5f) / rows.size
    }

    /** Normalized bounds of [key] as (left, top, right, bottom), or null if it isn't in this half. */
    fun boundsOf(key: Key): FloatArray? {
        var found: FloatArray? = null
        forEachKey { k, l, t, r, b -> if (k === key) found = floatArrayOf(l, t, r, b) }
        return found
    }

    companion object {
        const val MAX_COORD = 0.9999f
    }
}

class KeyboardLayout(val left: Half, val right: Half) {
    fun half(side: Int) = if (side == 0) left else right

    /** This layout without its top row on either half. */
    fun withoutTopRow() = KeyboardLayout(Half(left.rows.drop(1)), Half(right.rows.drop(1)))
}

object Layouts {
    private fun row(spec: String) = spec.split(' ').map { Key(it) }

    private fun shiftedRow(spec: String, shifted: String) =
        spec.split(' ').zip(shifted.split(' ')).map { (k, s) -> Key(k, shifted = s) }

    private fun shift() = Key("⇧", KeyType.SHIFT)
    private fun layer(label: String) = Key(label, KeyType.LAYER, weight = 1.5f)
    private fun space(weight: Float) = Key("space", KeyType.SPACE, text = " ", weight = weight)
    private fun backspace(weight: Float) = Key("⌫", KeyType.BACKSPACE, weight = weight)
    private fun enter() = Key("↵", KeyType.ENTER, weight = 2f)

    val LETTERS = KeyboardLayout(
        left = Half(
            listOf(
                shiftedRow("1 2 3 4 5", "! @ # $ %"),
                row("q w e r t"),
                row("a s d f g"),
                listOf(shift()) + row("z x c v"),
                listOf(layer("?123"), Key(",", shifted = ";"), space(2.5f)),
            )
        ),
        right = Half(
            listOf(
                shiftedRow("6 7 8 9 0", "^ & * ( )"),
                row("y u i o p"),
                row("h j k l") + Key("'", shifted = "\""),
                row("b n m") + backspace(2f),
                listOf(space(2f), Key(".", shifted = ":"), enter()),
            )
        ),
    )

    /** Letters without the number row; digits stay on the symbols layer. */
    val LETTERS_NO_NUMBERS = LETTERS.withoutTopRow()

    val SYMBOLS = KeyboardLayout(
        left = Half(
            listOf(
                row("1 2 3 4 5"),
                row("! @ # $ %"),
                row("- _ + = \\"),
                row("[ ] { } |"),
                listOf(layer("ABC"), Key(","), space(2.5f)),
            )
        ),
        right = Half(
            listOf(
                row("6 7 8 9 0"),
                row("^ & * ( )"),
                row(": ; \" ' /"),
                row("? < > ~ `") + backspace(1.5f),
                listOf(space(2f), Key("."), enter()),
            )
        ),
    )
}
