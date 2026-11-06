package dev.stefan.fluxline.game.save

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Preferences
import dev.stefan.fluxline.core.Campaign
import dev.stefan.fluxline.core.GameMode
import dev.stefan.fluxline.core.RoundResult
import dev.stefan.fluxline.core.SaveCodec
import dev.stefan.fluxline.core.SavedGame

/** Les réglages du joueur. */
class Settings(
    var masterVolume: Float = 0.9f,
    var musicVolume: Float = 0.6f,
    var sfxVolume: Float = 0.9f,
    var haptics: Boolean = true,
    /** Bloom (lueur du plasma) : coûteux sur les vieux téléphones. */
    var glow: Boolean = true,
    /** Lignes de balayage façon écran cathodique. */
    var scanlines: Boolean = false,
    /** Signale la prochaine case que le fluide atteindra. */
    var nextCellHint: Boolean = true,
    /** Code de langue (fr, en, ru, zh, km), ou « auto » : celle du système. */
    var language: String = "auto",
)

/** Statistiques cumulées. */
class Stats(
    var roundsPlayed: Int = 0,
    var roundsWon: Int = 0,
    var pipesFilled: Long = 0,
    var crossings: Int = 0,
    var drains: Int = 0,
    var piecesPlaced: Long = 0,
    var secondsPlayed: Float = 0f,
    var bestClassicScore: Int = 0,
    var bestClassicLevel: Int = 0,
    var bestArcadeScore: Int = 0,
    var bestArcadeLevel: Int = 0,
    var challengesWon: Int = 0,
)

/**
 * La sauvegarde : réglages, progression de la campagne, records, statistiques
 * et partie en cours. Tout passe par les Preferences de libGDX (SharedPreferences
 * sur Android) et est écrit sur disque à chaque changement important — jamais
 * seulement gardé en mémoire.
 */
class SaveData(private val prefs: Preferences = Gdx.app.getPreferences(PREFS_NAME)) {
    val settings = Settings()
    val stats = Stats()
    private val stars = IntArray(Campaign.size + 1)
    private val bestScores = HashMap<String, Int>()

    init {
        load()
    }

    private fun load() {
        try {
            if (prefs.getInteger("schema", 0) > SCHEMA) return
            settings.masterVolume = prefs.getFloat("set.master", settings.masterVolume).coerceIn(0f, 1f)
            settings.musicVolume = prefs.getFloat("set.music", settings.musicVolume).coerceIn(0f, 1f)
            settings.sfxVolume = prefs.getFloat("set.sfx", settings.sfxVolume).coerceIn(0f, 1f)
            settings.haptics = prefs.getBoolean("set.haptics", settings.haptics)
            settings.glow = prefs.getBoolean("set.glow", settings.glow)
            settings.scanlines = prefs.getBoolean("set.scanlines", settings.scanlines)
            settings.nextCellHint = prefs.getBoolean("set.hint", settings.nextCellHint)
            settings.language = prefs.getString("set.lang", settings.language)
            for (i in 1..Campaign.size) stars[i] = prefs.getInteger("stars.$i", 0).coerceIn(0, 3)
            stats.roundsPlayed = prefs.getInteger("st.rounds", 0)
            stats.roundsWon = prefs.getInteger("st.won", 0)
            stats.pipesFilled = prefs.getLong("st.pipes", 0)
            stats.crossings = prefs.getInteger("st.cross", 0)
            stats.drains = prefs.getInteger("st.drains", 0)
            stats.piecesPlaced = prefs.getLong("st.placed", 0)
            stats.secondsPlayed = prefs.getFloat("st.time", 0f)
            stats.bestClassicScore = prefs.getInteger("st.bestClassic", 0)
            stats.bestClassicLevel = prefs.getInteger("st.bestClassicLevel", 0)
            stats.bestArcadeScore = prefs.getInteger("st.bestArcade", 0)
            stats.bestArcadeLevel = prefs.getInteger("st.bestArcadeLevel", 0)
            stats.challengesWon = prefs.getInteger("st.challenges", 0)
            for ((key, value) in prefs.get()) {
                if (key.startsWith("best.")) bestScores[key.removePrefix("best.")] = (value as? String)?.toIntOrNull() ?: (value as? Int) ?: 0
            }
        } catch (e: RuntimeException) {
            // Une sauvegarde illisible ne doit jamais empêcher de jouer.
            Gdx.app.error("Fluxline", "sauvegarde illisible, valeurs par défaut", e)
        }
    }

    fun saveSettings() {
        prefs.putInteger("schema", SCHEMA)
        prefs.putFloat("set.master", settings.masterVolume)
        prefs.putFloat("set.music", settings.musicVolume)
        prefs.putFloat("set.sfx", settings.sfxVolume)
        prefs.putBoolean("set.haptics", settings.haptics)
        prefs.putBoolean("set.glow", settings.glow)
        prefs.putBoolean("set.scanlines", settings.scanlines)
        prefs.putBoolean("set.hint", settings.nextCellHint)
        prefs.putString("set.lang", settings.language)
        prefs.flush()
    }

