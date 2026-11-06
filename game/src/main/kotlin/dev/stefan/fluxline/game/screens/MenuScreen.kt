package dev.stefan.fluxline.game.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.TimeUtils
import dev.stefan.fluxline.core.AutoPlayer
import dev.stefan.fluxline.core.Campaign
import dev.stefan.fluxline.core.GameEvent
import dev.stefan.fluxline.core.GameMode
import dev.stefan.fluxline.core.GameSession
import dev.stefan.fluxline.core.LevelGenerator
import dev.stefan.fluxline.core.Run
import dev.stefan.fluxline.core.SavedGame
import dev.stefan.fluxline.game.Ease
import dev.stefan.fluxline.game.FluxGame
import dev.stefan.fluxline.game.GameLayout
import dev.stefan.fluxline.game.Palette
import dev.stefan.fluxline.game.audio.Track
import dev.stefan.fluxline.game.render.BoardRenderer
import dev.stefan.fluxline.game.render.Ghost
import dev.stefan.fluxline.game.ui.Button
import dev.stefan.fluxline.game.ui.ButtonStyle
import kotlin.math.min

/**
 * Le menu principal. En fond, une vraie partie se joue toute seule (le robot
 * planificateur) : le plateau, le plasma et les effets sont ceux du jeu.
 */
class MenuScreen(game: FluxGame) : Screen(game) {
    private val demoLayout = GameLayout()
    private val demoRenderer = BoardRenderer(game.assets)
    private val noGhost = Ghost()
    private var demo = newDemo()
    private var demoBot = AutoPlayer(0.5f)
    private var demoEnd = 0f
    private var saved: SavedGame? = null

    /** Secondes restantes pour confirmer la sortie (second toucher sur « quitter »). */
    private var quitConfirm = 0f
    private var iconRowTop = 0f

    private fun newDemo(): GameSession {
        val spec = LevelGenerator.classic(3, TimeUtils.millis()).copy(countdown = 4f, secondsPerCell = 1.2f)
        return GameSession(spec).also { demoRenderer.reset(it) }
    }

    override fun show() {
        game.audio.music(Track.MENU)
        saved = game.save.loadGame()
        layout()
    }

    override fun layout() {
        demoLayout.compute(width, height, 0f, 0f, 0f, 0f, 7, 10, false)
        widgets.clear()
        val save = game.save
        val bw = min(width * 0.80f, 600f * u)
        val bh = 96f * u
        val gap = 16f * u
        val x = width / 2 - bw / 2
        var y = height * 0.70f - game.insetTop * 0.5f
        fun add(b: Button) {
            b.set(x, y - bh, bw, bh)
            widgets.add(b)
            y -= bh + gap
        }
        saved?.let { s ->
            add(Button(tr["menu.continue"], "play", ButtonStyle.PRIMARY, subtitle = describe(s)) { resume(s) })
        }
        val best = save.stats.bestClassicScore
        add(Button(tr["menu.classic"], null, if (saved == null) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY, subtitle = if (best > 0) tr.format("menu.record", "score" to tr.number(best)) else tr["menu.classic.sub"]) {
            start(Run(GameMode.CLASSIC, seed = TimeUtils.millis()))
        })
        add(Button(tr["menu.campaign"], null, ButtonStyle.SECONDARY, subtitle = tr.format("menu.campaign.sub", "stars" to save.totalStars, "total" to Campaign.size * 3)) {
            game.setScreen(LevelSelectScreen(game))
        })
        val arcade = save.stats.bestArcadeScore
        add(Button(tr["menu.arcade"], null, ButtonStyle.SECONDARY, subtitle = if (arcade > 0) tr.format("menu.record", "score" to tr.number(arcade)) else tr["menu.arcade.sub"]) {
            start(Run(GameMode.ARCADE, seed = TimeUtils.millis()))
        })
        val day = game.platform.today()
        val dailyBest = save.best("daily-$day")
        val kind = tr["challenge.${LevelGenerator.challengeKind(day).key}.title"]
        add(Button(tr["menu.daily"], "calendar", ButtonStyle.SECONDARY, subtitle = kind + if (dailyBest > 0) " · ${tr.number(dailyBest)}" else "") {
            start(Run(GameMode.CHALLENGE, seed = day.toLong(), challengeDay = day))
        })

        // Rangée d'icônes en bas.
        val ib = 92f * u
        val iy = game.insetBottom + 46f * u
        iconRowTop = iy + ib
        val names = listOf(
            "gear" to { game.setScreen(OptionsScreen(game)) },
            "chart" to { game.setScreen(StatsScreen(game)) },
            "info" to { game.setScreen(CreditsScreen(game)) },
            "power" to ::quit,
        )
        val total = names.size * ib + (names.size - 1) * 44f * u
        var ix = width / 2 - total / 2
        for ((icon, action) in names) {
            val b = Button(icon = icon, style = ButtonStyle.ICON, onClick = action)
            b.set(ix, iy, ib, ib)
            widgets.add(b)
            ix += ib + 44f * u
        }
    }

