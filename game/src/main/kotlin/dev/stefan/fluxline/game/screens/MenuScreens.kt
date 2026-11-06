package dev.stefan.fluxline.game.screens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import dev.stefan.fluxline.core.Campaign
import dev.stefan.fluxline.core.GameMode
import dev.stefan.fluxline.core.Run
import dev.stefan.fluxline.core.Scoring
import dev.stefan.fluxline.game.Ease
import dev.stefan.fluxline.game.FluxGame
import dev.stefan.fluxline.game.Palette
import dev.stefan.fluxline.game.ui.Button
import dev.stefan.fluxline.game.ui.ButtonStyle
import dev.stefan.fluxline.game.ui.Choice
import dev.stefan.fluxline.game.ui.Slider
import dev.stefan.fluxline.game.ui.Toggle
import dev.stefan.fluxline.game.ui.UiContext
import dev.stefan.fluxline.game.ui.Widget
import kotlin.math.min

/** Base des écrans secondaires : fond, bouton retour, titre (clé de traduction). */
abstract class PanelScreen(game: FluxGame, private val titleKey: String) : Screen(game) {
    protected val backButton = Button(icon = "back", style = ButtonStyle.ICON) { goBack() }
    protected val top: Float get() = height - game.insetTop - 24f * u
    protected val contentTop: Float get() = top - 110f * u

    open fun goBack() {
        game.setScreen(MenuScreen(game))
    }

    override fun back(): Boolean {
        game.ui.audio.play(dev.stefan.fluxline.game.audio.Sfx.BACK)
        goBack()
        return true
    }

    override fun layout() {
        widgets.clear()
        val s = 84f * u
        backButton.set(game.insetLeft + 20f * u, top - s, s, s)
        backButton.touchPad = 10f * u
        widgets.add(backButton)
        layoutContent()
    }

    abstract fun layoutContent()

    override fun render(batch: SpriteBatch) {
        batch.begin()
        game.backdrop.draw(batch, width, height, game.time, u)
        game.backdrop.vignette(batch, width, height, 0.7f)
        val a = intro()
        val side = game.insetLeft + 120f * u
        game.ui.text(game.assets.fonts.title, tr[titleKey], width / 2, top - 42f * u + game.assets.fonts.title.capHeight / 2, Palette.TEXT, a, Align.center, maxWidth = width - 2 * side)
        drawContent(batch, a)
        widgets.draw(game.ui, a)
        batch.end()
    }

    open fun drawContent(batch: SpriteBatch, alpha: Float) {}
}

/** Une case de la grille des niveaux. */
private class LevelTile(
    val level: Int,
    val unlocked: Boolean,
    val stars: Int,
    val title: String,
    val onClick: () -> Unit,
) : Widget() {
    private var pressed = false

    override fun touchDown(x: Float, y: Float, ui: UiContext) {
        pressed = true
    }

    override fun touchUp(x: Float, y: Float, inside: Boolean, ui: UiContext) {
        if (inside && pressed) {
            if (unlocked) {
                ui.click()
                onClick()
            } else {
                ui.audio.play(dev.stefan.fluxline.game.audio.Sfx.REFUSE)
                ui.haptic(dev.stefan.fluxline.game.Haptic.ERROR)
            }
        }
        pressed = false
    }

    override fun draw(ui: UiContext, alpha: Float) {
        val b = bounds
        val s = if (pressed) 0.95f else 1f
        val w = b.width * s
        val h = b.height * s
        val x = b.x + (b.width - w) / 2
        val y = b.y + (b.height - h) / 2
        val a = alpha * if (unlocked) 1f else 0.55f
        val patch = when {
            pressed -> ui.assets.buttonDown
            stars == 3 -> ui.assets.button
            else -> ui.assets.buttonCyan
        }
        ui.patch(patch, x, y, w, h, Color.WHITE, a)
        val f = ui.assets.fonts
        if (unlocked) {
            ui.text(f.title, level.toString(), x + w / 2, y + h * 0.66f + f.title.capHeight / 2, if (stars > 0) Palette.AMBER_SOFT else Palette.TEXT, a, Align.center)
            val ss = w * 0.2f
            for (k in 0 until 3) {
                val on = k < stars
                ui.icon(ui.assets.icon(if (on) "star" else "star_outline"), x + w / 2 + (k - 1) * ss * 1.1f, y + h * 0.26f, ss, if (on) Palette.AMBER else Palette.TEXT_FAINT, a)
            }
        } else {
            ui.icon(ui.assets.icon("lock"), x + w / 2, y + h / 2, w * 0.34f, Palette.TEXT_DIM, a)
        }
    }
}