    // --- Campagne ------------------------------------------------------------------

    fun stars(level: Int): Int = stars.getOrElse(level) { 0 }

    val totalStars: Int get() = stars.sum()

    /** Un niveau est ouvert si le précédent a été réussi (le premier l'est toujours). */
    fun isUnlocked(level: Int, openAll: Boolean = false): Boolean = openAll || level <= 1 || stars(level - 1) > 0

    fun recordLevel(level: Int, result: RoundResult) {
        if (result.stars > stars(level)) {
            stars[level] = result.stars
            prefs.putInteger("stars.$level", result.stars)
        }
        recordBest("level-$level", result.score)
        prefs.flush()
    }

    fun best(key: String): Int = bestScores[key] ?: 0

    /** Enregistre un record ; renvoie vrai s'il est battu. */
    fun recordBest(key: String, score: Int): Boolean {
        if (score <= best(key)) return false
        bestScores[key] = score
        prefs.putString("best.$key", score.toString())
        prefs.flush()
        return true
    }

    // --- Statistiques ----------------------------------------------------------------

    fun recordRound(mode: GameMode, result: RoundResult) {
        stats.roundsPlayed++
        if (result.success) stats.roundsWon++
        stats.pipesFilled += result.filled
        stats.crossings += result.crossings
        if (result.drainReached) stats.drains++
        stats.piecesPlaced += result.piecesPlaced
        stats.secondsPlayed += result.durationSeconds
        if (mode == GameMode.CHALLENGE && result.success) stats.challengesWon++
        saveStats()
    }

    /** Fin d'une partie Classique ou Arcade ; renvoie vrai si le record est battu. */
    fun recordRun(mode: GameMode, totalScore: Int, level: Int): Boolean {
        var record = false
        when (mode) {
            GameMode.CLASSIC -> {
                if (totalScore > stats.bestClassicScore) {
                    stats.bestClassicScore = totalScore
                    record = true
                }
                stats.bestClassicLevel = maxOf(stats.bestClassicLevel, level)
            }
            GameMode.ARCADE -> {
                if (totalScore > stats.bestArcadeScore) {
                    stats.bestArcadeScore = totalScore
                    record = true
                }
                stats.bestArcadeLevel = maxOf(stats.bestArcadeLevel, level)
            }
            else -> Unit
        }
        saveStats()
        return record
    }

    private fun saveStats() {
        prefs.putInteger("schema", SCHEMA)
        prefs.putInteger("st.rounds", stats.roundsPlayed)
        prefs.putInteger("st.won", stats.roundsWon)
        prefs.putLong("st.pipes", stats.pipesFilled)
        prefs.putInteger("st.cross", stats.crossings)
        prefs.putInteger("st.drains", stats.drains)
        prefs.putLong("st.placed", stats.piecesPlaced)
        prefs.putFloat("st.time", stats.secondsPlayed)
        prefs.putInteger("st.bestClassic", stats.bestClassicScore)
        prefs.putInteger("st.bestClassicLevel", stats.bestClassicLevel)
        prefs.putInteger("st.bestArcade", stats.bestArcadeScore)
        prefs.putInteger("st.bestArcadeLevel", stats.bestArcadeLevel)
        prefs.putInteger("st.challenges", stats.challengesWon)
        prefs.flush()
    }

    // --- Partie en cours ------------------------------------------------------------

    fun saveGame(game: SavedGame) {
        prefs.putString("game", SaveCodec.encode(game))
        prefs.flush()
    }

    fun loadGame(): SavedGame? = SaveCodec.decode(prefs.getString("game", null))

    val hasSavedGame: Boolean get() = loadGame() != null

    fun clearGame() {
        prefs.remove("game")
        prefs.flush()
    }

    /** Efface la progression et les statistiques (les réglages sont conservés). */
    fun resetProgress() {
        for (i in 1..Campaign.size) {
            stars[i] = 0
            prefs.remove("stars.$i")
        }
        for (key in bestScores.keys) prefs.remove("best.$key")
        bestScores.clear()
        val fresh = Stats()
        stats.roundsPlayed = fresh.roundsPlayed
        stats.roundsWon = 0
        stats.pipesFilled = 0
        stats.crossings = 0
        stats.drains = 0
        stats.piecesPlaced = 0
        stats.secondsPlayed = 0f
        stats.bestClassicScore = 0
        stats.bestClassicLevel = 0
        stats.bestArcadeScore = 0
        stats.bestArcadeLevel = 0
        stats.challengesWon = 0
        saveStats()
        clearGame()
    }

    companion object {
        const val PREFS_NAME = "fluxline.save"
        const val SCHEMA = 1
    }
}
