package dev.stefan.fluxline.game.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import dev.stefan.fluxline.core.ChannelState
import dev.stefan.fluxline.core.Direction
import dev.stefan.fluxline.core.GameSession
import dev.stefan.fluxline.core.Phase
import dev.stefan.fluxline.core.Piece
import dev.stefan.fluxline.core.PieceFamily
import dev.stefan.fluxline.game.Assets
import dev.stefan.fluxline.game.Ease
import dev.stefan.fluxline.game.GameLayout
import dev.stefan.fluxline.game.Palette
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Ce que le doigt est en train de viser (aperçu de pose). */
class Ghost {
    var active = false
    var x = -1
    var y = -1
    var piece: Piece? = null
    var valid = false
    var replacing = false
    /** Pièce tenue sous le doigt (glisser depuis le distributeur), en pixels ; NaN sinon. */
    var heldX = Float.NaN
    var heldY = Float.NaN
}

/**
 * Le rendu du plateau, couche par couche :
 *  1. cadre et logements ;  2. corps des pièces (avec ombre portée) ;
 *  3. le plasma (shader, une seule passe pour toutes les cases) ;
 *  4. les reflets du verre ;  5. voyants, repères, aperçu ;  6. particules.
 *
 * Il ne décide de rien : il lit la partie et joue les animations qu'on lui
 * signale (pose, remplissage, fuite…). Aucune allocation pendant le dessin.
 */
class BoardRenderer(private val assets: Assets) {
    val particles = Particles()
    var time = 0f
        private set

    private var cols = 0
    private var rows = 0
    private var placeAge = FloatArray(0)
    private var flash = FloatArray(0)
    private var waveOrder = IntArray(0)
    private var waveCount = 0
    private var waveClock = -1f

    private var leakX = -1
    private var leakY = -1
    private var leakPort: Direction? = null
    private var leakAge = 99f
    private var shake = 0f

    private val tmpColor = Color()

    fun reset(session: GameSession) {
        cols = session.board.cols
        rows = session.board.rows
        val n = cols * rows
        placeAge = FloatArray(n) { 99f }
        flash = FloatArray(n * 2)
        waveOrder = IntArray(n * 2)
        waveCount = 0
        waveClock = -1f
        leakX = -1
        leakPort = null
        leakAge = 99f
        shake = 0f
        particles.clear()
        val (sx, sy) = session.board.findSource()
        sourceX = sx
        sourceY = sy
    }

    /** Décalage de secousse (à appliquer à la caméra du plateau). */
    val shakeX: Float get() = if (shake > 0f) sin(time * 91f) * shake else 0f
    val shakeY: Float get() = if (shake > 0f) cos(time * 77f) * shake else 0f

    fun update(dt: Float) {
        time += dt
        for (i in placeAge.indices) if (placeAge[i] < 99f) placeAge[i] += dt
        for (i in flash.indices) if (flash[i] > 0f) flash[i] = max(0f, flash[i] - dt * 1.6f)
        leakAge += dt
        shake = max(0f, shake - dt * 30f)
        if (waveClock >= 0f) {
            val before = waveClock
            waveClock += dt * 26f
            val from = before.toInt()
            val to = min(waveCount, waveClock.toInt())
            for (k in from until to) flash[waveOrder[k]] = 1f
            if (waveClock >= waveCount + 10) waveClock = -1f
        }
        particles.update(dt)
    }

    // --- Événements -------------------------------------------------------------------

