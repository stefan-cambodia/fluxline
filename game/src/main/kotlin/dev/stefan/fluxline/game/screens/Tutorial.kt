package dev.stefan.fluxline.game.screens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Align
import dev.stefan.fluxline.core.GameSession
import dev.stefan.fluxline.core.Phase
import dev.stefan.fluxline.core.TrailBuffer
import dev.stefan.fluxline.core.TrailEnd
import dev.stefan.fluxline.game.Ease
import dev.stefan.fluxline.game.GameLayout
import dev.stefan.fluxline.game.Palette
import dev.stefan.fluxline.game.i18n.Strings
import dev.stefan.fluxline.game.ui.UiContext
import kotlin.math.min

/**
 * Le tutoriel du niveau 1, au premier lancement : des consignes courtes et une
 * main animée qui montre où toucher.
 *
 *  1. ROTATE — la prochaine pièce ne se raccorde pas : la tourner (distributeur).
 *  2. PLACE  — la poser sur la case devant la source.
 *  3. EXTEND — le compte à rebours reprend : prolonger jusqu'à l'objectif.
 *     La première pièce qui ne se raccorde pas au bout du tuyau ramène ROTATE,
 *     si la rotation n'a pas encore été montrée.
 *  4. LAUNCH — objectif atteint : maintenir « Lancer ».
 *
 * Tant qu'aucune pièce n'est raccordée à la source, le compte à rebours est suspendu. Chaque étape se
 * déduit de l'état du plateau : une partie reprise retrouve la bonne, et il n'y
 * a rien à sauvegarder hormis « tutoriel terminé ».
 */
internal class Tutorial {
    enum class Step { ROTATE, PLACE, EXTEND, LAUNCH, DONE }

    var step = Step.PLACE
        private set
    private var stepTime = 0f
    /** Une pièce a déjà été raccordée à la source : on ne revient plus aux premières étapes. */
    private var connected = false
    /** La consigne de rotation a été montrée assez longtemps pour être lue. */
    private var rotationTaught = false
    /** Temps cumulé passé dans EXTEND : sa consigne ne s'affiche qu'une fois. */
    private var extendTime = 0f
    private val trail = TrailBuffer()

    /** Carte de la consigne, calculée par [layoutCard]. */
    val card = Rectangle()
    private var text: String? = null
    private var textFor: Step? = null
    private var textH = 0f

    val holdsCountdown: Boolean get() = !connected && (step == Step.ROTATE || step == Step.PLACE)
    val done: Boolean get() = step == Step.DONE

    /** La carte est-elle affichée (la consigne d'EXTEND disparaît après quelques secondes) ? */
    val showsCard: Boolean get() = text != null && cardAlpha() > 0f

    fun update(session: GameSession, dt: Float) {
        stepTime += dt
        if (step == Step.EXTEND) extendTime += dt
        val next = if (session.phase != Phase.COUNTDOWN) {
            Step.DONE
        } else {
            session.trace(trail)
            if (trail.length > 0) connected = true
            val fits = nextPieceFits(session)
            when {
                !connected -> if (fits) Step.PLACE else Step.ROTATE
                session.filled + trail.length >= session.spec.target -> Step.LAUNCH
                !rotationTaught && !fits && trail.end == TrailEnd.EMPTY_CELL -> Step.ROTATE
                else -> Step.EXTEND
            }
        }
        if (next != step) {
            if (step == Step.ROTATE && stepTime > 0.8f) rotationTaught = true
            step = next
            stepTime = 0f
        }
    }

    /** La prochaine pièce se raccorde-t-elle à la case où le réseau s'arrête ? */
    private fun nextPieceFits(session: GameSession): Boolean {
        if (trail.end != TrailEnd.EMPTY_CELL && trail.end != TrailEnd.BLOCKED) return true
        val piece = session.queue.next ?: return true
        val port = trail.port ?: return true
        return !session.spec.allowRotation || piece.channelAccepting(port) >= 0
    }

    /** À appeler quand la langue ou la taille de l'écran change. */
    fun invalidate() {
        textFor = null
    }

    /** Place la carte loin de la zone à montrer, et met le texte en cache (aucune allocation par image). */
    fun layoutCard(ui: UiContext, layout: GameLayout, tr: Strings, u: Float, width: Float) {
        if (textFor != step) {
            textFor = step
            text = when (step) {
                Step.ROTATE -> tr["tutorial.rotate"]
                Step.PLACE -> tr["tutorial.place"]
                Step.EXTEND -> tr["tutorial.extend"]
                Step.LAUNCH -> tr.format("tutorial.launch", "launch" to tr["hud.launch"])
                Step.DONE -> null
            }
            text?.let {
                ui.layout.setText(ui.assets.fonts.small, it, Palette.TEXT, cardWidth(layout, u, width) - PAD_LEFT * u - PAD_RIGHT * u, Align.left, true)
                textH = ui.layout.height
            }
        }
        val w = cardWidth(layout, u, width)
        val h = textH + 2 * PAD_V * u
        val board = layout.board
        // La carte se met dans la moitié du plateau opposée au bout du réseau (là où l'on construit).
        val focusInTopHalf = layout.cellY(trail.y) + layout.cell / 2 > board.y + board.height / 2
        val y = if (focusInTopHalf) board.y + 20f * u else board.y + board.height - h - 20f * u
        card.set(width / 2 - w / 2, y, w, h)
    }

