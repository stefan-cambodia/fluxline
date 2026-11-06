package dev.stefan.fluxline.game.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import kotlin.math.cos
import kotlin.math.sin

/**
 * Un système de particules sans allocation : tout vit dans des tableaux de
 * taille fixe ; une particule morte libère sa place, la plus ancienne est
 * recyclée si le stock est plein. Deux familles : mélangées (éclats, gouttes)
 * et additives (étincelles, lueurs).
 */
class Particles(private val capacity: Int = 700) {
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val maxLife = FloatArray(capacity)
    private val size0 = FloatArray(capacity)
    private val size1 = FloatArray(capacity)
    private val rot = FloatArray(capacity)
    private val vrot = FloatArray(capacity)
    private val gravity = FloatArray(capacity)
    private val drag = FloatArray(capacity)
    private val r = FloatArray(capacity)
    private val g = FloatArray(capacity)
    private val b = FloatArray(capacity)
    private val a = FloatArray(capacity)
    private val additive = BooleanArray(capacity)
    private val stretch = BooleanArray(capacity)
    private val region = arrayOfNulls<TextureRegion>(capacity)
    private var cursor = 0
    private var seed = 0x2545F491

    val alive: Int
        get() {
            var n = 0
            for (i in 0 until capacity) if (life[i] > 0f) n++
            return n
        }

    fun random(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed ushr 8) / 16777216f
    }

    fun range(lo: Float, hi: Float) = lo + (hi - lo) * random()

    fun emit(
        tex: TextureRegion,
        px: Float,
        py: Float,
        velX: Float,
        velY: Float,
        lifetime: Float,
        sizeStart: Float,
        sizeEnd: Float,
        color: Color,
        add: Boolean,
        grav: Float = 0f,
        dragging: Float = 0f,
        spin: Float = 0f,
        streak: Boolean = false,
    ) {
        var i = -1
        for (k in 0 until capacity) {
            val j = (cursor + k) % capacity
            if (life[j] <= 0f) {
                i = j
                break
            }
        }
        if (i < 0) i = cursor
        cursor = (i + 1) % capacity
        x[i] = px
        y[i] = py
        vx[i] = velX
        vy[i] = velY
        life[i] = lifetime
        maxLife[i] = lifetime
        size0[i] = sizeStart
        size1[i] = sizeEnd
        rot[i] = range(0f, 360f)
        vrot[i] = spin
        gravity[i] = grav
        drag[i] = dragging
        r[i] = color.r
        g[i] = color.g
        b[i] = color.b
        a[i] = color.a
        additive[i] = add
        stretch[i] = streak
        region[i] = tex
    }

    /** Une gerbe radiale. */
    fun burst(
        tex: TextureRegion,
        px: Float,
        py: Float,
        count: Int,
        speedMin: Float,
        speedMax: Float,
        lifetime: Float,
        size: Float,
        color: Color,
        add: Boolean,
        grav: Float = 0f,
        streak: Boolean = false,
        angleFrom: Float = 0f,
        angleTo: Float = 360f,
    ) {
        for (k in 0 until count) {
            val ang = Math.toRadians(range(angleFrom, angleTo).toDouble())
            val sp = range(speedMin, speedMax)
            emit(
                tex, px, py, (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat(),
                lifetime * range(0.6f, 1.2f), size * range(0.6f, 1.2f), size * 0.2f, color, add,
                grav = grav, dragging = 2.2f, spin = range(-300f, 300f), streak = streak,
            )
        }
    }

    fun update(dt: Float) {
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            val damp = 1f / (1f + drag[i] * dt)
            vx[i] *= damp
            vy[i] = vy[i] * damp - gravity[i] * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            rot[i] += vrot[i] * dt
        }
    }

    fun clear() {
        life.fill(0f)
    }

    /** Dessine les particules d'une famille (le mode de mélange est réglé par l'appelant). */
    fun draw(batch: SpriteBatch, additivePass: Boolean) {
        for (i in 0 until capacity) {
            if (life[i] <= 0f || additive[i] != additivePass) continue
            val t = 1f - life[i] / maxLife[i]
            val s = size0[i] + (size1[i] - size0[i]) * t
            val fade = if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f
            batch.setColor(r[i], g[i], b[i], a[i] * fade)
            val tex = region[i]!!
            if (stretch[i]) {
                val speed = kotlin.math.sqrt(vx[i] * vx[i] + vy[i] * vy[i])
                val len = s * (1f + speed * 0.012f)
                val ang = Math.toDegrees(kotlin.math.atan2(vy[i].toDouble(), vx[i].toDouble())).toFloat()
                batch.draw(tex, x[i] - len / 2, y[i] - s / 4, len / 2, s / 4, len, s / 2, 1f, 1f, ang)
            } else {
                batch.draw(tex, x[i] - s / 2, y[i] - s / 2, s / 2, s / 2, s, s, 1f, 1f, rot[i])
            }
        }
        batch.setColor(1f, 1f, 1f, 1f)
    }
}
