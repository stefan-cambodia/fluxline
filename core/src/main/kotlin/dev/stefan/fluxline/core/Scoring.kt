package dev.stefan.fluxline.core

/**
 * Le barème. Regroupé ici pour qu'il soit lisible d'un coup d'œil, testé, et
 * affiché tel quel dans l'aide du jeu.
 */
object Scoring {
    /** Un tuyau rempli. */
    const val PIPE = 50
    /** Un tuyau rempli au-delà de l'objectif : le dépassement rapporte double. */
    const val PIPE_OVER_TARGET = 100
    /** Un réservoir rempli (lent, donc mieux payé). */
    const val RESERVOIR = 150
    /** Le fluide traverse une croix pour la seconde fois, dans l'autre sens. */
    const val CROSS_BONUS = 500
    /** Le fluide atteint l'arrivée. */
    const val DRAIN_BONUS = 1000
    /** Remplacer une pièce posée que le fluide n'a pas encore atteinte. */
    const val REPLACE_PENALTY = 50
    /** Fin de manche : chaque pièce posée que le fluide n'a jamais traversée. */
    const val UNUSED_PENALTY = 50
    /** Multiplicateur des tuyaux remplis pendant l'accélération. */
    const val FAST_MULTIPLIER = 2

    /** Délai pendant lequel la pose est bloquée après un remplacement (secondes). */
    const val REPLACE_COOLDOWN = 0.4f

    fun pipePoints(piece: Piece, countAfter: Int, target: Int, fast: Boolean): Int {
        val base = when {
            piece.family == PieceFamily.RESERVOIR -> RESERVOIR
            countAfter > target -> PIPE_OVER_TARGET
            else -> PIPE
        }
        return if (fast) base * FAST_MULTIPLIER else base
    }
}
