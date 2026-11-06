package dev.stefan.fluxline.core

/**
 * Ce que la partie annonce au reste du jeu. Le rendu, l'audio et les vibrations
 * s'y abonnent ; la logique, elle, ne sait rien de ce qui en est fait.
 */
sealed interface GameEvent {
    data class Placed(val x: Int, val y: Int, val piece: Piece, val replaced: Piece?) : GameEvent
    data class Refused(val x: Int, val y: Int, val reason: Refusal) : GameEvent
    data class Rotated(val piece: Piece) : GameEvent
    data class CountdownTick(val secondsLeft: Int) : GameEvent
    data object FlowStarted : GameEvent
    data object FastForward : GameEvent
    data class ChannelEntered(val x: Int, val y: Int, val channel: Int, val entry: Direction?) : GameEvent
    data class ChannelFilled(
        val x: Int,
        val y: Int,
        val channel: Int,
        val points: Int,
        val crossBonus: Boolean,
        val count: Int,
    ) : GameEvent
    data object TargetReached : GameEvent
    /** Le fluide sort de la case (x, y) par [port] et ne trouve rien pour l'accueillir. */
    data class Leaked(val x: Int, val y: Int, val port: Direction) : GameEvent
    data class DrainReached(val x: Int, val y: Int) : GameEvent
    data class RoundEnded(val result: RoundResult) : GameEvent
}

/** Pourquoi une pose est refusée. */
enum class Refusal {
    /** La case contient une pièce du niveau (source, bloc, réservoir…). */
    FIXED,
    /** Le fluide a déjà atteint cette pièce. */
    FLUID,
    /** Le distributeur est vide (défi à pièces limitées). */
    EMPTY_QUEUE,
    /** Une pièce vient d'être remplacée : la pose reprend dans un instant. */
    COOLDOWN,
    /** La manche est terminée. */
    FINISHED,
}

/** Le bilan d'une manche. */
data class RoundResult(
    val success: Boolean,
    val filled: Int,
    val target: Int,
    val drainReached: Boolean,
    val pipePoints: Int,
    val crossBonus: Int,
    val drainBonus: Int,
    val replacePenalty: Int,
    val unusedPieces: Int,
    val unusedPenalty: Int,
    val score: Int,
    val crossings: Int,
    val replacements: Int,
    val piecesPlaced: Int,
    val durationSeconds: Float,
) {
    /** 0 à 3 étoiles : réussite, puis 125 % et 150 % de l'objectif (ou l'arrivée). */
    val stars: Int
        get() = when {
            !success -> 0
            filled >= starThreshold(target, 3) -> 3
            filled >= starThreshold(target, 2) || drainReached -> 2
            else -> 1
        }

    companion object {
        fun starThreshold(target: Int, stars: Int): Int = when (stars) {
            3 -> (target * 3 + 1) / 2
            2 -> (target * 5 + 3) / 4
            else -> target
        }
    }
}
