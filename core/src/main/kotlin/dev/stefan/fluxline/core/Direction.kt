package dev.stefan.fluxline.core

/**
 * Les quatre côtés d'une case. La grille est indexée ligne 0 en haut :
 * aller au nord, c'est décrémenter y.
 */
enum class Direction(val dx: Int, val dy: Int) {
    N(0, -1),
    E(1, 0),
    S(0, 1),
    W(-1, 0);

    val opposite: Direction
        get() = when (this) {
            N -> S
            E -> W
            S -> N
            W -> E
        }

    /** Rotation d'un quart de tour dans le sens horaire. */
    val clockwise: Direction
        get() = when (this) {
            N -> E
            E -> S
            S -> W
            W -> N
        }

    val isHorizontal: Boolean get() = this == E || this == W

    companion object {
        private val ALL = entries.toTypedArray()

        fun of(ordinal: Int): Direction = ALL[ordinal]
    }
}
