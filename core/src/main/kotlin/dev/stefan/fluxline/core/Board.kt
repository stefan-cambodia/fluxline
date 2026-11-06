package dev.stefan.fluxline.core

/** État d'un conduit. */
enum class ChannelState { EMPTY, FILLING, FULL }

/**
 * La grille et l'état du fluide dans chaque conduit. Tout est rangé dans des
 * tableaux plats (deux conduits au plus par case) : aucune allocation pendant
 * la simulation, et une copie triviale pour la sauvegarde.
 */
class Board(val cols: Int, val rows: Int) {
    val size = cols * rows

    private val pieces = arrayOfNulls<Piece>(size)
    private val fixed = BooleanArray(size)
    private val placedByPlayer = BooleanArray(size)
    private val fill = FloatArray(size * 2)
    private val states = ByteArray(size * 2)
    /** Port par lequel le fluide est entré dans le conduit : 0..3, CENTER, ou NONE. */
    private val entries = ByteArray(size * 2) { NONE }

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until cols && y in 0 until rows

    fun index(x: Int, y: Int): Int = y * cols + x

    fun piece(x: Int, y: Int): Piece? = pieces[index(x, y)]

    fun isFixed(x: Int, y: Int): Boolean = fixed[index(x, y)]

    fun isPlacedByPlayer(x: Int, y: Int): Boolean = placedByPlayer[index(x, y)]

    fun channelState(x: Int, y: Int, channel: Int): ChannelState =
        STATES[states[index(x, y) * 2 + channel].toInt()]

    fun channelFill(x: Int, y: Int, channel: Int): Float = fill[index(x, y) * 2 + channel]

    /** Port d'entrée du fluide dans ce conduit (null = centre ou pas encore entré). */
    fun channelEntry(x: Int, y: Int, channel: Int): Direction? {
        val e = entries[index(x, y) * 2 + channel].toInt()
        return if (e in 0..3) Direction.of(e) else null
    }

    fun hasEntered(x: Int, y: Int, channel: Int): Boolean = entries[index(x, y) * 2 + channel] != NONE

    /** Vrai si le fluide a touché au moins un conduit de la case. */
    fun isTouched(x: Int, y: Int): Boolean {
        val i = index(x, y) * 2
        return states[i] != 0.toByte() || states[i + 1] != 0.toByte()
    }

    fun setPiece(x: Int, y: Int, piece: Piece?, isFixed: Boolean, byPlayer: Boolean) {
        val i = index(x, y)
        pieces[i] = piece
        fixed[i] = isFixed
        placedByPlayer[i] = byPlayer
        resetChannels(i)
    }

    internal fun startChannel(x: Int, y: Int, channel: Int, entry: Direction?) {
        val i = index(x, y) * 2 + channel
        states[i] = 1
        fill[i] = 0f
        entries[i] = entry?.ordinal?.toByte() ?: CENTER
    }

    internal fun setFill(x: Int, y: Int, channel: Int, amount: Float) {
        fill[index(x, y) * 2 + channel] = amount
    }

    internal fun completeChannel(x: Int, y: Int, channel: Int) {
        val i = index(x, y) * 2 + channel
        states[i] = 2
        fill[i] = 1f
    }

    private fun resetChannels(cell: Int) {
        for (c in 0..1) {
            val i = cell * 2 + c
            states[i] = 0
            fill[i] = 0f
            entries[i] = NONE
        }
    }

    fun loadLayout(spec: LevelSpec) {
        require(spec.cols == cols && spec.rows == rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = spec.pieceAt(x, y)
            setPiece(x, y, p, isFixed = p != null, byPlayer = false)
        }
    }

    /** Coordonnées de l'unique source. */
    fun findSource(): Pair<Int, Int> {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (pieces[index(x, y)]?.family == PieceFamily.SOURCE) return x to y
        }
        error("aucune source sur le plateau")
    }

    // --- Sauvegarde --------------------------------------------------------------

    fun encode(): BoardState = BoardState(
        cols = cols,
        rows = rows,
        pieces = pieces.map { it?.code ?: '.' }.joinToString(""),
        fixed = fixed.map { if (it) '1' else '0' }.joinToString(""),
        placedByPlayer = placedByPlayer.map { if (it) '1' else '0' }.joinToString(""),
        fill = fill.copyOf(),
        states = states.copyOf(),
        entries = entries.copyOf(),
    )

    fun decode(state: BoardState) {
        require(state.cols == cols && state.rows == rows) { "dimensions incompatibles" }
        for (i in 0 until size) {
            val c = state.pieces[i]
            pieces[i] = if (c == '.') null else requireNotNull(Piece.byCode(c))
            fixed[i] = state.fixed[i] == '1'
            placedByPlayer[i] = state.placedByPlayer[i] == '1'
        }
        state.fill.copyInto(fill)
        state.states.copyInto(states)
        state.entries.copyInto(entries)
    }

    companion object {
        const val NONE: Byte = -1
        const val CENTER: Byte = 4
        private val STATES = ChannelState.entries.toTypedArray()
    }
}

class BoardState(
    val cols: Int,
    val rows: Int,
    val pieces: String,
    val fixed: String,
    val placedByPlayer: String,
    val fill: FloatArray,
    val states: ByteArray,
    val entries: ByteArray,
)
