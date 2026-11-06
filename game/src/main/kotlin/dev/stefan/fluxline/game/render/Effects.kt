package dev.stefan.fluxline.game.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import dev.stefan.fluxline.game.Assets
import dev.stefan.fluxline.game.Palette
import kotlin.math.max

/**
 * Le bloom : les éléments lumineux (plasma, étincelles) sont dessinés une
 * seconde fois dans un tampon au quart de la résolution, floutés, puis ajoutés
 * à l'image. Coût fixe et faible ; désactivable dans les options.
 */
class Bloom(private val assets: Assets) : Disposable {
    private var a: FrameBuffer? = null
    private var b: FrameBuffer? = null
    private var regionA: TextureRegion? = null
    private var regionB: TextureRegion? = null
    private var bw = 1
    private var bh = 1
    private val proj = Matrix4()
    private val saved = Matrix4()

    fun resize(width: Int, height: Int) {
        disposeBuffers()
        bw = max(1, width / 4)
        bh = max(1, height / 4)
        try {
            a = FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false)
            b = FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false)
        } catch (e: RuntimeException) {
            // Pas de FBO disponible : le jeu continue sans bloom.
            Gdx.app.error("Fluxline", "bloom indisponible", e)
            disposeBuffers()
            return
        }
        for (fb in listOf(a!!, b!!)) fb.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        regionA = TextureRegion(a!!.colorBufferTexture).also { it.flip(false, true) }
        regionB = TextureRegion(b!!.colorBufferTexture).also { it.flip(false, true) }
    }

    val available: Boolean get() = a != null

    /** Dessine [draw] (en coordonnées écran) dans le tampon de lueur. */
    fun capture(batch: SpriteBatch, draw: () -> Unit) {
        val fa = a ?: return
        fa.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        batch.begin()
        draw()
        batch.end()
        fa.end()
    }

    /** Floute puis ajoute la lueur à l'écran. */
    fun composite(batch: SpriteBatch, width: Float, height: Float, intensity: Float) {
        val fa = a ?: return
        val fb = b ?: return
        saved.set(batch.projectionMatrix)
        proj.setToOrtho2D(0f, 0f, bw.toFloat(), bh.toFloat())
        batch.projectionMatrix = proj
        batch.shader = assets.blurShader
        repeat(2) { pass ->
            val spread = 1f + pass
            fb.begin()
            Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            batch.begin()
            batch.disableBlending()
            assets.blurShader.setUniformf("u_dir", spread / bw, 0f)
            batch.draw(regionA, 0f, 0f, bw.toFloat(), bh.toFloat())
            batch.end()
            fb.end()
            fa.begin()
            batch.begin()
            assets.blurShader.setUniformf("u_dir", 0f, spread / bh)
            batch.draw(regionB, 0f, 0f, bw.toFloat(), bh.toFloat())
            batch.end()
            batch.enableBlending()
            fa.end()
        }
        batch.shader = null
        batch.projectionMatrix = saved
        batch.begin()
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE)
        batch.setColor(intensity, intensity, intensity, 1f)
        batch.draw(regionA, 0f, 0f, width, height)
        batch.setColor(1f, 1f, 1f, 1f)
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        batch.end()
    }

    private fun disposeBuffers() {
        a?.dispose()
        b?.dispose()
        a = null
        b = null
    }

    override fun dispose() = disposeBuffers()
}

/** Le fond animé (shader plein écran) et les lignes de balayage optionnelles. */
class Backdrop(private val assets: Assets) {
    fun draw(batch: SpriteBatch, width: Float, height: Float, time: Float, scale: Float) {
        val sh = assets.backgroundShader
        batch.shader = sh
        sh.setUniformf("u_resolution", width, height)
        sh.setUniformf("u_time", time)
        sh.setUniformf("u_scale", scale)
        sh.setUniformf("u_accent", Palette.CYAN.r, Palette.CYAN.g, Palette.CYAN.b)
        batch.disableBlending()
        batch.draw(assets.white, 0f, 0f, width, height)
        batch.enableBlending()
        batch.shader = null
    }

    fun vignette(batch: SpriteBatch, width: Float, height: Float, alpha: Float) {
        batch.setColor(1f, 1f, 1f, alpha)
        batch.draw(assets.vignette, -width * 0.1f, -height * 0.1f, width * 1.2f, height * 1.2f)
        batch.setColor(1f, 1f, 1f, 1f)
    }

    fun scanlines(batch: SpriteBatch, width: Float, height: Float, scale: Float) {
        val step = max(3f, 3f * scale)
        batch.setColor(0f, 0f, 0f, 0.13f)
        var y = 0f
        while (y < height) {
            batch.draw(assets.white, 0f, y, width, step * 0.45f)
            y += step
        }
        batch.setColor(1f, 1f, 1f, 1f)
    }
}
