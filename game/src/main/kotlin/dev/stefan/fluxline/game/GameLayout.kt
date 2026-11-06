package dev.stefan.fluxline.game

import com.badlogic.gdx.math.Rectangle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * La disposition de l'écran de jeu, calculée une fois par changement de taille.
 * Pure (aucun appel graphique) : testée sur la JVM pour tous les formats.
 *
 * En portrait (le cas normal) : barre du haut, jauge, plateau au centre, et en
 * bas — sous le pouce — le distributeur et le bouton d'accélération. Les zones
 * sûres (encoche, barre de gestes) sont respectées. Un format paysage (fenêtre
 * PC, multi-fenêtre) place l'interface dans une colonne à droite.
 *
 * Coordonnées en pixels, origine en bas à gauche.
 */
class GameLayout {
    var width = 0f
        private set
    var height = 0f
        private set
    /** Échelle d'interface : 1 sur un écran de référence 720 × 1280. */
    var scale = 1f
        private set
    var landscape = false
        private set

    val board = Rectangle()
    var cell = 0f
        private set
    var cols = 7
        private set
    var rows = 10
        private set

    val pause = Rectangle()
    val title = Rectangle()
    val score = Rectangle()
    val gauge = Rectangle()
    val rail = Rectangle()
    val next = Rectangle()
    val upcoming = Array(4) { Rectangle() }
    val rotate = Rectangle()
    val fast = Rectangle()
    var hasRotate = false
        private set

    fun compute(
        w: Float,
        h: Float,
        insetTop: Float,
        insetBottom: Float,
        insetLeft: Float,
        insetRight: Float,
        cols: Int,
        rows: Int,
        rotation: Boolean,
    ) {
        width = w
        height = h
        this.cols = cols
        this.rows = rows
        hasRotate = rotation
        scale = uiScale(w, h)
        landscape = w > h * 0.85f
        if (landscape) computeLandscape(insetTop, insetBottom, insetLeft, insetRight) else computePortrait(insetTop, insetBottom, insetLeft, insetRight)
    }

    private fun computePortrait(iTop: Float, iBottom: Float, iLeft: Float, iRight: Float) {
        val u = scale
        val margin = 14f * u
        val left = iLeft + margin
        val right = width - iRight - margin
        val top = height - iTop - 10f * u

        // Barre du haut : pause, titre, score.
        val barH = 78f * u
        pause.set(left, top - barH, barH, barH)
        score.set(right - 230f * u, top - barH, 230f * u, barH)
        title.set(pause.x + pause.width + 12f * u, top - barH, score.x - pause.x - pause.width - 24f * u, barH)
        val gaugeH = 50f * u
        gauge.set(left, pause.y - 10f * u - gaugeH, right - left, gaugeH)

        // Distributeur en bas (zone du pouce), au-dessus de la barre de gestes.
        val railH = 150f * u
        val bottom = iBottom + max(16f * u, 0f)
        rail.set(left, bottom, right - left, railH)

        // Plateau : la plus grande case qui tient entre les deux.
        val availTop = gauge.y - 14f * u
        val availBottom = rail.y + rail.height + 16f * u
        val availH = availTop - availBottom
        val availW = width - iLeft - iRight - 2f * 10f * u
        cell = floor(min(availW / cols, availH / rows))
        val bw = cell * cols
        val bh = cell * rows
        val bx = floor(iLeft + (width - iLeft - iRight - bw) / 2f)
        val by = floor(availBottom + (availH - bh) / 2f)
        board.set(bx, by, bw, bh)

        layoutRail()
    }

    private fun computeLandscape(iTop: Float, iBottom: Float, iLeft: Float, iRight: Float) {
        val u = scale
        val margin = 16f * u
        val availH = height - iTop - iBottom - 2 * margin
        val sideW = max(330f * u, width * 0.30f)
        val availW = width - iLeft - iRight - sideW - 3 * margin
        cell = floor(min(availW / cols, availH / rows))
        val bw = cell * cols
        val bh = cell * rows
        board.set(floor(iLeft + margin + (availW - bw) / 2f), floor(iBottom + margin + (availH - bh) / 2f), bw, bh)
        val sx = board.x + bw + 2 * margin
        val sw = width - iRight - margin - sx
        val top = height - iTop - margin
        val barH = 78f * u
        pause.set(sx, top - barH, barH, barH)
        score.set(sx + barH + 10f * u, top - barH, sw - barH - 10f * u, barH)
        title.set(sx, pause.y - 60f * u, sw, 50f * u)
        gauge.set(sx, title.y - 60f * u, sw, 50f * u)
        rail.set(sx, iBottom + margin, sw, min(170f * u, gauge.y - iBottom - 2 * margin))
        layoutRail()
    }

    /**
     * Le rail : [rotation] [prochaine pièce, grande] [4 suivantes] … [accélérer].
     * La prochaine pièce est la plus grande cible ; l'accélération est isolée à
     * droite pour ne pas être touchée par mégarde.
     */
    private fun layoutRail() {
        val u = scale
        val r = rail
        val fastW = min(r.height * 0.86f, 132f * u)
        fast.set(r.x + r.width - fastW, r.y + (r.height - fastW) / 2f, fastW, fastW)
        var x = r.x
        if (hasRotate) {
            val rw = min(r.height * 0.62f, 96f * u)
            rotate.set(x, r.y + (r.height - rw) / 2f, rw, rw)
            x += rw + 12f * u
        } else {
            rotate.set(0f, 0f, 0f, 0f)
        }
        // La prochaine pièce, avec son libellé dessous (26 u réservés).
        val label = 26f * u
        val nextSize = min(r.height - label - 8f * u, max(cell * 1.05f, 104f * u))
        next.set(x, r.y + label + (r.height - label - nextSize) / 2f, nextSize, nextSize)
        x += nextSize + 14f * u
        val spaceEnd = fast.x - 18f * u
        val gap = 12f * u
        val slot = min(nextSize * 0.62f, (spaceEnd - x - gap * 3) / 4f)
        for (i in 0 until 4) {
            upcoming[i].set(x + i * (slot + gap), next.y + (nextSize - slot) / 2f, slot, slot)
        }
    }

    fun cellX(x: Int): Float = board.x + x * cell

    /** Bas de la case de la ligne [y] (la ligne 0 est en haut). */
    fun cellY(y: Int): Float = board.y + (rows - 1 - y) * cell

    /** Colonne sous le point (px, py), ou -1. */
    fun colAt(px: Float): Int {
        val c = floor((px - board.x) / cell).toInt()
        return if (c in 0 until cols) c else -1
    }

    fun rowAt(py: Float): Int {
        val r = rows - 1 - floor((py - board.y) / cell).toInt()
        return if (r in 0 until rows) r else -1
    }

    companion object {
        fun uiScale(w: Float, h: Float): Float {
            val portrait = h >= w
            return if (portrait) min(w / 720f, h / 1280f) else min(w / 1280f, h / 720f) * 1.05f
        }
    }
}
