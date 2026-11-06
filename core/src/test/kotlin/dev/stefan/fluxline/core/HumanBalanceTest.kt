package dev.stefan.fluxline.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * L'équilibrage vu par des joueurs humains simulés : plus lents que le robot,
 * ralentis par chaque rotation, et qui se trompent parfois (une option plausible
 * au lieu de la meilleure). Les graines sont fixes : les taux sont reproductibles.
 *
 * Chaque joueur choisit sa façon de jouer selon le niveau (coup par coup, ou
 * itinéraire vers l'arrivée) : on garde la meilleure des deux.
 */
class HumanBalanceTest {
    private class Profile(val name: String, val think: Float, val rotate: Float, val mistakes: Float)

    private val beginner = Profile("débutant", think = 2.2f, rotate = 0.35f, mistakes = 0.30f)
    private val casual = Profile("occasionnel", think = 1.6f, rotate = 0.30f, mistakes = 0.18f)

    private fun play(spec: LevelSpec, bot: AutoPlayer): Boolean {
        val s = GameSession(spec)
        val dt = 1f / 60f
        var guard = 0
        while (!s.isFinished && guard++ < 60 * 60 * 10) {
            bot.update(s, dt)
            s.update(dt)
        }
        return s.result!!.success
    }

    /** Taux de réussite (%) sur [specs], chaque plateau joué avec sa propre graine d'erreurs. */
    private fun rate(p: Profile, specs: List<LevelSpec>): Int = listOf(false, true).maxOf { planning ->
        specs.indices.count { i -> play(specs[i], AutoPlayer(p.think, planning, p.mistakes, p.rotate, seed = i + 1L)) } * 100 / specs.size
    }

    private fun check(label: String, rate: Int, min: Int, failures: MutableList<String>) {
        println("$label : $rate %")
        if (rate < min) failures += "$label : $rate % (minimum $min %)"
    }

    @Test
    fun `la campagne accueille les debutants puis monte progressivement`() {
        val failures = ArrayList<String>()
        for (n in 1..Campaign.size) {
            val runs = List(30) { Campaign.level(n) }
            if (n <= 9) check("niveau $n, débutant", rate(beginner, runs), 75, failures)
            check("niveau $n, occasionnel", rate(casual, runs), 65, failures)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `les premieres manches classiques et arcade sont abordables`() {
        val failures = ArrayList<String>()
        for (level in 1..3) {
            val classic = (0L until 20L).map { LevelGenerator.classic(level, it) }
            val arcade = (0L until 20L).map { LevelGenerator.arcade(level, it) }
            if (level <= 2) {
                check("Classique manche $level, débutant", rate(beginner, classic), 70, failures)
                check("Arcade vague $level, débutant", rate(beginner, arcade), 75, failures)
            }
            check("Classique manche $level, occasionnel", rate(casual, classic), 80, failures)
            check("Arcade vague $level, occasionnel", rate(casual, arcade), 85, failures)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