    fun onPlaced(x: Int, y: Int, replaced: Boolean, layout: GameLayout) {
        placeAge[y * cols + x] = 0f
        val cx = layout.cellX(x) + layout.cell / 2
        val cy = layout.cellY(y) + layout.cell / 2
        val c = layout.cell
        if (replaced) {
            // Éclats de céramique et de laiton, éclair bref.
            particles.burst(assets.dot, cx, cy, 16, c * 1.2f, c * 3.2f, 0.65f, c * 0.11f, Color(0.92f, 0.90f, 0.86f, 1f), add = false, grav = c * 9f)
            particles.burst(assets.dot, cx, cy, 8, c * 1.0f, c * 2.6f, 0.55f, c * 0.08f, Palette.AMBER, add = false, grav = c * 9f)
            particles.burst(assets.spark, cx, cy, 10, c * 2f, c * 5f, 0.35f, c * 0.35f, Palette.AMBER_SOFT, add = true, streak = true)
            particles.emit(assets.glow, cx, cy, 0f, 0f, 0.3f, c * 1.6f, c * 2.2f, Palette.AMBER, add = true)
            shake = c * 0.03f
        } else {
            particles.emit(assets.ring, cx, cy, 0f, 0f, 0.32f, c * 0.8f, c * 1.5f, Palette.tmp(Palette.CYAN, 0.6f), add = true)
            particles.burst(assets.dot, cx, cy - c * 0.3f, 5, c * 0.4f, c * 1.0f, 0.4f, c * 0.05f, Color(0.6f, 0.75f, 0.9f, 0.6f), add = false, grav = c * 2f, angleFrom = 200f, angleTo = 340f)
        }
    }

    fun onFilled(x: Int, y: Int, channel: Int, cross: Boolean, layout: GameLayout) {
        val k = (y * cols + x) * 2 + channel
        flash[k] = 0.7f
        if (waveCount < waveOrder.size) waveOrder[waveCount++] = k
        if (cross) {
            val cx = layout.cellX(x) + layout.cell / 2
            val cy = layout.cellY(y) + layout.cell / 2
            val c = layout.cell
            particles.burst(assets.spark, cx, cy, 22, c * 2f, c * 6f, 0.6f, c * 0.4f, Palette.CYAN_BRIGHT, add = true, streak = true)
            particles.emit(assets.glow, cx, cy, 0f, 0f, 0.5f, c * 1.2f, c * 3f, Palette.CYAN, add = true)
            flash[(y * cols + x) * 2] = 1f
            flash[(y * cols + x) * 2 + 1] = 1f
        }
    }

    fun onLeak(x: Int, y: Int, port: Direction, layout: GameLayout) {
        leakX = x
        leakY = y
        leakPort = port
        leakAge = 0f
        shake = layout.cell * 0.06f
    }

    fun onDrain(x: Int, y: Int, layout: GameLayout) {
        val cx = layout.cellX(x) + layout.cell / 2
        val cy = layout.cellY(y) + layout.cell / 2
        val c = layout.cell
        particles.emit(assets.ring, cx, cy, 0f, 0f, 0.7f, c * 0.6f, c * 2.6f, Palette.CYAN, add = true)
        particles.emit(assets.ring, cx, cy, 0f, 0f, 0.9f, c * 0.4f, c * 3.4f, Palette.AMBER, add = true)
        particles.burst(assets.spark, cx, cy, 30, c * 2f, c * 7f, 0.8f, c * 0.45f, Palette.CYAN_BRIGHT, add = true, streak = true)
    }

    /** Vague lumineuse qui parcourt tout le réseau, dans l'ordre du remplissage. */
    fun startWave() {
        waveClock = 0f
    }

    fun celebrate(layout: GameLayout) {
        val b = layout.board
        for (k in 0 until 40) {
            val px = b.x + particles.random() * b.width
            val py = b.y + particles.random() * b.height
            val c = if (k % 3 == 0) Palette.AMBER_SOFT else Palette.CYAN_BRIGHT
            particles.emit(assets.spark, px, py, particles.range(-1f, 1f) * layout.cell, layout.cell * particles.range(1f, 3f), particles.range(0.6f, 1.3f), layout.cell * 0.4f, layout.cell * 0.1f, c, add = true, streak = true, dragging = 1f)
        }
    }

    // --- Dessin ---------------------------------------------------------------------------

    private fun tile(batch: SpriteBatch, r: TextureRegion, x: Float, y: Float, cell: Float, scale: Float = 1f, rotation: Float = 0f) {
        val size = cell * CANVAS_RATIO * scale
        val cx = x + cell / 2
        val cy = y + cell / 2
        batch.draw(r, cx - size / 2, cy - size / 2, size / 2, size / 2, size, size, 1f, 1f, rotation)
    }

    /** Une pièce complète hors plateau (distributeur, pièce tenue) : corps et verre. */
    fun drawPiece(batch: SpriteBatch, piece: Piece, x: Float, y: Float, size: Float, alpha: Float = 1f, rotation: Float = 0f) {
        batch.setColor(1f, 1f, 1f, alpha)
        tile(batch, assets.body(piece), x, y, size, 1f, rotation)
        assets.glass(piece)?.let { tile(batch, it, x, y, size, 1f, rotation) }
        batch.setColor(1f, 1f, 1f, 1f)
    }