/** La campagne : vingt niveaux, débloqués l'un après l'autre. */
class LevelSelectScreen(game: FluxGame) : PanelScreen(game, "levels.title") {
    override fun layoutContent() {
        val cols = 4
        val rows = (Campaign.size + cols - 1) / cols
        val gap = 18f * u
        val areaTop = contentTop - 40f * u
        val areaBottom = game.insetBottom + 40f * u
        val tile = min((min(width, 760f * u) - 2 * 30f * u - (cols - 1) * gap) / cols, (areaTop - areaBottom - (rows - 1) * gap) / rows)
        val gridW = cols * tile + (cols - 1) * gap
        val x0 = width / 2 - gridW / 2
        val openAll = game.platform.devTools
        for (i in 0 until Campaign.size) {
            val level = i + 1
            val cx = i % cols
            val cy = i / cols
            val unlocked = game.save.isUnlocked(level, openAll)
            val t = LevelTile(level, unlocked, game.save.stars(level), tr["level.$level.title"]) {
                game.save.clearGame()
                game.setScreen(GameScreen(game, Run(GameMode.LEVELS, seed = 1, level = level)))
            }
            t.set(x0 + cx * (tile + gap), areaTop - (cy + 1) * tile - cy * gap, tile, tile)
            widgets.add(t)
        }
    }

    override fun drawContent(batch: SpriteBatch, alpha: Float) {
        val f = game.assets.fonts
        val label = "${game.save.totalStars} / ${Campaign.size * 3}"
        val tw = game.ui.measure(f.body, label)
        val iconSize = 40f * u
        val x0 = width / 2 - (tw + iconSize + 10f * u) / 2
        game.ui.icon(game.assets.icon("star"), x0 + iconSize / 2, contentTop + 10f * u - f.body.capHeight / 2, iconSize, Palette.AMBER, alpha)
        game.ui.text(f.body, label, x0 + iconSize + 10f * u, contentTop + 10f * u, Palette.AMBER, alpha)
    }
}

/** Les réglages. Peut revenir à une partie en pause plutôt qu'au menu. */
class OptionsScreen(game: FluxGame, private val returnTo: Screen? = null) : PanelScreen(game, "options.title") {
    private var confirmReset = 0f
    private lateinit var resetButton: Button

    override fun goBack() {
        game.save.saveSettings()
        game.setScreen(returnTo ?: MenuScreen(game))
    }

