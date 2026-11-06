package dev.stefan.fluxline.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.GdxRuntimeException
import dev.stefan.fluxline.core.Piece
import dev.stefan.fluxline.core.PieceFamily
import dev.stefan.fluxline.game.i18n.Language
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Toutes les ressources graphiques : deux atlas (plateau, interface), les
 * shaders et les polices. Les polices sont rastérisées à la taille exacte de
 * l'écran (FreeType), donc nettes à toutes les densités.
 */
class Assets : Disposable {
    val board = TextureAtlas(Gdx.files.internal("gfx/board.atlas"))
    val ui = TextureAtlas(Gdx.files.internal("gfx/ui.atlas"))

    private val bodies = HashMap<Piece, TextureRegion>()
    private val flows = HashMap<Piece, Array<TextureRegion>>()
    private val glasses = HashMap<Piece, TextureRegion>()

    val socket: TextureRegion = board.findRegion("socket")
    val fixedMark: TextureRegion = board.findRegion("fixed_mark")
    val glow: TextureRegion = board.findRegion("glow")
    val spark: TextureRegion = board.findRegion("spark")
    val dot: TextureRegion = board.findRegion("dot")
    val ring: TextureRegion = board.findRegion("ring")
    val droplet: TextureRegion = board.findRegion("droplet")
    /** Un carré blanc : rectangles, traits, voiles. Pris au centre pour éviter le filtrage des bords. */
    val white: TextureRegion = board.findRegion("white").let { TextureRegion(it, 2, 2, 4, 4) }

    val logo: TextureRegion = ui.findRegion("logo")
    val knob: TextureRegion = ui.findRegion("knob")
    val vignette: TextureRegion = ui.findRegion("vignette")
    private val icons = HashMap<String, TextureRegion>()

    lateinit var panel: NinePatch
        private set
    lateinit var button: NinePatch
        private set
    lateinit var buttonDown: NinePatch
        private set
    lateinit var buttonCyan: NinePatch
        private set
    lateinit var pill: NinePatch
        private set
    lateinit var barTrack: NinePatch
        private set
    lateinit var barFill: NinePatch
        private set

    val fluidShader = shader("sprite.vert", "fluid.frag")
    val blurShader = shader("sprite.vert", "blur.frag")
    val backgroundShader = shader("sprite.vert", "background.frag")

    val fonts = Fonts()

    init {
        for (p in Piece.entries) {
            bodies[p] = board.findRegion(bodyName(p)) ?: error("texture manquante : ${bodyName(p)}")
            if (p.channels.isNotEmpty()) {
                flows[p] = Array(p.channels.size) { ch -> board.findRegion(flowName(p, ch)) ?: error("texture manquante : ${flowName(p, ch)}") }
                glasses[p] = board.findRegion(glassName(p)) ?: error("texture manquante : ${glassName(p)}")
            }
        }
        for (r in ui.regions) if (r.name.startsWith("icon_")) icons[r.name.removePrefix("icon_")] = r
        patches(1f)
    }

    fun body(p: Piece): TextureRegion = bodies[p]!!
    fun flow(p: Piece, channel: Int): TextureRegion = flows[p]!![channel]
    fun glass(p: Piece): TextureRegion? = glasses[p]
    fun icon(name: String): TextureRegion = icons[name] ?: error("icône inconnue : $name")

    /** Adapte bordures et polices à l'échelle de l'écran et à la langue. */
    fun rescale(uiScale: Float, language: Language) {
        patches(uiScale)
        fonts.ensure(uiScale, language)
    }

    /** Épaisseur des bordures des neuf-tranches à l'échelle de l'écran. */
    private fun patches(uiScale: Float) {
        val s = uiScale / 1.5f
        panel = patch("panel", s)
        button = patch("button", s)
        buttonDown = patch("button_down", s)
        buttonCyan = patch("button_cyan", s)
        pill = patch("pill", s)
        barTrack = patch("bar_track", s)
        barFill = patch("bar_fill", s)
    }

    private fun patch(name: String, scale: Float): NinePatch = ui.createPatch(name).also { it.scale(scale, scale) }

    private fun shader(vert: String, frag: String): ShaderProgram {
        ShaderProgram.pedantic = false
        val p = ShaderProgram(Gdx.files.internal("shaders/$vert"), Gdx.files.internal("shaders/$frag"))
        if (!p.isCompiled) throw GdxRuntimeException("shader $frag : ${p.log}")
        return p
    }

