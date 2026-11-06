package dev.stefan.fluxline.core

/** Les modes de jeu. */
enum class GameMode(val key: String) {
    /** Les règles du genre : pièces imposées, sans rotation, une manche ratée et c'est fini. */
    CLASSIC("classic"),
    /** La campagne : niveaux dessinés à la main, rotation de la prochaine pièce, étoiles. */
    LEVELS("levels"),
    /** Rythme rapide, rotation, trois vies. */
    ARCADE("arcade"),
    /** Le défi du jour : un plateau et un objectif particulier, les mêmes pour tous ce jour-là. */
    CHALLENGE("challenge");

    companion object {
        fun byKey(key: String): GameMode = entries.first { it.key == key }
    }
}

/** Ce que la partie doit faire après une manche. */
enum class RunOutcome {
    /** Manche suivante. */
    NEXT,
    /** Même niveau, nouveau plateau (une vie de moins en arcade). */
    RETRY,
    /** Partie terminée. */
    GAME_OVER,
    /** Niveau de campagne ou défi terminé : retour au menu. */
    DONE,
}

/**
 * Une partie : la suite des manches d'un mode. [level] commence à 1. En mode
 * campagne, c'est le numéro du niveau joué.
 */
class Run(
    val mode: GameMode,
    val seed: Long,
    level: Int = 1,
    totalScore: Int = 0,
    lives: Int = startingLives(mode),
    /** Nombre de manches jouées dans cette partie (sert à varier les plateaux rejoués). */
    attempt: Int = 0,
    /** Clé du défi du jour (date AAAAMMJJ), 0 sinon. */
    val challengeDay: Int = 0,
) {
    var level: Int = level
        private set
    var totalScore: Int = totalScore
        private set
    var lives: Int = lives
        private set
    var attempt: Int = attempt
        private set

    /** Le plateau de la manche courante. */
    fun currentSpec(): LevelSpec = when (mode) {
        GameMode.CLASSIC -> LevelGenerator.classic(level, Rng.mix(seed, attempt.toLong()))
        GameMode.ARCADE -> LevelGenerator.arcade(level, Rng.mix(seed, attempt.toLong()))
        GameMode.LEVELS -> Campaign.level(level)
        GameMode.CHALLENGE -> LevelGenerator.daily(challengeDay)
    }

    /** Enregistre le bilan d'une manche et décide de la suite. */
    fun record(result: RoundResult): RunOutcome {
        totalScore += result.score
        attempt++
        return when (mode) {
            GameMode.CLASSIC -> if (result.success) {
                level++
                RunOutcome.NEXT
            } else {
                RunOutcome.GAME_OVER
            }
            GameMode.ARCADE -> if (result.success) {
                level++
                RunOutcome.NEXT
            } else {
                lives--
                if (lives > 0) RunOutcome.RETRY else RunOutcome.GAME_OVER
            }
            GameMode.LEVELS, GameMode.CHALLENGE -> RunOutcome.DONE
        }
    }

    fun state(): RunState = RunState(mode, seed, level, totalScore, lives, attempt, challengeDay)

    companion object {
        fun startingLives(mode: GameMode): Int = if (mode == GameMode.ARCADE) 3 else 1

        fun from(s: RunState) = Run(s.mode, s.seed, s.level, s.totalScore, s.lives, s.attempt, s.challengeDay)
    }
}

data class RunState(
    val mode: GameMode,
    val seed: Long,
    val level: Int,
    val totalScore: Int,
    val lives: Int,
    val attempt: Int,
    val challengeDay: Int,
)
