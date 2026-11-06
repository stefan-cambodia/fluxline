package dev.stefan.fluxline.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LevelsTest {

    private fun play(spec: LevelSpec, thinkTime: Float, planning: Boolean = false): RoundResult {
        val s = GameSession(spec)
        val bot = AutoPlayer(thinkTime, planning)
        val dt = 1f / 60f
        var guard = 0
        while (!s.isFinished && guard++ < 60 * 60 * 10) {
            bot.update(s, dt)
            s.update(dt)
        }
        return s.result!!
    }

    @Test
    fun `les plateaux generes sont valides et jouables`() {
        for (level in 1..20) {
            for (seed in 0L until 40L) {
                for (spec in listOf(LevelGenerator.classic(level, seed), LevelGenerator.arcade(level, seed))) {
                    val board = Board(spec.cols, spec.rows)
                    board.loadLayout(spec)
                    val (sx, sy) = board.findSource()
                    val dir = board.piece(sx, sy)!!.endPort!!
                    val fx = sx + dir.dx
                    val fy = sy + dir.dy
                    assertTrue(board.inBounds(fx, fy), "${spec.id}/$seed : la source regarde hors du plateau")
                    assertEquals(null, board.piece(fx, fy), "${spec.id}/$seed : la source est bouchée")
                }
            }
        }
    }

    @Test
    fun `la generation est deterministe`() {
        for (level in 1..10) {
            assertEquals(LevelGenerator.classic(level, 77).layout, LevelGenerator.classic(level, 77).layout)
        }
        assertEquals(LevelGenerator.daily(20261105).layout, LevelGenerator.daily(20261105).layout)
        assertTrue(LevelGenerator.daily(20261105).layout != LevelGenerator.daily(20261106).layout)
    }

    @Test
    fun `la difficulte classique augmente`() {
        var prev = LevelGenerator.classicDifficulty(1)
        for (n in 2..20) {
            val d = LevelGenerator.classicDifficulty(n)
            assertTrue(d.target >= prev.target)
            assertTrue(d.countdown <= prev.countdown)
            assertTrue(d.secondsPerCell <= prev.secondsPerCell)
            prev = d
        }
    }

    /** Chaque niveau de la campagne doit être réussi par un joueur raisonnable. */
    @Test
    fun `chaque niveau de campagne est faisable`() {
        val failures = ArrayList<String>()
        for (n in 1..Campaign.size) {
            val spec = Campaign.level(n)
            val greedy = play(spec, thinkTime = 0.75f)
            val planned = play(spec, thinkTime = 0.75f, planning = true)
            println("niveau $n : glouton ${greedy.filled}/${spec.target}, planifié ${planned.filled}/${spec.target}")
            if (!greedy.success && !planned.success) {
                failures += "niveau $n « ${spec.title} » : ${greedy.filled} et ${planned.filled} sur ${spec.target}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * L'équilibrage du mode Classique : un bon joueur doit franchir les premières
     * manches presque à coup sûr ; la difficulté monte ensuite. Les taux mesurés
     * sont affichés pour le réglage.
     */
    @Test
    fun `equilibrage du mode classique`() {
        val rates = (1..12).map { level ->
            val wins = (0L until 40L).count { seed ->
                val spec = LevelGenerator.classic(level, seed)
                play(spec, 0.7f).success || play(spec, 0.7f, planning = true).success
            }
            level to wins / 40f
        }
        println("Classique, taux de réussite du robot : " + rates.joinToString { "${it.first}=${(it.second * 100).toInt()}%" })
        for ((level, rate) in rates.take(3)) assertTrue(rate >= 0.8f, "manche $level trop dure : $rate")
    }

    @Test
    fun `equilibrage du mode arcade`() {
        val rates = (1..10).map { level ->
            val wins = (0L until 30L).count { seed ->
                val spec = LevelGenerator.arcade(level, seed)
                play(spec, 0.6f).success || play(spec, 0.6f, planning = true).success
            }
            level to wins / 30f
        }
        println("Arcade, taux de réussite du robot : " + rates.joinToString { "${it.first}=${(it.second * 100).toInt()}%" })
        for ((level, rate) in rates.take(3)) assertTrue(rate >= 0.8f, "vague $level trop dure : $rate")
    }

    @Test
    fun `les defis du jour sont faisables`() {
        var wins = 0
        val days = (1..28).map { 20261100 + it }
        for (day in days) {
            val spec = LevelGenerator.daily(day)
            if (play(spec, 0.7f).success || play(spec, 0.7f, planning = true).success) wins++
        }
        println("Défis du jour réussis par le robot : $wins/${days.size}")
        assertTrue(wins >= days.size / 2)
    }

    @Test
    fun `une partie classique avance jusqu'a l'echec`() {
        val run = Run(GameMode.CLASSIC, seed = 5)
        val win = RoundResult(true, 10, 10, false, 500, 0, 0, 0, 0, 0, 500, 0, 0, 10, 30f)
        assertEquals(RunOutcome.NEXT, run.record(win))
        assertEquals(2, run.level)
        assertEquals(RunOutcome.GAME_OVER, run.record(win.copy(success = false, score = 100)))
        assertEquals(600, run.totalScore)
    }

    @Test
    fun `l'arcade donne trois vies`() {
        val run = Run(GameMode.ARCADE, seed = 5)
        val fail = RoundResult(false, 3, 10, false, 150, 0, 0, 0, 0, 0, 150, 0, 0, 10, 30f)
        assertEquals(RunOutcome.RETRY, run.record(fail))
        assertEquals(RunOutcome.RETRY, run.record(fail))
        assertEquals(RunOutcome.GAME_OVER, run.record(fail))
        assertEquals(1, run.level)
    }
}
