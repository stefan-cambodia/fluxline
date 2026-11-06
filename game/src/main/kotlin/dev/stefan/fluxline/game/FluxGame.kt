package dev.stefan.fluxline.game

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.ScreenUtils
import dev.stefan.fluxline.core.GameMode
import dev.stefan.fluxline.core.Run
import dev.stefan.fluxline.game.audio.AudioManager
import dev.stefan.fluxline.game.i18n.Language
import dev.stefan.fluxline.game.i18n.Strings
import dev.stefan.fluxline.game.render.Backdrop
import dev.stefan.fluxline.game.render.Bloom
import dev.stefan.fluxline.game.save.SaveData
import dev.stefan.fluxline.game.screens.CreditsScreen
import dev.stefan.fluxline.game.screens.GameScreen
import dev.stefan.fluxline.game.screens.LevelSelectScreen
import dev.stefan.fluxline.game.screens.MenuScreen
import dev.stefan.fluxline.game.screens.OptionsScreen
import dev.stefan.fluxline.game.screens.Screen
import dev.stefan.fluxline.game.screens.StatsScreen
import dev.stefan.fluxline.game.ui.UiContext

/**
 * Le point d'entrée commun à toutes les plateformes : boucle principale,
 * gestion des écrans (avec fondus), entrées, cycle de vie.
 */