    override fun dispose() {
        board.dispose()
        ui.dispose()
        fluidShader.dispose()
        blurShader.dispose()
        backgroundShader.dispose()
        fonts.dispose()
    }

    companion object {
        fun bodyName(p: Piece): String = when (p) {
            Piece.ONE_WAY_E, Piece.ONE_WAY_W -> "body_STRAIGHT_H"
            Piece.ONE_WAY_N, Piece.ONE_WAY_S -> "body_STRAIGHT_V"
            else -> "body_${p.name}"
        }

        fun flowName(p: Piece, channel: Int): String = when {
            p == Piece.CROSS -> "flow_CROSS_$channel"
            p.family == PieceFamily.ONE_WAY -> if (p.flowDirection!!.isHorizontal) "flow_STRAIGHT_H" else "flow_STRAIGHT_V"
            else -> "flow_${p.name}"
        }

        fun glassName(p: Piece): String = "glass_${p.name}"
    }
}

/**
 * Les polices, régénérées quand l'échelle de l'écran ou la langue change.
 * Chakra Petch (SIL OFL 1.1) pour les écritures latines ; pour le russe, le
 * chinois et le khmer, Chakra Petch complétée par l'écriture de la langue
 * (tools/gen_fonts.py), de sorte que chiffres et ponctuation restent identiques.
 */
class Fonts : Disposable {
    lateinit var small: BitmapFont
        private set
    lateinit var body: BitmapFont
        private set
    lateinit var title: BitmapFont
        private set
    lateinit var big: BitmapFont
        private set

    private var scale = -1f
    private var language: Language? = null
    private val loaded = ArrayList<BitmapFont>()
    /** Gardés ouverts quand les glyphes sont générés à la demande (chinois, khmer). */
    private val generators = ArrayList<FreeTypeFontGenerator>()

    fun ensure(uiScale: Float, lang: Language) {
        if (lang == language && scale > 0f && abs(uiScale - scale) / scale < 0.08f) return
        disposeFonts()
        scale = uiScale
        language = lang
        val bold = FreeTypeFontGenerator(Gdx.files.internal(lang.fontFile("Bold")))
        val semi = FreeTypeFontGenerator(Gdx.files.internal(lang.fontFile("SemiBold")))
        small = make(semi, 21f * uiScale, shadow = false, lang)
        body = make(semi, 27f * uiScale, shadow = true, lang)
        title = make(bold, 38f * uiScale, shadow = true, lang)
        big = make(bold, 58f * uiScale, shadow = true, lang)
        if (lang.incremental) {
            generators += bold
            generators += semi
        } else {
            bold.dispose()
            semi.dispose()
        }
    }

    private fun make(gen: FreeTypeFontGenerator, size: Float, shadow: Boolean, lang: Language): BitmapFont {
        val p = FreeTypeFontGenerator.FreeTypeFontParameter()
        p.size = size.roundToInt().coerceAtLeast(8)
        p.characters = when {
            lang.incremental -> FreeTypeFontGenerator.DEFAULT_CHARS
            lang == Language.RU -> CHARS + CYRILLIC
            else -> CHARS
        }
        p.incremental = lang.incremental
        p.minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
        p.magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear
        p.kerning = true
        if (shadow) {
            p.shadowOffsetX = 0
            p.shadowOffsetY = (size / 18f).roundToInt().coerceAtLeast(1)
            p.shadowColor = Color(0f, 0.01f, 0.05f, 0.55f)
        }
        // Pages plus petites pour les petites tailles générées à la demande.
        FreeTypeFontGenerator.setMaxTextureSize(if (lang.incremental && p.size < 48) 512 else 1024)
        val f = gen.generateFont(p)
        FreeTypeFontGenerator.setMaxTextureSize(1024)
        // Coupure de ligne possible entre deux mots khmers (espace sans chasse).
        f.data.breakChars = BREAK_CHARS
        f.setUseIntegerPositions(true)
        loaded += f
        return f
    }

    private fun disposeFonts() {
        for (f in loaded) f.dispose()
        loaded.clear()
        for (g in generators) g.dispose()
        generators.clear()
    }

    override fun dispose() = disposeFonts()

    companion object {
        const val CHARS = FreeTypeFontGenerator.DEFAULT_CHARS +
            "àâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ«»’…—–•×·°−"
        val CYRILLIC = String(CharArray(0x60) { (0x400 + it).toChar() })
        private val BREAK_CHARS = charArrayOf('\u200B')
    }
}
