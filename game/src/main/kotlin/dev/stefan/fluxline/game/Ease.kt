package dev.stefan.fluxline.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Courbes d'interpolation (entrée et sortie dans 0..1). */
object Ease {
    fun clamp(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

    fun outCubic(t: Float): Float {
        val u = 1f - clamp(t)
        return 1f - u * u * u
    }

    fun inOutCubic(t: Float): Float {
        val x = clamp(t)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    fun outBack(t: Float, overshoot: Float = 1.7f): Float {
        val x = clamp(t) - 1f
        return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
    }

    fun outElastic(t: Float): Float {
        val x = clamp(t)
        if (x == 0f || x == 1f) return x
        return (2.0.pow(-10.0 * x) * sin((x * 10 - 0.75) * (2 * PI / 3)) + 1).toFloat()
    }

    fun inQuad(t: Float): Float {
        val x = clamp(t)
        return x * x
    }

    fun outQuad(t: Float): Float {
        val x = clamp(t)
        return 1f - (1f - x) * (1f - x)
    }

    /** Pulsation 0..1 de période [period] secondes. */
    fun pulse(time: Float, period: Float): Float = (0.5f - 0.5f * cos(time / period * 2f * PI.toFloat()))

    /** Rapproche [current] de [target] de façon exponentielle, indépendamment du taux d'images. */
    fun approach(current: Float, target: Float, rate: Float, dt: Float): Float =
        target + (current - target) * kotlin.math.exp(-rate * dt)
}