    fun drawBoard(batch: SpriteBatch, session: GameSession, layout: GameLayout, ghost: Ghost, hintEnabled: Boolean) {
        val board = session.board
        val c = layout.cell
        val u = layout.scale
        val fb = layout.board

        // 1. Cadre et logements.
        val pad = 12f * u
        assets.panel.setColor(Color.WHITE)
        assets.panel.draw(batch, fb.x - pad, fb.y - pad, fb.width + 2 * pad, fb.height + 2 * pad)
        for (y in 0 until rows) for (x in 0 until cols) tile(batch, assets.socket, layout.cellX(x), layout.cellY(y), c)

        // Lignes de visée (aident quand le doigt cache la case).
        if (ghost.active && ghost.x >= 0) {
            val col = if (ghost.valid) Palette.CYAN else Palette.MAGENTA
            batch.setColor(col.r, col.g, col.b, 0.10f)
            batch.draw(assets.white, layout.cellX(ghost.x), fb.y, c, fb.height)
            batch.draw(assets.white, fb.x, layout.cellY(ghost.y), fb.width, c)
            batch.setColor(1f, 1f, 1f, 1f)
        }

        // 2. Corps des pièces.
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = board.piece(x, y) ?: continue
            if (ghost.active && ghost.replacing && ghost.x == x && ghost.y == y) {
                batch.setColor(1f, 1f, 1f, 0.35f)
            }
            tile(batch, assets.body(p), layout.cellX(x), layout.cellY(y), c, placeScale(x, y))
            batch.setColor(1f, 1f, 1f, 1f)
        }

        // 3. Plasma.
        drawFluid(batch, session, layout, glowOnly = false)

