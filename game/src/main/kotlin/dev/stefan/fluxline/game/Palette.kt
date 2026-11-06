package dev.stefan.fluxline.game

import com.badlogic.gdx.graphics.Color

/**
 * La palette de FLUXLINE. Trois couleurs portent le sens :
 * cyan = le fluide et ce qui est vivant ; ambre = ce qu'on manipule ;
 * magenta = le danger (fuite, échec). Le reste est en bleu nuit.
 */
object Palette {
    val CYAN = Color(0.30f, 0.92f, 1.00f, 1f)
    val CYAN_DEEP = Color(0.02f, 0.30f, 0.85f, 1f)
    val CYAN_BRIGHT = Color(0.30f, 0.96f, 1.00f, 1f)
    val AMBER = Color(1.00f, 0.70f, 0.22f, 1f)
    val AMBER_SOFT = Color(1.00f, 0.82f, 0.52f, 1f)
    val MAGENTA = Color(1.00f, 0.25f, 0.55f, 1f)
    val GREEN = Color(0.45f, 1.00f, 0.60f, 1f)
    val TEXT = Color(0.92f, 0.95f, 1.00f, 1f)
    val TEXT_DIM = Color(0.60f, 0.68f, 0.80f, 1f)
    val TEXT_FAINT = Color(0.38f, 0.45f, 0.58f, 1f)
    val NIGHT = Color(0.02f, 0.035f, 0.08f, 1f)
    val SHADE = Color(0.01f, 0.015f, 0.04f, 1f)

    /** Couleur temporaire réutilisable (évite les allocations dans la boucle de rendu). */
    val tmp = Color()

    fun tmp(c: Color, alpha: Float): Color = tmp.set(c.r, c.g, c.b, c.a * alpha)
}
