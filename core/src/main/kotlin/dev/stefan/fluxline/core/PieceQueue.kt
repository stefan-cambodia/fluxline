package dev.stefan.fluxline.core

/**
 * Le distributeur : une file de pièces visibles, la prochaine en tête.
 *
 * Le tirage est pondéré (droites et coudes fréquents, croix plus rares) et
 * corrigé contre les séries : une même pièce ne sort jamais trois fois de suite.
 * Avec [limit] > 0, le distributeur se tarit après ce nombre de pièces (défis).
 */
class PieceQueue(
    seed: Long,
    val visible: Int = 5,
    val limit: Int = 0,
) {
    val rng = Rng(seed)
    private val slots = arrayOfNulls<Piece>(visible)

    /** Nombre de pièces déjà sorties du distributeur (tirées dans la file). */
    var drawn: Int = 0
        private set

    /** Nombre de pièces effectivement posées depuis la tête. */
    var consumed: Int = 0
        private set

    init {
        for (i in 0 until visible) slots[i] = draw()
    }

    /** La prochaine pièce, ou null si le distributeur est vide. */
    val next: Piece? get() = slots[0]

    operator fun get(index: Int): Piece? = slots[index]

    val remaining: Int get() = if (limit <= 0) Int.MAX_VALUE else limit - consumed

    /** Retire la tête et fait avancer la file. */
    fun pop(): Piece? {
        val head = slots[0] ?: return null
        for (i in 0 until visible - 1) slots[i] = slots[i + 1]
        slots[visible - 1] = null
        slots[visible - 1] = draw()
        consumed++
        return head
    }

    /** Fait pivoter la prochaine pièce d'un quart de tour. */
    fun rotateNext(): Piece? {
        val head = slots[0] ?: return null
        val rotated = head.rotatedClockwise()
        slots[0] = rotated
        return rotated
    }

    private fun draw(): Piece? {
        if (limit > 0 && drawn >= limit) return null
        var piece = weighted()
        // Anti-série : jamais trois fois la même pièce d'affilée.
        val last = lastDrawn(0)
        val beforeLast = lastDrawn(1)
        while (piece == last && piece == beforeLast) piece = weighted()
        drawn++
        return piece
    }

    private fun lastDrawn(back: Int): Piece? {
        // Les pièces déjà en file sont les plus récemment tirées.
        var seen = 0
        for (i in visible - 1 downTo 0) {
            val p = slots[i] ?: continue
            if (seen == back) return p
            seen++
        }
        return null
    }

    private fun weighted(): Piece {
        var roll = rng.nextInt(TOTAL_WEIGHT)
        for (i in WEIGHTED.indices) {
            roll -= WEIGHTS[i]
            if (roll < 0) return WEIGHTED[i]
        }
        return WEIGHTED.last()
    }

    // --- Sauvegarde --------------------------------------------------------------

    fun snapshot(): QueueState = QueueState(rng.state, slots.map { it?.code ?: '.' }.joinToString(""), drawn, consumed)

    fun restore(state: QueueState) {
        rng.state = state.rngState
        for (i in 0 until visible) {
            val c = state.slots.getOrNull(i) ?: '.'
            slots[i] = if (c == '.') null else Piece.byCode(c)
        }
        drawn = state.drawn
        consumed = state.consumed
    }

    companion object {
        private val WEIGHTED = arrayOf(
            Piece.STRAIGHT_H, Piece.STRAIGHT_V,
            Piece.ELBOW_NE, Piece.ELBOW_ES, Piece.ELBOW_SW, Piece.ELBOW_WN,
            Piece.CROSS,
        )
        private val WEIGHTS = intArrayOf(15, 15, 14, 14, 14, 14, 10)
        private val TOTAL_WEIGHT = WEIGHTS.sum()
    }
}

data class QueueState(val rngState: Long, val slots: String, val drawn: Int, val consumed: Int)