        // 4. Verre, repères des pièces fixes.
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = board.piece(x, y) ?: continue
            val s = placeScale(x, y)
            assets.glass(p)?.let { tile(batch, it, layout.cellX(x), layout.cellY(y), c, s) }
            if (board.isFixed(x, y) && p.isStandard) tile(batch, assets.fixedMark, layout.cellX(x), layout.cellY(y), c)
        }

        // 5. Voyants de la source, prochaine case, fuite, aperçu.
        drawSourceLeds(batch, session, layout)
        if (hintEnabled && session.phase != Phase.FINISHED) drawNextHint(batch, session, layout)
        drawLeak(batch, layout)
        drawGhost(batch, layout, ghost)
    }

    private fun placeScale(x: Int, y: Int): Float {
        val age = placeAge[y * cols + x]
        if (age >= PLACE_TIME) return 1f
        return 1.22f - 0.22f * Ease.outBack(age / PLACE_TIME, 2.2f)
    }

    /** Le plasma de toutes les cases, en un seul lot (paramètres dans la couleur des sprites). */
    fun drawFluid(batch: SpriteBatch, session: GameSession, layout: GameLayout, glowOnly: Boolean) {
        val board = session.board
        val c = layout.cell
        val sh = assets.fluidShader
        batch.shader = sh
        sh.setUniformf("u_time", time)
        sh.setUniformf("u_deep", Palette.CYAN_DEEP.r, Palette.CYAN_DEEP.g, Palette.CYAN_DEEP.b)
        sh.setUniformf("u_bright", Palette.CYAN_BRIGHT.r, Palette.CYAN_BRIGHT.g, Palette.CYAN_BRIGHT.b)
        sh.setUniformf("u_glowOnly", if (glowOnly) 1f else 0f)
        for (y in 0 until rows) for (x in 0 until cols) {
            val p = board.piece(x, y) ?: continue
            for (ch in p.channels.indices) {
                val state = board.channelState(x, y, ch)
                if (state == ChannelState.EMPTY) continue
                val fill = if (state == ChannelState.FULL) 1f else board.channelFill(x, y, ch)
                val entry = board.channelEntry(x, y, ch)
                val reverse = entry != null && entry != textureStart(p, ch)
                val f = flash[(y * cols + x) * 2 + ch]
                val intensity = (0.5f + 0.5f * f).coerceAtMost(1f)
                batch.setColor(fill, if (reverse) 1f else 0f, intensity, 1f)
                tile(batch, assets.flow(p, ch), layout.cellX(x), layout.cellY(y), c, placeScale(x, y))
            }
        }
        batch.setColor(1f, 1f, 1f, 1f)
        batch.shader = null
    }

    /** Port où la carte d'écoulement commence (t = 0). */
    private fun textureStart(p: Piece, ch: Int): Direction? = when (p.family) {
        PieceFamily.ONE_WAY -> if (p.flowDirection!!.isHorizontal) Direction.W else Direction.N
        else -> p.channels[ch].a
    }

    /** Pour le bloom : plasma (cœur seulement), voyants et lueurs. */
    fun drawGlow(batch: SpriteBatch, session: GameSession, layout: GameLayout) {
        drawFluid(batch, session, layout, glowOnly = true)
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        drawSourceLeds(batch, session, layout)
        particles.draw(batch, additivePass = true)
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawSourceLeds(batch: SpriteBatch, session: GameSession, layout: GameLayout) {
        val c = layout.cell
        // La source est la tête tant que le fluide n'est pas parti.
        if (session.phase == Phase.COUNTDOWN) {
            sourceX = session.headX
            sourceY = session.headY
        }
        val x = sourceX
        val y = sourceY
        if (x < 0) return
        val cx = layout.cellX(x) + c / 2
        val cy = layout.cellY(y) + c / 2
        val n = 20
        val countdown = session.phase == Phase.COUNTDOWN
        val frac = if (countdown && session.spec.countdown > 0f) session.countdownLeft / session.spec.countdown else 0f
        val lit = if (countdown) ceil(frac * n).toInt() else n
        val urgent = countdown && session.countdownLeft <= 5f
        val blink = if (urgent) Ease.pulse(time, 0.5f) else 1f
        val radius = c * 0.267f
        val size = c * 0.085f
        for (i in 0 until n) {
            val ang = Math.PI / 2 - i * 2 * Math.PI / n
            val px = cx + (cos(ang) * radius).toFloat()
            val py = cy + (sin(ang) * radius).toFloat()
            val on = i < lit
            val col = when {
                !countdown -> Palette.CYAN
                urgent -> Palette.MAGENTA
                else -> Palette.AMBER
            }
            val a = if (on) (if (urgent) 0.35f + 0.65f * blink else 0.95f) else 0.12f
            batch.setColor(col.r, col.g, col.b, a)
            batch.draw(assets.dot, px - size / 2, py - size / 2, size, size)
        }
        // Pression qui monte dans le dôme pendant l'attente.
        if (countdown) {
            val pressure = 1f - frac
            batch.setColor(Palette.CYAN.r, Palette.CYAN.g, Palette.CYAN.b, 0.10f + 0.35f * pressure * (0.7f + 0.3f * Ease.pulse(time, 0.8f)))
            val g = c * 0.55f
            batch.draw(assets.glow, cx - g / 2, cy - g / 2, g, g)
        }
        batch.setColor(1f, 1f, 1f, 1f)
    }

    private var sourceX = -1
    private var sourceY = -1
    private val trailBuf = dev.stefan.fluxline.core.TrailBuffer()

    private fun drawNextHint(batch: SpriteBatch, session: GameSession, layout: GameLayout) {
        val trail = session.trace(trailBuf)
        if (trail.end != dev.stefan.fluxline.core.TrailEnd.EMPTY_CELL) return
        val c = layout.cell
        // Temps avant que le fluide n'atteigne cette case.
        val spc = session.spec.secondsPerCell / if (session.fast) session.spec.fastFactor else 1f
        val eta = session.countdownLeft + (1f - session.headProgress) * session.headDuration() + max(0, trail.length - 1) * spc
        val urgent = eta < 2.5f
        val col = if (urgent) Palette.MAGENTA else Palette.CYAN
        val a = if (urgent) 0.45f + 0.55f * Ease.pulse(time, 0.45f) else 0.30f + 0.25f * Ease.pulse(time, 1.4f)
        brackets(batch, layout.cellX(trail.x), layout.cellY(trail.y), c, col, a, c * 0.05f)
        // Une flèche lumineuse indique le côté d'arrivée.
        val port = trail.port ?: return
        val cx = layout.cellX(trail.x) + c / 2 + port.dx * c * 0.42f
        val cy = layout.cellY(trail.y) + c / 2 - port.dy * c * 0.42f
        batch.setColor(col.r, col.g, col.b, a * 0.8f)
        val g = c * 0.38f
        batch.draw(assets.glow, cx - g / 2, cy - g / 2, g, g)
        batch.setColor(1f, 1f, 1f, 1f)
    }

    private fun brackets(batch: SpriteBatch, x: Float, y: Float, c: Float, col: Color, a: Float, t: Float) {
        val l = c * 0.26f
        val i = c * 0.06f
        batch.setColor(col.r, col.g, col.b, a)
        val x0 = x + i
        val y0 = y + i
        val x1 = x + c - i
        val y1 = y + c - i
        batch.draw(assets.white, x0, y0, l, t)
        batch.draw(assets.white, x0, y0, t, l)
        batch.draw(assets.white, x1 - l, y0, l, t)
        batch.draw(assets.white, x1 - t, y0, t, l)
        batch.draw(assets.white, x0, y1 - t, l, t)
        batch.draw(assets.white, x0, y1 - l, t, l)
        batch.draw(assets.white, x1 - l, y1 - t, l, t)
        batch.draw(assets.white, x1 - t, y1 - l, t, l)
        batch.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawLeak(batch: SpriteBatch, layout: GameLayout) {
        val port = leakPort ?: return
        if (leakAge > 2.5f) return
        val c = layout.cell
        val px = layout.cellX(leakX) + c / 2 + port.dx * c * 0.5f
        val py = layout.cellY(leakY) + c / 2 - port.dy * c * 0.5f
        // Jet de gouttes pendant un instant.
        if (leakAge < 1.4f) {
            val spread = 50f
            val base = when (port) {
                Direction.N -> 90f
                Direction.S -> 270f
                Direction.E -> 0f
                Direction.W -> 180f
            }
            for (k in 0 until 2) {
                val ang = Math.toRadians((base + particles.range(-spread, spread)).toDouble())
                val sp = c * particles.range(2f, 5f)
                particles.emit(assets.droplet, px, py, (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat(), particles.range(0.5f, 0.9f), c * 0.10f, c * 0.04f, Palette.CYAN_BRIGHT, add = true, grav = c * 8f, dragging = 0.6f)
            }
        }
        val pulse = Ease.pulse(time, 0.35f)
        val fade = 1f - (leakAge / 2.5f)
        batch.setColor(Palette.MAGENTA.r, Palette.MAGENTA.g, Palette.MAGENTA.b, (0.35f + 0.5f * pulse) * fade)
        val g = c * (0.9f + 0.3f * pulse)
        batch.draw(assets.glow, px - g / 2, py - g / 2, g, g)
        batch.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawGhost(batch: SpriteBatch, layout: GameLayout, ghost: Ghost) {
        if (!ghost.active) return
        val c = layout.cell
        val piece = ghost.piece
        if (ghost.x >= 0 && ghost.y >= 0) {
            val x = layout.cellX(ghost.x)
            val y = layout.cellY(ghost.y)
            val col = when {
                !ghost.valid -> Palette.MAGENTA
                ghost.replacing -> Palette.AMBER
                else -> Palette.CYAN
            }
            if (piece != null && ghost.valid && ghost.heldX.isNaN()) {
                drawPiece(batch, piece, x, y, c, alpha = 0.55f + 0.15f * Ease.pulse(time, 0.9f))
            }
            brackets(batch, x, y, c, col, 0.95f, c * 0.06f)
        }
        if (piece != null && !ghost.heldX.isNaN()) {
            val s = c * 1.08f
            drawPiece(batch, piece, ghost.heldX - s / 2, ghost.heldY - s / 2, s, alpha = 0.92f)
        }
    }

    fun drawParticles(batch: SpriteBatch) {
        particles.draw(batch, additivePass = false)
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        particles.draw(batch, additivePass = true)
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        /** Rapport entre la taille de la texture (avec marge) et celle de la case. */
        const val CANVAS_RATIO = 208f / 176f
        const val PLACE_TIME = 0.28f
    }
}
