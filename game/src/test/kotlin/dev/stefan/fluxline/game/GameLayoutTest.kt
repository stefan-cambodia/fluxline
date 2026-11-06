package dev.stefan.fluxline.game

import com.badlogic.gdx.math.Rectangle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * La disposition de l'écran de jeu sur tous les formats visés : rien ne se
 * chevauche, rien ne déborde, les zones sûres sont respectées et les cases
 * restent assez grandes pour un doigt.
 */
class GameLayoutTest {

    @ParameterizedTest(name = "{0}×{1} (dpi {2}, encoche {3}, barre {4})")
    @CsvSource(
        "1080, 1920, 420, 0, 0",     // 16:9
        "1080, 2160, 440, 60, 0",    // 18:9
        "1080, 2340, 440, 110, 66",  // 19,5:9 avec encoche et barre de gestes
        "1080, 2400, 420, 100, 63",  // 20:9
        "1440, 3200, 560, 140, 84",  // 20:9 QHD
        "720, 1600, 320, 80, 48",    // 20:9 HD+ d'entrée de gamme
        "1600, 2560, 320, 0, 0",     // tablette 10 pouces portrait
        "1200, 1920, 240, 0, 0",     // tablette 8 pouces
        "1200, 1600, 320, 0, 0",     // tablette tenue en paysage : jeu affiché en 3:4 avec bandes
        "320, 640, 160, 0, 0",       // très petit écran
        "2340, 1080, 440, 0, 0",     // paysage (multi-fenêtre)
    )
    fun `la disposition tient sur l'ecran`(w: Float, h: Float, dpi: Float, insetTop: Float, insetBottom: Float) {
        for (rotation in listOf(false, true)) {
            val l = GameLayout()
            l.compute(w, h, insetTop, insetBottom, 0f, 0f, 7, 10, rotation)
            val screen = Rectangle(0f, 0f, w, h)
            val safe = Rectangle(0f, insetBottom, w, h - insetTop - insetBottom)
            val parts = mutableListOf("plateau" to l.board, "pause" to l.pause, "score" to l.score, "jauge" to l.gauge, "rail" to l.rail, "suivante" to l.next, "accélérer" to l.fast)
            if (rotation) parts += "rotation" to l.rotate
            l.upcoming.forEachIndexed { i, r -> parts += "file$i" to r }
            for ((name, r) in parts) {
                assertTrue(contains(screen, r), "$name déborde de l'écran : $r")
                assertTrue(contains(safe, r), "$name empiète sur une zone système : $r")
            }
            // Le plateau ne chevauche aucun élément d'interface.
            for ((name, r) in parts) {
                if (name == "plateau") continue
                assertTrue(!r.overlaps(l.board), "$name chevauche le plateau")
            }
            // Le rail contient ses éléments, qui ne se chevauchent pas entre eux.
            val railParts = parts.filter { it.first.startsWith("file") || it.first in listOf("suivante", "accélérer", "rotation") }
            for (i in railParts.indices) for (j in i + 1 until railParts.size) {
                assertTrue(!railParts[i].second.overlaps(railParts[j].second), "${railParts[i].first} chevauche ${railParts[j].first}")
            }
            assertEquals(l.cell * 7, l.board.width, 0.01f)
            assertEquals(l.cell * 10, l.board.height, 0.01f)
            // Taille de case : au moins 6,5 mm (cible tactile confortable), sauf écran minuscule.
            val mm = l.cell / dpi * 25.4f
            if (w >= 720 && !l.landscape) assertTrue(mm >= 6.5f, "cases trop petites : %.1f mm".format(mm))
            // Le plateau occupe l'essentiel de l'écran.
            if (!l.landscape) assertTrue(l.board.height / h >= 0.52f, "plateau trop petit : ${l.board.height / h}")
        }
    }

    @ParameterizedTest
    @CsvSource("1080, 2340", "1600, 2560", "720, 1280")
    fun `chaque pixel du plateau correspond a la bonne case`(w: Float, h: Float) {
        val l = GameLayout()
        l.compute(w, h, 0f, 0f, 0f, 0f, 7, 10, false)
        for (y in 0 until 10) for (x in 0 until 7) {
            val cx = l.cellX(x) + l.cell / 2
            val cy = l.cellY(y) + l.cell / 2
            assertEquals(x, l.colAt(cx))
            assertEquals(y, l.rowAt(cy))
            // Les bords d'une case appartiennent à cette case (pas d'ambiguïté).
            assertEquals(x, l.colAt(l.cellX(x) + 0.5f))
            assertEquals(y, l.rowAt(l.cellY(y) + l.cell - 0.5f))
        }
        assertEquals(-1, l.colAt(l.board.x - 1f))
        assertEquals(-1, l.rowAt(l.board.y - 1f))
    }

    private fun contains(outer: Rectangle, r: Rectangle): Boolean =
        r.x >= outer.x - 0.5f && r.y >= outer.y - 0.5f && r.x + r.width <= outer.x + outer.width + 0.5f && r.y + r.height <= outer.y + outer.height + 0.5f
}