    /** Quitter demande un second toucher : une icône effleurée par erreur ne ferme pas le jeu. */
    private fun quit() {
        if (quitConfirm > 0f) Gdx.app.exit() else quitConfirm = QUIT_CONFIRM_TIME
    }

    private fun describe(s: SavedGame): String = when (s.run.mode) {
        GameMode.CLASSIC -> tr.format("mode.classic.round", "n" to s.run.level)
        GameMode.ARCADE -> tr.format("mode.arcade.wave", "n" to s.run.level)
        GameMode.LEVELS -> tr.format("mode.level", "n" to s.run.level, "title" to tr["level.${s.run.level}.title"])
        GameMode.CHALLENGE -> tr["mode.daily"]
    }

    private fun start(run: Run) {
        game.save.clearGame()
        game.setScreen(GameScreen(game, run))
    }

    private fun resume(s: SavedGame) {
        game.setScreen(GameScreen(game, Run.from(s.run), restored = s))
    }

    override fun update(dt: Float) {
        super.update(dt)
        quitConfirm = maxOf(0f, quitConfirm - dt)
        val d = dt * 1.4f
        demoRenderer.update(d)
        if (!demo.isFinished) {
            var t = d
            while (t > 0f) {
                val step = min(t, 1f / 60f)
                demoBot.update(demo, step)
                demo.update(step)
                t -= step
            }
            while (true) {
                when (val e = demo.pollEvent() ?: break) {
                    is GameEvent.Placed -> demoRenderer.onPlaced(e.x, e.y, e.replaced != null, demoLayout)
                    is GameEvent.ChannelFilled -> demoRenderer.onFilled(e.x, e.y, e.channel, e.crossBonus, demoLayout)
                    is GameEvent.Leaked -> demoRenderer.onLeak(e.x, e.y, e.port, demoLayout)
                    is GameEvent.RoundEnded -> if (e.result.success) demoRenderer.startWave()
                    else -> Unit
                }
            }
        } else {
            demoEnd += dt
            if (demoEnd > 2.5f) {
                demoEnd = 0f
                demo = newDemo()
                demoBot = AutoPlayer(0.5f)
            }
        }
    }

    override fun render(batch: SpriteBatch) {
        val ui = game.ui
        batch.begin()
        game.backdrop.draw(batch, width, height, game.time, u)
        demoRenderer.drawBoard(batch, demo, demoLayout, noGhost, false)
        demoRenderer.drawParticles(batch)
        // Voile : le plateau reste un décor.
        ui.rect(0f, 0f, width, height, Palette.SHADE, 0.62f)
        game.backdrop.vignette(batch, width, height, 0.8f)

        // Logo qui descend en douceur, puis respire.
        val a = intro(0f, 0.6f)
        val logo = game.assets.logo
        val lw = min(width * 0.88f, 980f * u)
        val lh = lw * logo.regionHeight / logo.regionWidth
        val ly = height - game.insetTop - 70f * u - lh + (1f - a) * 40f * u
        batch.setColor(1f, 1f, 1f, a)
        batch.draw(logo, width / 2 - lw / 2, ly, lw, lh)
        batch.setColor(1f, 1f, 1f, 1f)
        val glow = 0.25f + 0.15f * Ease.pulse(age, 3f)
        ui.text(game.assets.fonts.small, tr["menu.tagline"], width / 2, ly - 6f * u, Palette.CYAN, a * (0.6f + glow), Align.center, maxWidth = width * 0.9f)

        widgets.draw(ui, intro(0.15f, 0.4f))
        if (quitConfirm > 0f) {
            val qa = min(1f, quitConfirm * 3f) * min(1f, (QUIT_CONFIRM_TIME - quitConfirm) * 8f)
            ui.text(game.assets.fonts.small, tr["menu.quit.confirm"], width / 2, iconRowTop + 44f * u, Palette.MAGENTA, qa, Align.center, maxWidth = width * 0.9f)
        }
        ui.text(game.assets.fonts.small, "v1.0", width - 20f * u, game.insetBottom + 30f * u, Palette.TEXT_FAINT, 0.7f, Align.right)
        batch.end()
    }

    override fun back(): Boolean = false

    private companion object {
        const val QUIT_CONFIRM_TIME = 3f
    }
}