    override fun layoutContent() {
        val s = game.save.settings
        val w = min(width * 0.86f, 640f * u)
        val x = width / 2 - w / 2
        var y = contentTop - 10f * u
        val sliderH = 96f * u
        val toggleH = 96f * u
        fun slider(sl: Slider) {
            sl.set(x, y - sliderH, w, sliderH)
            sl.touchPad = 6f * u
            widgets.add(sl)
            y -= sliderH + 14f * u
        }
        fun toggle(t: Toggle) {
            t.set(x, y - toggleH, w, toggleH)
            widgets.add(t)
            y -= toggleH + 6f * u
        }
        // La langue d'abord : on doit pouvoir la retrouver même sans lire la langue affichée.
        val language = Choice(tr["options.language"], languageLabel(), "globe") {
            val i = LANGUAGES.indexOf(s.language).coerceAtLeast(0)
            game.setLanguage(LANGUAGES[(i + 1) % LANGUAGES.size])
            layout()
        }
        language.set(x, y - 84f * u, w, 84f * u)
        widgets.add(language)
        y -= 84f * u + 26f * u
        slider(Slider(tr["options.master"], s.masterVolume) { s.masterVolume = it; game.audio.refreshVolumes() })
        slider(Slider(tr["options.music"], s.musicVolume) { s.musicVolume = it; game.audio.refreshVolumes() })
        slider(Slider(tr["options.sfx"], s.sfxVolume) { s.sfxVolume = it })
        y -= 10f * u
        toggle(Toggle(tr["options.haptics"], s.haptics, tr["options.haptics.sub"]) { s.haptics = it })
        toggle(Toggle(tr["options.glow"], s.glow, tr["options.glow.sub"]) { s.glow = it })
        toggle(Toggle(tr["options.hint"], s.nextCellHint, tr["options.hint.sub"]) { s.nextCellHint = it })
        toggle(Toggle(tr["options.scanlines"], s.scanlines, tr["options.scanlines.sub"]) { s.scanlines = it })
        if (returnTo == null) {
            y -= 20f * u
            resetButton = Button(tr["options.reset"], null, ButtonStyle.DANGER) {
                if (confirmReset > 0f) {
                    game.save.resetProgress()
                    resetButton.text = tr["options.reset.done"]
                    confirmReset = 0f
                } else {
                    confirmReset = 3f
                    resetButton.text = tr["options.reset.confirm"]
                }
            }
            resetButton.set(x, y - 84f * u, w, 84f * u)
            widgets.add(resetButton)
        }
    }

    override fun update(dt: Float) {
        super.update(dt)
        if (confirmReset > 0f) {
            confirmReset -= dt
            if (confirmReset <= 0f && ::resetButton.isInitialized) resetButton.text = tr["options.reset"]
        }
    }

    override fun hide() {
        game.save.saveSettings()
    }

    /** « Français », ou « Automatique · Français » quand la langue suit celle du système. */
    private fun languageLabel(): String {
        val auto = dev.stefan.fluxline.game.i18n.Language.byCode(game.save.settings.language) == null
        return if (auto) tr.format("options.language.auto", "lang" to tr["lang.name"]) else tr["lang.name"]
    }

    private companion object {
        /** Ordre de défilement : « auto » suit la langue du système. */
        val LANGUAGES = listOf("auto", "fr", "en", "ru", "zh", "km")
    }
}

/** Les statistiques cumulées. */
class StatsScreen(game: FluxGame) : PanelScreen(game, "stats.title") {
    private val rows = ArrayList<Pair<String, String>>()

    override fun layoutContent() {
        val st = game.save.stats
        rows.clear()
        fun n(v: Long) = tr.number(v.toInt())
        rows += tr["stats.rounds"] to n(st.roundsPlayed.toLong())
        rows += tr["stats.won"] to if (st.roundsPlayed > 0) tr.format("stats.won.value", "n" to n(st.roundsWon.toLong()), "pct" to st.roundsWon * 100 / st.roundsPlayed) else "0"
        rows += tr["stats.pipes"] to n(st.pipesFilled)
        rows += tr["stats.pieces"] to n(st.piecesPlaced)
        rows += tr["stats.crossings"] to n(st.crossings.toLong())
        rows += tr["stats.drains"] to n(st.drains.toLong())
        rows += tr["stats.bestClassic"] to if (st.bestClassicScore > 0) tr.format("stats.bestClassic.value", "score" to n(st.bestClassicScore.toLong()), "n" to st.bestClassicLevel) else "—"
        rows += tr["stats.bestArcade"] to if (st.bestArcadeScore > 0) tr.format("stats.bestArcade.value", "score" to n(st.bestArcadeScore.toLong()), "n" to st.bestArcadeLevel) else "—"
        rows += tr["stats.stars"] to "${game.save.totalStars} / ${Campaign.size * 3}"
        rows += tr["stats.challenges"] to n(st.challengesWon.toLong())
        val minutes = (st.secondsPlayed / 60f).toInt()
        rows += tr["stats.time"] to if (minutes >= 60) tr.format("stats.time.hours", "h" to minutes / 60, "m" to minutes % 60) else tr.format("stats.time.minutes", "m" to minutes)
    }

