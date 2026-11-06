package dev.stefan.fluxline.core

import dev.stefan.fluxline.core.TestLevels.drainEvents
import dev.stefan.fluxline.core.TestLevels.run
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueueAndSaveTest {

    @Test
    fun `une graine donne toujours la meme suite de pieces`() {
        val a = PieceQueue(1234)
        val b = PieceQueue(1234)
        repeat(200) { assertEquals(a.pop(), b.pop()) }
        val x = PieceQueue(1234)
        val y = PieceQueue(4321)
        assertTrue(List(50) { x.pop() } != List(50) { y.pop() })
    }

    @Test
    fun `jamais trois fois la meme piece de suite et toutes les pieces sortent`() {
        val q = PieceQueue(99)
        val seq = List(5000) { q.pop()!! }
        for (i in 2 until seq.size) {
            assertTrue(!(seq[i] == seq[i - 1] && seq[i] == seq[i - 2]), "série à l'index $i")
        }
        val counts = seq.groupingBy { it }.eachCount()
        assertEquals(Piece.STANDARD_PIECES.toSet(), counts.keys)
        // Croix plus rares que les droites.
        assertTrue(counts[Piece.CROSS]!! < counts[Piece.STRAIGHT_H]!!)
    }

    @Test
    fun `un distributeur limite se tarit`() {
        val q = PieceQueue(7, visible = 5, limit = 3)
        assertNotNull(q.pop())
        assertNotNull(q.pop())
        assertNotNull(q.pop())
        assertNull(q.next)
        assertNull(q.pop())
        assertEquals(0, q.remaining)
    }

    @Test
    fun `l'etat du distributeur se restaure a l'identique`() {
        val q = PieceQueue(55)
        repeat(7) { q.pop() }
        val copy = PieceQueue(1)
        copy.restore(q.snapshot())
        repeat(100) { assertEquals(q.pop(), copy.pop()) }
    }

    /**
     * Le test essentiel de la sauvegarde : une partie interrompue en plein
     * écoulement, encodée, décodée puis reprise, se termine exactement comme
     * celle qui n'a jamais été interrompue.
     */
    @Test
    fun `une partie sauvegardee en plein ecoulement reprend a l'identique`() {
        val run = Run(GameMode.CLASSIC, seed = 2026)
        val spec = run.currentSpec()
        val original = GameSession(spec)
        val bot = AutoPlayer(0.6f)
        // On joue jusqu'au milieu de l'écoulement : une demi-seconde après son départ,
        // quel que soit le réglage de la manche (le robot peut lancer avant la fin du compte à rebours).
        var flowing = 0f
        while (flowing < 0.5f && !original.isFinished) {
            bot.update(original, 1f / 120f)
            original.update(1f / 120f)
            if (original.phase == Phase.FLOWING) flowing += 1f / 120f
        }
        check(original.phase == Phase.FLOWING) { "le test doit couper pendant l'écoulement" }

        val text = SaveCodec.encode(SavedGame(run.state(), spec, original.capture()))
        val saved = SaveCodec.decode(text)!!
        assertEquals(run.state(), saved.run)
        val resumed = GameSession(saved.spec)
        resumed.restore(saved.session)
        original.drainEvents()

        // Les deux parties reçoivent les mêmes actions à partir d'ici.
        val botA = AutoPlayer(0.6f)
        val botB = AutoPlayer(0.6f)
        while (!original.isFinished || !resumed.isFinished) {
            botA.update(original, 1f / 120f)
            botB.update(resumed, 1f / 120f)
            original.update(1f / 120f)
            resumed.update(1f / 120f)
            assertEquals(original.filled, resumed.filled)
            assertEquals(original.score, resumed.score)
        }
        assertEquals(original.result, resumed.result)
    }

    @Test
    fun `une sauvegarde abimee est ignoree`() {
        val spec = Campaign.level(3)
        val s = GameSession(spec)
        s.place(0, 0)
        val text = SaveCodec.encode(SavedGame(Run(GameMode.LEVELS, 1, level = 3).state(), spec, s.capture()))
        assertNotNull(SaveCodec.decode(text))
        assertNull(SaveCodec.decode(null))
        assertNull(SaveCodec.decode(""))
        assertNull(SaveCodec.decode(text.replace("version=1", "version=99")))
        assertNull(SaveCodec.decode(text.lines().filterNot { it.startsWith("board.fill") }.joinToString("\n")))
        assertNull(SaveCodec.decode(text.replace("s.headX=", "s.headX=x")))
        assertNull(SaveCodec.decode(text.substring(0, text.length / 2)))
    }

    /** Android indente le XML des préférences : la sauvegarde doit survivre aux espaces parasites. */
    @Test
    fun `une sauvegarde relue avec des blancs parasites reste valide`() {
        val spec = Campaign.level(2)
        val s = GameSession(spec)
        s.place(1, 1)
        val text = SaveCodec.encode(SavedGame(Run(GameMode.LEVELS, 1, level = 2).state(), spec, s.capture()))
        assertTrue(!text.endsWith("\n"))
        assertNotNull(SaveCodec.decode(text + "\n    "))
        assertNotNull(SaveCodec.decode(text.replace("\n", "\r\n")))
        assertNotNull(SaveCodec.decode("\n  " + text.replace("\n", "\n  ") + "\n\n"))
    }

    @Test
    fun `restaurer avant l'ecoulement conserve le compte a rebours`() {
        val spec = Campaign.level(1)
        val s = GameSession(spec)
        s.run(5f)
        val r = GameSession(spec)
        r.restore(SaveCodec.decode(SaveCodec.encode(SavedGame(Run(GameMode.LEVELS, 1).state(), spec, s.capture())))!!.session)
        assertEquals(s.countdownLeft, r.countdownLeft)
        assertEquals(Phase.COUNTDOWN, r.phase)
        assertEquals(s.queue.next, r.queue.next)
    }
}
