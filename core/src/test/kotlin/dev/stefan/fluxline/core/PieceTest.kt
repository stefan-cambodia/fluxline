package dev.stefan.fluxline.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PieceTest {

    @Test
    fun `chaque piece standard a les bons ports`() {
        fun ports(p: Piece) = Direction.entries.filter { p.hasPort(it) }.toSet()
        assertEquals(setOf(Direction.W, Direction.E), ports(Piece.STRAIGHT_H))
        assertEquals(setOf(Direction.N, Direction.S), ports(Piece.STRAIGHT_V))
        assertEquals(setOf(Direction.N, Direction.E), ports(Piece.ELBOW_NE))
        assertEquals(setOf(Direction.E, Direction.S), ports(Piece.ELBOW_ES))
        assertEquals(setOf(Direction.S, Direction.W), ports(Piece.ELBOW_SW))
        assertEquals(setOf(Direction.W, Direction.N), ports(Piece.ELBOW_WN))
        assertEquals(Direction.entries.toSet(), ports(Piece.CROSS))
        assertTrue(ports(Piece.BLOCK).isEmpty())
    }

    @Test
    fun `la rotation fait tourner les ports dans le sens horaire`() {
        for (p in Piece.STANDARD_PIECES) {
            val r = p.rotatedClockwise()
            for (d in Direction.entries) {
                assertEquals(p.hasPort(d), r.hasPort(d.clockwise), "$p -> $r, port $d")
            }
        }
    }

    @Test
    fun `quatre rotations ramenent a la piece de depart`() {
        for (p in Piece.STANDARD_PIECES) {
            var r = p
            repeat(4) { r = r.rotatedClockwise() }
            assertEquals(p, r)
        }
    }

    @Test
    fun `un conduit renvoie l'autre extremite`() {
        val c = Piece.ELBOW_ES.channels[0]
        assertEquals(Direction.S, c.exitFor(Direction.E))
        assertEquals(Direction.E, c.exitFor(Direction.S))
    }

    @Test
    fun `la croix a deux conduits independants`() {
        assertEquals(0, Piece.CROSS.channelAccepting(Direction.W))
        assertEquals(0, Piece.CROSS.channelAccepting(Direction.E))
        assertEquals(1, Piece.CROSS.channelAccepting(Direction.N))
        assertEquals(1, Piece.CROSS.channelAccepting(Direction.S))
    }

    @Test
    fun `un sens unique refuse le contre-courant`() {
        // Le fluide circule vers l'est : il entre par l'ouest, jamais par l'est.
        assertEquals(0, Piece.ONE_WAY_E.channelAccepting(Direction.W))
        assertEquals(-1, Piece.ONE_WAY_E.channelAccepting(Direction.E))
        assertEquals(-1, Piece.ONE_WAY_E.channelAccepting(Direction.N))
        assertEquals(0, Piece.ONE_WAY_N.channelAccepting(Direction.S))
        assertEquals(-1, Piece.ONE_WAY_N.channelAccepting(Direction.N))
    }

    @Test
    fun `sources et arrivees n'ont qu'un port`() {
        for (d in Direction.entries) {
            val s = Piece.source(d)
            assertEquals(d, s.endPort)
            assertEquals(1, Direction.entries.count { s.hasPort(it) })
            val drain = Piece.drain(d)
            assertEquals(d, drain.endPort)
            assertEquals(0, drain.channelAccepting(d))
            assertFalse(drain.hasPort(d.opposite))
        }
    }

    @Test
    fun `chaque code de plan designe une seule piece`() {
        for (p in Piece.entries) assertEquals(p, Piece.byCode(p.code))
        assertNotNull(Piece.byCode('+'))
        assertEquals(null, Piece.byCode('.'))
    }
}
