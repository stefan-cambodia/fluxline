package dev.stefan.fluxline.core

import dev.stefan.fluxline.core.TestLevels.drainEvents
import dev.stefan.fluxline.core.TestLevels.force
import dev.stefan.fluxline.core.TestLevels.run
import dev.stefan.fluxline.core.TestLevels.spec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameSessionTest {

    private fun line() = spec(
        ".....",
        ">....",
        ".....",
        target = 3,
    )

    @Test
    fun `le fluide attend la fin du compte a rebours`() {
        val s = GameSession(line())
        s.run(1.9f)
        assertEquals(Phase.COUNTDOWN, s.phase)
        s.run(0.2f)
        assertEquals(Phase.FLOWING, s.phase)
    }

    @Test
    fun `le compte a rebours suspendu n'empeche ni de poser ni de tourner`() {
        val s = GameSession(spec(".....", ">....", ".....", target = 3, rotation = true))
        s.holdCountdown = true
        s.run(10f)
        assertEquals(Phase.COUNTDOWN, s.phase)
        assertEquals(2f, s.countdownLeft, 1e-4f)
        assertTrue(s.rotateNext())
        assertTrue(s.place(1, 1))
        s.holdCountdown = false
        s.run(2.1f)
        assertEquals(Phase.FLOWING, s.phase)
    }

    @Test
    fun `le fluide suit une ligne droite puis fuit dans la case vide`() {
        val s = GameSession(line())
        for (x in 1..3) s.force(x, 1, Piece.STRAIGHT_H)
        s.run(30f)
        val r = s.result!!
        assertTrue(r.success)
        assertEquals(3, r.filled)
        assertEquals(3 * Scoring.PIPE, r.score)
        val leak = s.drainEvents().filterIsInstance<GameEvent.Leaked>().single()
        assertEquals(GameEvent.Leaked(3, 1, Direction.E), leak)
    }

    @Test
    fun `le temps de parcours suit la vitesse du niveau`() {
        val s = GameSession(line())
        for (x in 1..3) s.force(x, 1, Piece.STRAIGHT_H)
        // Départ à 2 s, source (0,6 case) à 2,6 s, puis une seconde par tuyau.
        s.run(3.55f)
        assertEquals(0, s.filled)
        s.run(0.1f)
        assertEquals(1, s.filled)
        s.run(1.0f)
        assertEquals(2, s.filled)
    }

    @Test
    fun `manche ratee si l'objectif n'est pas atteint`() {
        val s = GameSession(line())
        s.force(1, 1, Piece.STRAIGHT_H)
        s.run(30f)
        assertFalse(s.result!!.success)
        assertEquals(1, s.result!!.filled)
    }

    @Test
    fun `le fluide fuit au bord du plateau`() {
        val s = GameSession(line())
        for (x in 1..4) s.force(x, 1, Piece.STRAIGHT_H)
        s.run(30f)
        assertEquals(4, s.result!!.filled)
        assertEquals(GameEvent.Leaked(4, 1, Direction.E), s.drainEvents().filterIsInstance<GameEvent.Leaked>().single())
    }

    @Test
    fun `le fluide fuit contre une piece incompatible ou un bloc`() {
        val s1 = GameSession(line())
        s1.force(1, 1, Piece.STRAIGHT_H)
        s1.force(2, 1, Piece.STRAIGHT_V)
        s1.run(30f)
        assertEquals(1, s1.result!!.filled)
        assertEquals(GameEvent.Leaked(1, 1, Direction.E), s1.drainEvents().filterIsInstance<GameEvent.Leaked>().single())

        val s2 = GameSession(spec(".....", ">#...", ".....", target = 1))
        s2.run(30f)
        assertEquals(0, s2.result!!.filled)
        assertFalse(s2.result!!.success)
    }

    @Test
    fun `les coudes font tourner le fluide`() {
        val s = GameSession(spec(".....", ">7...", ".L-..", target = 3))
        s.run(30f)
        // (1,1) entre par l'ouest, sort au sud ; (1,2) entre au nord, sort à l'est ; (2,2) droit.
        assertEquals(3, s.result!!.filled)
        val entered = s.drainEvents().filterIsInstance<GameEvent.ChannelEntered>().map { Triple(it.x, it.y, it.entry) }
        assertEquals(
            listOf(Triple(0, 1, null), Triple(1, 1, Direction.W), Triple(1, 2, Direction.N), Triple(2, 2, Direction.W)),
            entered,
        )
    }

    @Test
    fun `traverser une croix dans les deux sens rapporte le bonus`() {
        // Boucle : croix (1,1) traversée à l'horizontale puis à la verticale.
        val s = GameSession(
            spec(
                ".....",
                ">+7..",
                ".LJ..",
                target = 5,
            ),
        )
        s.run(30f)
        val r = s.result!!
        assertEquals(5, r.filled)
        assertEquals(1, r.crossings)
        assertEquals(5 * Scoring.PIPE + Scoring.CROSS_BONUS, r.score)
        assertEquals(GameEvent.Leaked(1, 1, Direction.N), s.drainEvents().filterIsInstance<GameEvent.Leaked>().single())
    }

    @Test
    fun `un port sans vis-a-vis fait fuir meme au bout d'une boucle`() {
        // (1,1) droit → (2,1) J → (2,0) 7 → (1,0) F, qui redescend vers (1,1) par le
        // nord : le droit horizontal n'a pas de port nord, le fluide fuit.
        val s = GameSession(
            spec(
                ".F7..",
                ">-J..",
                ".....",
                target = 1,
            ),
        )
        s.run(30f)
        assertEquals(4, s.result!!.filled)
        assertEquals(GameEvent.Leaked(1, 0, Direction.S), s.drainEvents().filterIsInstance<GameEvent.Leaked>().single())
    }

    @Test
    fun `un conduit deja rempli arrete le fluide`() {
        val s = GameSession(spec(".....", ">-+..", ".....", target = 1))
        s.board.completeChannel(2, 1, 0)
        s.run(30f)
        assertEquals(1, s.result!!.filled)
        assertEquals(GameEvent.Leaked(1, 1, Direction.E), s.drainEvents().filterIsInstance<GameEvent.Leaked>().single())
    }

    @Test
    fun `excedent d'objectif paye double`() {
        val s = GameSession(spec(".....", ">....", ".....", target = 2))
        for (x in 1..4) s.force(x, 1, Piece.STRAIGHT_H)
        s.run(30f)
        assertEquals(2 * Scoring.PIPE + 2 * Scoring.PIPE_OVER_TARGET, s.result!!.score)
    }

    @Test
    fun `poser consomme la prochaine piece et fait avancer la file`() {
        val s = GameSession(line())
        val first = s.queue.next!!
        val second = s.queue[1]!!
        assertTrue(s.place(2, 0))
        assertEquals(first, s.board.piece(2, 0))
        assertEquals(second, s.queue.next)
        val placed = s.drainEvents().filterIsInstance<GameEvent.Placed>().single()
        assertEquals(GameEvent.Placed(2, 0, first, null), placed)
    }

    @Test
    fun `remplacer coute des points et bloque brievement la pose`() {
        val s = GameSession(line())
        s.place(2, 0)
        assertTrue(s.wouldReplace(2, 0))
        assertTrue(s.place(2, 0))
        assertEquals(Refusal.COOLDOWN, s.refusalAt(3, 0))
        assertFalse(s.place(3, 0))
        s.run(Scoring.REPLACE_COOLDOWN + 0.02f)
        assertNull(s.refusalAt(3, 0))
        s.drainEvents()
        // Sans aucun tuyau devant la source, la manche est ratée ; les trois pièces
        // posées n'ont jamais servi.
        s.run(60f)
        val r = s.result!!
        assertEquals(1, r.replacements)
        assertEquals(Scoring.REPLACE_PENALTY, r.replacePenalty)
        assertEquals(1, r.unusedPieces)
    }

    @Test
    fun `on ne pose ni sur une piece du niveau ni la ou le fluide est passe`() {
        val s = GameSession(line())
        assertEquals(Refusal.FIXED, s.refusalAt(0, 1))
        s.force(1, 1, Piece.STRAIGHT_H)
        s.force(2, 1, Piece.STRAIGHT_H)
        s.run(3.1f) // le fluide est dans (1,1)
        assertEquals(Refusal.FLUID, s.refusalAt(1, 1))
        assertNull(s.refusalAt(2, 1))
        assertFalse(s.place(1, 1))
        assertEquals(Refusal.FLUID, s.drainEvents().filterIsInstance<GameEvent.Refused>().single().reason)
    }

    @Test
    fun `accelerer lache le fluide et double les points`() {
        val s = GameSession(spec(".....", ">....", ".....", target = 3, countdown = 20f))
        for (x in 1..3) s.force(x, 1, Piece.STRAIGHT_H)
        s.accelerate()
        assertEquals(Phase.FLOWING, s.phase)
        s.run(2f) // 9 fois plus vite : bien assez pour 4 cases
        val r = s.result!!
        assertEquals(3 * Scoring.PIPE * Scoring.FAST_MULTIPLIER, r.score)
    }

    @Test
    fun `l'arrivee termine la manche avec un bonus`() {
        val s = GameSession(spec(".....", ">--w.", ".....", target = 2))
        s.run(30f)
        val r = s.result!!
        assertTrue(r.success)
        assertTrue(r.drainReached)
        assertEquals(2 * Scoring.PIPE + Scoring.DRAIN_BONUS, r.score)
        assertTrue(s.drainEvents().any { it is GameEvent.DrainReached })
    }

    @Test
    fun `si l'arrivee est exigee une fuite est un echec`() {
        val s = GameSession(spec(".....", ">--..", "...n.", target = 2, requireDrain = true))
        s.run(30f)
        assertEquals(2, s.result!!.filled)
        assertFalse(s.result!!.success)
    }

    @Test
    fun `un reservoir est trois fois plus lent et rapporte plus`() {
        val s = GameSession(spec(".....", ">H...", ".....", target = 1))
        s.run(2.6f + 2.9f)
        assertEquals(0, s.filled)
        s.run(0.2f)
        assertEquals(1, s.filled)
        s.run(10f)
        assertEquals(Scoring.RESERVOIR, s.result!!.score)
    }

    @Test
    fun `un sens unique a contre-courant fait fuir`() {
        val ok = GameSession(spec(".....", ">E...", ".....", target = 1))
        ok.run(30f)
        assertEquals(1, ok.result!!.filled)
        val ko = GameSession(spec(".....", ">W...", ".....", target = 1))
        ko.run(30f)
        assertEquals(0, ko.result!!.filled)
    }

    @Test
    fun `les cinq dernieres secondes sont annoncees`() {
        val s = GameSession(spec(".....", ">....", ".....", countdown = 8f))
        s.run(8.1f)
        val ticks = s.drainEvents().filterIsInstance<GameEvent.CountdownTick>().map { it.secondsLeft }
        assertEquals(listOf(5, 4, 3, 2, 1), ticks)
    }

    @Test
    fun `les pieces posees jamais traversees sont penalisees`() {
        val s = GameSession(spec(".....", ">....", ".....", target = 1))
        s.force(1, 1, Piece.STRAIGHT_H)
        s.force(3, 0, Piece.CROSS)
        s.force(4, 2, Piece.CROSS)
        s.run(30f)
        val r = s.result!!
        assertEquals(2, r.unusedPieces)
        assertEquals(2 * Scoring.UNUSED_PENALTY, r.unusedPenalty)
        assertEquals(0, r.score) // jamais négatif
    }

    @Test
    fun `la rotation n'est permise que si le niveau l'autorise`() {
        val fixed = GameSession(line())
        assertFalse(fixed.rotateNext())
        val free = GameSession(spec(".....", ">....", ".....", rotation = true))
        val before = free.queue.next!!
        assertTrue(free.rotateNext())
        assertEquals(before.rotatedClockwise(), free.queue.next)
    }

    @Test
    fun `le trace suit le reseau pose en aval`() {
        val s = GameSession(spec(".....", ">-7..", ".....", target = 1))
        val t = s.trail()
        assertEquals(TrailEnd.EMPTY_CELL, t.end)
        assertEquals(2, t.length)
        assertEquals(2 to 2, t.x to t.y)
        assertEquals(Direction.N, t.port)
    }

    @Test
    fun `les etoiles recompensent le depassement`() {
        fun stars(filled: Int, target: Int, drain: Boolean = false) = RoundResult(
            true, filled, target, drain, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0f,
        ).stars
        assertEquals(1, stars(10, 10))
        assertEquals(2, stars(13, 10))
        assertEquals(3, stars(15, 10))
        assertEquals(2, stars(10, 10, drain = true))
    }
}