    private fun cardWidth(layout: GameLayout, u: Float, width: Float) = min(min(width * 0.92f, layout.board.width - 16f * u), 680f * u)

    private fun cardAlpha(): Float {
        val fadeIn = min(1f, stepTime / 0.3f)
        if (step != Step.EXTEND) return fadeIn
        return fadeIn * (1f - ((extendTime - (EXTEND_TIME - 0.5f)) / 0.5f).coerceIn(0f, 1f))
    }

    fun draw(ui: UiContext, layout: GameLayout, u: Float) {
        val a = cardAlpha()
        if (a <= 0f) return
        when (step) {
            Step.PLACE -> {
                val x = layout.cellX(trail.x)
                val y = layout.cellY(trail.y)
                highlight(ui, x, y, layout.cell, layout.cell, u, a)
                hand(ui, x + layout.cell / 2, y + layout.cell / 2, layout.cell, u, a, hold = false)
            }
            Step.ROTATE -> {
                val r = layout.next
                highlight(ui, r.x, r.y, r.width, r.height, u, a)
                // Le doigt dans le coin bas-droit : la pièce à tourner reste visible.
                hand(ui, r.x + r.width * 0.74f, r.y + r.height * 0.26f, layout.cell, u, a, hold = false)
            }
            Step.LAUNCH -> {
                val r = layout.fast
                hand(ui, r.x + r.width / 2, r.y + r.height / 2, layout.cell, u, a, hold = true)
            }
            else -> Unit
        }
        val t = text ?: return
        ui.patch(ui.assets.panel, card.x, card.y, card.width, card.height, Color.WHITE, a * 0.96f)
        ui.text(ui.assets.fonts.small, t, card.x + PAD_LEFT * u, card.y + card.height - PAD_V * u, Palette.TEXT, a, Align.left, card.width - PAD_LEFT * u - PAD_RIGHT * u, wrap = true)
    }

    /** Cadre cyan qui pulse autour de la zone à toucher. */
    private fun highlight(ui: UiContext, x: Float, y: Float, w: Float, h: Float, u: Float, a: Float) {
        val p = Ease.pulse(stepTime, 1.1f)
        ui.rect(x, y, w, h, Palette.CYAN, a * (0.10f + 0.12f * p))
        val t = 4f * u
        val ea = a * (0.55f + 0.45f * p)
        ui.rect(x, y, w, t, Palette.CYAN, ea)
        ui.rect(x, y + h - t, w, t, Palette.CYAN, ea)
        ui.rect(x, y, t, h, Palette.CYAN, ea)
        ui.rect(x + w - t, y, t, h, Palette.CYAN, ea)
    }

    /**
     * La main : le bout de l'index sur (tx, ty). Un toucher toutes les 1,2 s avec
     * une onde ; ou un appui maintenu (« Lancer » se maintient).
     */
    private fun hand(ui: UiContext, tx: Float, ty: Float, cell: Float, u: Float, a: Float, hold: Boolean) {
        val period = if (hold) 1.8f else 1.2f
        val c = stepTime % period
        val press = if (hold) {
            when {
                c < 0.15f -> c / 0.15f
                c < 1.15f -> 1f
                c < 1.3f -> 1f - (c - 1.15f) / 0.15f
                else -> 0f
            }
        } else {
            when {
                c < 0.15f -> c / 0.15f
                c < 0.3f -> 1f - (c - 0.15f) / 0.15f
                else -> 0f
            }
        }
        // Onde partant du point touché.
        val waveT = if (hold) (c / 1.15f).coerceAtMost(1f) else (c / 0.8f).coerceAtMost(1f)
        if (waveT < 1f) {
            val size = cell * (0.35f + 0.75f * Ease.outCubic(waveT))
            ui.batch.setColor(Palette.CYAN.r, Palette.CYAN.g, Palette.CYAN.b, a * (1f - waveT) * 0.9f)
            ui.batch.draw(ui.assets.ring, tx - size / 2, ty - size / 2, size, size)
            ui.batch.setColor(1f, 1f, 1f, 1f)
        }
        val size = maxOf(cell * 0.95f, 92f * u) * (1f - 0.06f * press)
        // Le bout de l'index est à (44, 6) sur la grille de 96 de l'icône.
        val cx = tx + size * (4f / 96f)
        val cy = ty - size * (42f / 96f) + (1f - press) * 14f * u
        val icon = ui.assets.icon("hand")
        ui.icon(icon, cx + 5f * u, cy - 6f * u, size, Palette.SHADE, a * 0.7f)
        ui.icon(icon, cx, cy, size, Palette.TEXT, a)
    }

    companion object {
        const val EXTEND_TIME = 6f
        const val PAD_LEFT = 36f
        const val PAD_RIGHT = 76f
        const val PAD_V = 30f
    }
}
