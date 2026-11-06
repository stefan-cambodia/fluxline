package dev.stefan.fluxline.game

/** Les retours haptiques, du plus discret au plus marqué. */
enum class Haptic { TICK, PLACE, REPLACE, ERROR, SUCCESS, FAIL }

/**
 * Ce que le jeu demande à la plateforme. Android branche le vibreur et la date
 * locale ; le lanceur PC fournit des versions neutres.
 */
interface Platform {
    fun haptic(kind: Haptic) {}

    /** Date locale au format AAAAMMJJ (clé du défi du jour). */
    fun today(): Int {
        val d = java.time.LocalDate.now()
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    /** Langue du système (code ISO 639, « fr », « zh »…) : choix par défaut de la langue du jeu. */
    fun systemLanguage(): String = java.util.Locale.getDefault().language

    /** Vrai pour les versions de développement (outils de capture, tout débloqué). */
    val devTools: Boolean get() = false
}

/** Options de lancement utilisées par les outils de développement (captures, démo). */
data class LaunchOptions(
    /** Écran de départ : menu, game, levels, options, stats, credits, result. */
    val startScreen: String = "menu",
    /** Le robot joue la partie (démonstration, mesure de performance). */
    val autoplay: Boolean = false,
    /** Si non nul : capture d'écran enregistrée dans ce fichier, puis sortie. */
    val shotPath: String? = null,
    val shotDelay: Float = 2.5f,
    val mode: String = "classic",
    val level: Int = 1,
    /** Accélère le temps de jeu (captures en fin de manche). */
    val timeScale: Float = 1f,
    /** Journalise les temps d'image toutes les N secondes (0 = jamais). */
    val perfLogSeconds: Float = 0f,
    /** Impose une langue pour cette session (captures), sans toucher aux réglages. */
    val language: String? = null,
)
