package dev.stefan.fluxline.game.screens

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import dev.stefan.fluxline.game.FluxGame
import dev.stefan.fluxline.game.ui.WidgetGroup

/**
 * Un écran du jeu. Le jeu lui transmet la taille, le temps et le toucher (en
 * pixels, y vers le haut) ; l'écran ouvre et ferme lui-même le lot de dessin,
 * car certains ont besoin de plusieurs passes (bloom).
 */
abstract class Screen(val game: FluxGame) {
    val widgets = WidgetGroup()
    var width = 0f
        private set
    var height = 0f
        private set

    /** Temps écoulé depuis l'affichage (animations d'entrée). */
    var age = 0f
        private set

    open fun show() {}
    open fun hide() {}

    fun resize(w: Float, h: Float) {
        width = w
        height = h
        layout()
    }

    /** Place les widgets ; appelé à l'affichage et à chaque changement de taille. */
    open fun layout() {}

    open fun update(dt: Float) {
        age += dt
        widgets.update(dt)
    }

    abstract fun render(batch: SpriteBatch)

    open fun touchDown(x: Float, y: Float, pointer: Int): Boolean = widgets.touchDown(x, y, pointer, game.ui)
    open fun touchDragged(x: Float, y: Float, pointer: Int): Boolean = widgets.touchDragged(x, y, pointer, game.ui)
    open fun touchUp(x: Float, y: Float, pointer: Int): Boolean = widgets.touchUp(x, y, pointer, game.ui)

    /** Touche retour d'Android. Renvoie faux pour laisser l'application se fermer. */
    open fun back(): Boolean = false

    /** L'application passe en arrière-plan. */
    open fun pause() {}

    open fun resume() {}

    /** Entrée en fondu des écrans (0..1). */
    protected fun intro(delay: Float = 0f, duration: Float = 0.35f): Float =
        dev.stefan.fluxline.game.Ease.outCubic((age - delay) / duration)

    val u: Float get() = game.ui.scale

    /** Les textes de la langue courante. */
    val tr: dev.stefan.fluxline.game.i18n.Strings get() = game.tr
}