class FluxGame(
    val platform: Platform,
    val launch: LaunchOptions = LaunchOptions(),
) : ApplicationListener {
    lateinit var assets: Assets
        private set
    lateinit var save: SaveData
        private set
    lateinit var audio: AudioManager
        private set
    lateinit var ui: UiContext
        private set
    lateinit var batch: SpriteBatch
        private set
    lateinit var backdrop: Backdrop
        private set
    lateinit var bloom: Bloom
        private set
    /** Les textes de la langue courante. */
    lateinit var tr: Strings
        private set
    val camera = OrthographicCamera()
    private var languageOverride: String? = launch.language

    private var screen: Screen? = null
    private var pending: Screen? = null
    private var fadeOut = 0f
    private var fadeIn = 0f

    var width = 0f
        private set
    var height = 0f
        private set
    var time = 0f
        private set

    // Mesures (journal de performance et captures).
    private var shotTimer = 0f
    private var shotDone = false
    private var perfTimer = 0f
    private var perfFrames = 0
    private var perfWorst = 0f

    override fun create() {
        Gdx.app.logLevel = com.badlogic.gdx.Application.LOG_INFO
        save = SaveData()
        tr = loadStrings(resolveLanguage())
        assets = Assets()
        audio = AudioManager(save.settings)
        batch = SpriteBatch(4000)
        ui = UiContext(batch, assets, audio) { kind -> if (save.settings.haptics) platform.haptic(kind) }
        backdrop = Backdrop(assets)
        bloom = Bloom(assets)
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        Gdx.input.inputProcessor = input
        width = Gdx.graphics.width.toFloat()
        height = Gdx.graphics.height.toFloat()
        applySize()
        setScreen(firstScreen(), instant = true)
    }

    private fun firstScreen(): Screen = when (launch.startScreen) {
        "game", "result" -> {
            val mode = GameMode.byKey(launch.mode)
            val day = if (mode == GameMode.CHALLENGE) platform.today() else 0
            GameScreen(this, Run(mode, seed = 2026, level = launch.level, challengeDay = day))
        }
        "levels" -> LevelSelectScreen(this)
        "options" -> OptionsScreen(this)
        "stats" -> StatsScreen(this)
        "credits" -> CreditsScreen(this)
        else -> MenuScreen(this)
    }

    // --- Écrans --------------------------------------------------------------------

    fun setScreen(next: Screen, instant: Boolean = false) {
        screen?.widgets?.cancel(ui)
        if (instant || screen == null) {
            screen?.hide()
            screen = next
            next.resize(width, height)
            next.show()
            fadeIn = if (screen == null) 0f else 1f
            fadeOut = 0f
            pending = null
        } else {
            pending = next
            fadeOut = 0.0001f
        }
    }

    val currentScreen: Screen? get() = screen

    // --- Langue ------------------------------------------------------------------------

    /** La langue choisie dans les options, sinon celle du système (anglais par défaut). */
    fun resolveLanguage(): Language =
        Language.byCode(languageOverride) ?: Language.byCode(save.settings.language) ?: Language.forSystem(platform.systemLanguage())

    /**
     * Change de langue tout de suite : textes et polices. Les écrans relisent
     * leurs textes à leur prochaine mise en page.
     */
    fun setLanguage(code: String) {
        save.settings.language = code
        save.saveSettings()
        languageOverride = null
        val lang = resolveLanguage()
        if (lang != tr.language) {
            tr = loadStrings(lang)
            assets.rescale(ui.scale, lang)
        }
    }

    private fun loadStrings(lang: Language): Strings {
        fun read(l: Language) = Strings.parse(Gdx.files.internal(l.stringsFile).readString("UTF-8"))
        val english = Strings(Language.EN, read(Language.EN))
        return if (lang == Language.EN) english else Strings(lang, read(lang), english)
    }

    // --- Boucle ----------------------------------------------------------------------

    override fun render() {
        val raw = Gdx.graphics.deltaTime
        val dt = raw.coerceAtMost(0.1f) * launch.timeScale
        time += dt
        audio.update(dt)

        // Transitions : fondu au noir, changement, fondu d'entrée.
        if (pending != null) {
            fadeOut += dt / 0.16f
            if (fadeOut >= 1f) {
                screen?.hide()
                screen = pending
                pending = null
                screen!!.resize(width, height)
                screen!!.show()
                fadeOut = 0f
                fadeIn = 1f
            }
        } else if (fadeIn > 0f) {
            fadeIn = (fadeIn - dt / 0.24f).coerceAtLeast(0f)
        }

        val s = screen ?: return
        s.update(dt)

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        camera.update()
        batch.projectionMatrix = camera.combined
        s.render(batch)

        val veil = maxOf(fadeOut.coerceAtMost(1f), fadeIn)
        batch.begin()
        if (save.settings.scanlines) backdrop.scanlines(batch, width, height, ui.scale)
        if (veil > 0f) ui.rect(0f, 0f, width, height, Palette.SHADE, Ease.inOutCubic(veil))
        batch.end()

        devHooks(raw)
    }

    private fun devHooks(raw: Float) {
        if (launch.perfLogSeconds > 0f) {
            perfTimer += raw
            perfFrames++
            perfWorst = maxOf(perfWorst, raw)
            if (perfTimer >= launch.perfLogSeconds) {
                Gdx.app.log(
                    "FluxlinePerf",
                    "fps=%.1f pire=%.1fms java=%dMo natif=%dMo".format(
                        perfFrames / perfTimer, perfWorst * 1000, Gdx.app.javaHeap / 1048576, Gdx.app.nativeHeap / 1048576,
                    ),
                )
                perfTimer = 0f
                perfFrames = 0
                perfWorst = 0f
            }
        }
        val shot = launch.shotPath ?: return
        if (shotDone) return
        shotTimer += raw
        if (shotTimer >= launch.shotDelay) {
            shotDone = true
            val w = Gdx.graphics.backBufferWidth
            val h = Gdx.graphics.backBufferHeight
            val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
            for (i in 3 until pixels.size step 4) pixels[i] = 255.toByte()
            val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
            com.badlogic.gdx.utils.BufferUtils.copy(pixels, 0, pm.pixels, pixels.size)
            PixmapIO.writePNG(Gdx.files.absolute(shot), pm, java.util.zip.Deflater.DEFAULT_COMPRESSION, false)
            pm.dispose()
            Gdx.app.log("Fluxline", "capture : $shot")
            Gdx.app.exit()
        }
    }

    override fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        width = w.toFloat()
        height = h.toFloat()
        applySize()
        screen?.resize(width, height)
    }

    private fun applySize() {
        camera.setToOrtho(false, width, height)
        camera.update()
        ui.scale = GameLayout.uiScale(width, height)
        assets.rescale(ui.scale, tr.language)
        bloom.resize(width.toInt(), height.toInt())
    }

    override fun pause() {
        screen?.pause()
    }

    override fun resume() {
        screen?.resume()
    }

    override fun dispose() {
        screen?.hide()
        bloom.dispose()
        audio.dispose()
        batch.dispose()
        assets.dispose()
    }

    // --- Entrées -------------------------------------------------------------------------

    val insetTop: Float get() = Gdx.graphics.safeInsetTop.toFloat()
    val insetBottom: Float get() = Gdx.graphics.safeInsetBottom.toFloat()
    val insetLeft: Float get() = Gdx.graphics.safeInsetLeft.toFloat()
    val insetRight: Float get() = Gdx.graphics.safeInsetRight.toFloat()

    private val inputBlocked: Boolean get() = pending != null || fadeIn > 0.6f

    private val input = object : InputAdapter() {
        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (inputBlocked) return true
            return screen?.touchDown(screenX.toFloat(), height - screenY, pointer) ?: false
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            return screen?.touchDragged(screenX.toFloat(), height - screenY, pointer) ?: false
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            return screen?.touchUp(screenX.toFloat(), height - screenY, pointer) ?: false
        }

        override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            screen?.widgets?.cancel(ui)
            return screen?.touchUp(-1e6f, -1e6f, pointer) ?: false
        }

        override fun keyDown(keycode: Int): Boolean {
            if (keycode == Input.Keys.BACK || keycode == Input.Keys.ESCAPE) {
                if (inputBlocked) return true
                val handled = screen?.back() ?: false
                if (!handled) Gdx.app.exit()
                return true
            }
            return false
        }
    }
}