    override fun drawContent(batch: SpriteBatch, alpha: Float) {
        val f = game.assets.fonts
        val w = min(width * 0.88f, 680f * u)
        val x = width / 2 - w / 2
        var y = contentTop - 20f * u
        val rowH = 74f * u
        for ((i, row) in rows.withIndex()) {
            val a = alpha * Ease.outCubic((age - i * 0.03f) / 0.3f)
            if (i % 2 == 0) game.ui.patch(game.assets.pill, x - 16f * u, y - rowH + 8f * u, w + 32f * u, rowH - 8f * u, Color.WHITE, a * 0.6f)
            val cy = y - rowH / 2 + 4f * u + f.body.capHeight / 2
            val vw = game.ui.text(f.body, row.second, x + w, cy, Palette.TEXT, a, Align.right)
            game.ui.text(f.body, row.first, x, cy, Palette.TEXT_DIM, a, maxWidth = w - vw - 24f * u)
            y -= rowH
        }
    }
}

/** Les crédits et licences. */
class CreditsScreen(game: FluxGame) : PanelScreen(game, "credits.title") {
    /** Texte, couleur, titre de rubrique (police plus grande). */
    private class Line(val text: String, val color: Color, val heading: Boolean = false)

    private var lines = emptyList<Line>()

    override fun layoutContent() {
        val blank = Line("", Palette.TEXT)
        lines = listOf(
            Line("FLUXLINE", Palette.AMBER_SOFT, heading = true),
            Line(tr["credits.tagline"], Palette.TEXT),
            blank,
            Line(tr["credits.design"], Palette.CYAN, heading = true),
            Line(tr["credits.design.text"], Palette.TEXT),
            blank,
            Line(tr["credits.fonts"], Palette.CYAN, heading = true),
            // Noms propres et licences : identiques dans toutes les langues.
            Line("Chakra Petch — The Chakra Petch Project Authors, SIL Open Font License 1.1.", Palette.TEXT),
            Line("Orbitron (logo) — The Orbitron Project Authors, SIL Open Font License 1.1.", Palette.TEXT),
            Line("Exo 2 — The Exo 2 Project Authors, SIL Open Font License 1.1.", Palette.TEXT),
            Line("Noto Sans Khmer — The Noto Project Authors, SIL Open Font License 1.1.", Palette.TEXT),
            Line("Noto Sans SC — Adobe, SIL Open Font License 1.1.", Palette.TEXT),
            blank,
            Line(tr["credits.engine"], Palette.CYAN, heading = true),
            Line("libGDX — Apache License 2.0.", Palette.TEXT),
            blank,
            Line(tr["credits.scoring"], Palette.CYAN, heading = true),
            Line(
                tr.format(
                    "credits.scoring.text",
                    "pipe" to Scoring.PIPE, "over" to Scoring.PIPE_OVER_TARGET, "reservoir" to Scoring.RESERVOIR,
                    "cross" to Scoring.CROSS_BONUS, "drain" to Scoring.DRAIN_BONUS, "replace" to Scoring.REPLACE_PENALTY,
                    "unused" to Scoring.UNUSED_PENALTY, "fast" to Scoring.FAST_MULTIPLIER,
                ),
                Palette.TEXT_DIM,
            ),
        )
    }

    override fun drawContent(batch: SpriteBatch, alpha: Float) {
        val f = game.assets.fonts
        val w = min(width * 0.86f, 680f * u)
        val x = width / 2 - w / 2
        var y = contentTop - 10f * u
        for (line in lines) {
            if (line.text.isEmpty()) {
                y -= 22f * u
                continue
            }
            game.ui.text(if (line.heading) f.body else f.small, line.text, x, y, line.color, alpha, Align.left, w, wrap = true)
            y -= game.ui.layout.height + 14f * u
        }
    }
}
