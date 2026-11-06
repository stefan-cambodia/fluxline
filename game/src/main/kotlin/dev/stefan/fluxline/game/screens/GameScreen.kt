package dev.stefan.fluxline.game.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.TimeUtils
import dev.stefan.fluxline.core.AutoPlayer
import dev.stefan.fluxline.core.Campaign
import dev.stefan.fluxline.core.GameEvent
import dev.stefan.fluxline.core.GameMode
import dev.stefan.fluxline.core.GameSession
import dev.stefan.fluxline.core.LevelGenerator
import dev.stefan.fluxline.core.Phase
import dev.stefan.fluxline.core.Refusal
import dev.stefan.fluxline.core.RoundResult
import dev.stefan.fluxline.core.Run
import dev.stefan.fluxline.core.RunOutcome
import dev.stefan.fluxline.core.SavedGame
import dev.stefan.fluxline.core.Scoring
import dev.stefan.fluxline.game.Ease
import dev.stefan.fluxline.game.FluxGame
import dev.stefan.fluxline.game.GameLayout
import dev.stefan.fluxline.game.Haptic
import dev.stefan.fluxline.game.Palette
import dev.stefan.fluxline.game.audio.Sfx
import dev.stefan.fluxline.game.audio.Track
import dev.stefan.fluxline.game.render.BoardRenderer
import dev.stefan.fluxline.game.render.Ghost
import dev.stefan.fluxline.game.ui.Button
import dev.stefan.fluxline.game.ui.ButtonStyle
import dev.stefan.fluxline.game.ui.WidgetGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * L'écran de jeu : une manche après l'autre, selon le mode de la partie.
 *
 * Gestes :
 *  - toucher le plateau montre où la pièce ira ; glisser ajuste ; relâcher pose.
 *    Sortir du plateau avant de relâcher annule.
 *  - glisser depuis la prochaine pièce du distributeur la prend sous le doigt.
 *  - toucher la prochaine pièce (ou le bouton dédié) la fait pivoter, si le mode le permet.
 *  - « Accélérer » se maintient un court instant : c'est irréversible.
 */
class GameScreen(
    game: FluxGame,
    private var run: Run,
    restored: SavedGame? = null,
) : Screen(game) {

    private enum class State { INTRO, PLAYING, PAUSED, ENDING, RESULT }

    private val layout = GameLayout()
    private val renderer = BoardRenderer(game.assets)
    private val ghost = Ghost()
    private lateinit var session: GameSession
    private var state = State.INTRO
    private var stateTime = 0f
    private var accumulator = 0f
    private val bot: AutoPlayer? = if (game.launch.autoplay) AutoPlayer(0.6f) else null

    private val overlay = WidgetGroup()
    private val pauseButton = Button(icon = "pause", style = ButtonStyle.ICON) { openPause() }

    // Score affiché (il rattrape le vrai score en douceur).
    private var shownScore = 0f
    private var result: RoundResult? = null
    private var outcome: RunOutcome? = null
    private var newRecord = false
    private var saveTimer = 0f

    // Geste en cours.
    private enum class Gesture { NONE, AIM, QUEUE_PRESS, DRAG, FAST, ROTATE }
    private var gesture = Gesture.NONE
    private var gesturePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var fastHold = 0f
    private var fastHint = 0f
    private var pendingX = -1
    private var pendingY = -1
    private var pendingAge = 0f

    // Animations du distributeur.
    private var queueShift = 1f
    private var rotateAnim = 1f
    private var refusedX = -1
    private var refusedY = -1
    private var refusedAge = 9f

    // Textes flottants et bannière.
    private val popText = arrayOfNulls<String>(24)
    private val popX = FloatArray(24)
    private val popY = FloatArray(24)
    private val popAge = FloatArray(24) { 9f }
    private val popBig = BooleanArray(24)
    private val popColor = arrayOfNulls<Color>(24)
    private var popCursor = 0
    private var banner: String? = null
    private var bannerColor: Color = Palette.CYAN
    private var bannerAge = 9f

    // Textes mis en cache (ne sont recalculés que si la valeur change).
    private var cachedScore = -1
    private var scoreText = ""
    private var cachedGauge = -1
    private var gaugeText = ""
    private var cachedTimer = -1
    private var timerText = ""

    init {
        if (restored != null) {
            session = GameSession(restored.spec)
            session.restore(restored.session)
            state = State.PAUSED
        } else {
            session = GameSession(run.currentSpec())
        }
        renderer.reset(session)
        shownScore = (run.totalScore + session.score).toFloat()
    }

    private val spec get() = session.spec

    override fun show() {
        game.audio.music(Track.GAME)
        if (state == State.PAUSED) buildPauseOverlay() else enterIntro()
    }

    override fun hide() {
        game.audio.hum(0f)
    }

    override fun layout() {
        layout.compute(width, height, game.insetTop, game.insetBottom, game.insetLeft, game.insetRight, spec.cols, spec.rows, spec.allowRotation)
        pauseButton.set(layout.pause.x, layout.pause.y, layout.pause.width, layout.pause.height)
        pauseButton.touchPad = 10f * u
        widgets.clear()
        widgets.add(pauseButton)
        // Les textes en cache sont recalculés (la langue a pu changer dans les options).
        hudFor = null
        hudLivesFor = -1
        cachedTimer = -1
        cachedRemaining = -1
        cachedScore = -1
        introFor = null
        rebuildOverlay()
    }

    // --- Manches -------------------------------------------------------------------

    private fun startRound(newRun: Run? = null) {
        if (newRun != null) run = newRun
        session = GameSession(run.currentSpec())
        renderer.reset(session)
        result = null
        outcome = null
        newRecord = false
        queueShift = 1f
        shownScore = run.totalScore.toFloat()
        layout()
        enterIntro()
    }

    private fun enterIntro() {
        state = State.INTRO
        stateTime = 0f
        saveGame()
        rebuildOverlay()
    }

    private fun beginPlay() {
        state = State.PLAYING
        stateTime = 0f
        rebuildOverlay()
    }

    private fun openPause() {
        if (state != State.PLAYING && state != State.INTRO) return
        cancelGesture()
        state = State.PAUSED
        stateTime = 0f
        game.audio.hum(0f)
        saveGame()
        rebuildOverlay()
    }

    private fun resumePlay() {
        state = if (session.phase == Phase.COUNTDOWN && session.countdownLeft >= spec.countdown) State.INTRO else State.PLAYING
        stateTime = if (state == State.INTRO) 1f else 0f
        rebuildOverlay()
    }

    private fun saveGame() {
        if (bot != null) return
        if (session.isFinished) return
        game.save.saveGame(SavedGame(run.state(), spec, session.capture()))
    }

    private fun buildPauseOverlay() {
        state = State.PAUSED
        rebuildOverlay()
    }

    private fun rebuildOverlay() {
        overlay.clear()
        val bw = min(width * 0.78f, 560f * u)
        val bh = 92f * u
        val cx = width / 2 - bw / 2
        fun column(vararg buttons: Button, top: Float) {
            var y = top
            for (b in buttons) {
                b.set(cx, y - bh, bw, bh)
                overlay.add(b)
                y -= bh + 18f * u
            }
        }
        when (state) {
            State.PAUSED -> column(
                Button(tr["pause.resume"], "play") { resumePlay() },
                Button(tr["pause.restart"], "restart", ButtonStyle.SECONDARY) { restartRound() },
                Button(tr["pause.options"], "gear", ButtonStyle.SECONDARY) { game.setScreen(OptionsScreen(game, returnTo = this)) },
                Button(tr["pause.quit"], "home", ButtonStyle.SECONDARY, subtitle = tr["pause.quit.sub"]) { quitToMenu() },
                top = height * 0.62f,
            )
            State.RESULT -> {
                val buttons = resultButtons()
                column(*buttons.toTypedArray(), top = resultButtonsTop())
            }
            else -> Unit
        }
    }

    private fun restartRound() {
        game.save.clearGame()
        if (run.mode == GameMode.CLASSIC || run.mode == GameMode.ARCADE) {
            startRound(Run(run.mode, seed = TimeUtils.millis()))
        } else {
            startRound(Run(run.mode, run.seed, run.level, challengeDay = run.challengeDay))
        }
    }

    private fun quitToMenu() {
        saveGame()
        game.setScreen(MenuScreen(game))
    }

    // --- Mise à jour ------------------------------------------------------------------

    override fun update(dt: Float) {
        super.update(dt)
        overlay.update(dt)
        stateTime += dt
        renderer.update(dt)
        for (i in popAge.indices) popAge[i] += dt
        bannerAge += dt
        refusedAge += dt
        fastHint = max(0f, fastHint - dt)
        queueShift = min(1f, queueShift + dt / 0.2f)
        rotateAnim = min(1f, rotateAnim + dt / 0.14f)

        when (state) {
            State.INTRO -> if (bot != null && stateTime > 1.2f) beginPlay()
            State.PLAYING -> {
                if (gesture == Gesture.FAST) {
                    fastHold += dt
                    if (fastHold >= FAST_HOLD) {
                        session.accelerate()
                        gesture = Gesture.NONE
                    }
                }
                accumulator += dt
                var steps = 0
                while (accumulator >= STEP && steps < 24) {
                    bot?.update(session, STEP)
                    session.update(STEP)
                    accumulator -= STEP
                    steps++
                }
                if (steps == 24) accumulator = 0f
                retryPending(dt)
                handleEvents()
                updateGhostValidity()
                saveTimer += dt
                if (saveTimer > 5f) {
                    saveTimer = 0f
                    saveGame()
                }
                val flowing = session.phase == Phase.FLOWING
                game.audio.hum(if (flowing) 1f else 0f, if (session.fast) 1.5f else 1f)
            }
            State.ENDING -> {
                handleEvents()
                if (stateTime > 1.6f) {
                    state = State.RESULT
                    stateTime = 0f
                    rebuildOverlay()
                }
            }
            State.RESULT -> {
                if (bot != null && stateTime > 2.5f && outcome == RunOutcome.NEXT) {
                    startRound()
                }
            }
            State.PAUSED -> Unit
        }
        val target = (run.totalScore + (if (state == State.RESULT || state == State.ENDING) 0 else session.score)).toFloat()
        shownScore = if (abs(shownScore - target) < 1f) target else Ease.approach(shownScore, target, 9f, dt)
    }

    /** Une pose refusée pendant le court blocage après un remplacement est rejouée dès que possible. */
    private fun retryPending(dt: Float) {
        if (pendingX < 0) return
        pendingAge += dt
        if (pendingAge > 0.6f) {
            pendingX = -1
            return
        }
        if (session.cooldown <= 0f) {
            val x = pendingX
            val y = pendingY
            pendingX = -1
            if (session.refusalAt(x, y) == null) session.place(x, y)
        }
    }

    private fun handleEvents() {
        val audio = game.audio
        while (true) {
            val e = session.pollEvent() ?: break
            when (e) {
                is GameEvent.Placed -> {
                    renderer.onPlaced(e.x, e.y, e.replaced != null, layout)
                    queueShift = 0f
                    if (e.replaced != null) {
                        audio.play(Sfx.REPLACE)
                        haptic(Haptic.REPLACE)
                        popup("−${Scoring.REPLACE_PENALTY}", e.x, e.y, Palette.MAGENTA, big = false)
                    } else {
                        audio.play(Sfx.PLACE, pitch = 0.95f + renderer.particles.random() * 0.1f)
                        haptic(Haptic.PLACE)
                    }
                }
                is GameEvent.Refused -> {
                    if (e.reason == Refusal.COOLDOWN) continue
                    refusedX = e.x
                    refusedY = e.y
                    refusedAge = 0f
                    audio.play(Sfx.REFUSE)
                    haptic(Haptic.ERROR)
                }
                is GameEvent.Rotated -> {
                    rotateAnim = 0f
                    audio.play(Sfx.ROTATE)
                    haptic(Haptic.TICK)
                }
                is GameEvent.CountdownTick -> {
                    audio.play(Sfx.TICK, pitch = 1f + (5 - e.secondsLeft) * 0.08f)
                    if (e.secondsLeft <= 3) haptic(Haptic.TICK)
                }
                GameEvent.FlowStarted -> {
                    audio.play(Sfx.GO)
                    haptic(Haptic.PLACE)
                }
                GameEvent.FastForward -> {
                    audio.play(Sfx.FAST)
                    haptic(Haptic.SUCCESS)
                    showBanner(tr["banner.fast"], Palette.AMBER)
                }
                is GameEvent.ChannelEntered -> Unit
                is GameEvent.ChannelFilled -> {
                    renderer.onFilled(e.x, e.y, e.channel, e.crossBonus, layout)
                    audio.play(Sfx.FILL, pitch = 0.9f + min(e.count, 30) * 0.012f)
                    popup("+${e.points}", e.x, e.y, Palette.CYAN, big = false)
                    if (e.crossBonus) {
                        audio.play(Sfx.CROSS)
                        haptic(Haptic.SUCCESS)
                        popup("+${Scoring.CROSS_BONUS}", e.x, e.y, Palette.AMBER, big = true)
                        showBanner(tr["banner.cross"], Palette.AMBER)
                    }
                }
                GameEvent.TargetReached -> {
                    audio.play(Sfx.TARGET)
                    haptic(Haptic.SUCCESS)
                    showBanner(tr["banner.target"], Palette.GREEN)
                }
                is GameEvent.Leaked -> {
                    renderer.onLeak(e.x, e.y, e.port, layout)
                    audio.play(Sfx.LEAK)
                    haptic(if (session.targetReached) Haptic.PLACE else Haptic.FAIL)
                }
                is GameEvent.DrainReached -> {
                    renderer.onDrain(e.x, e.y, layout)
                    audio.play(Sfx.DRAIN)
                    haptic(Haptic.SUCCESS)
                    popup("+${Scoring.DRAIN_BONUS}", e.x, e.y, Palette.AMBER, big = true)
                }
                is GameEvent.RoundEnded -> onRoundEnded(e.result)
            }
        }
    }

    private fun onRoundEnded(r: RoundResult) {
        cancelGesture()
        result = r
        game.audio.hum(0f)
        val save = game.save
        if (bot == null) save.recordRound(run.mode, r)
        val levelBefore = run.level
        val o = run.record(r)
        outcome = o
        if (bot == null) {
            when (run.mode) {
                GameMode.LEVELS -> {
                    newRecord = r.score > save.best("level-$levelBefore") && r.success
                    save.recordLevel(levelBefore, r)
                }
                GameMode.CHALLENGE -> newRecord = save.recordBest("daily-${run.challengeDay}", r.score)
                else -> if (o == RunOutcome.GAME_OVER) newRecord = save.recordRun(run.mode, run.totalScore, levelBefore)
            }
            if (o == RunOutcome.GAME_OVER || o == RunOutcome.DONE) save.clearGame()
        }
        if (r.success) {
            renderer.startWave()
            renderer.celebrate(layout)
            game.audio.play(Sfx.WIN)
            haptic(Haptic.SUCCESS)
        } else {
            game.audio.play(Sfx.FAIL)
        }
        state = State.ENDING
        stateTime = 0f
    }

    private fun haptic(kind: Haptic) = game.ui.haptic(kind)

    private fun popup(text: String, x: Int, y: Int, color: Color, big: Boolean) {
        val i = popCursor
        popCursor = (popCursor + 1) % popText.size
        popText[i] = text
        popX[i] = layout.cellX(x) + layout.cell / 2
        popY[i] = layout.cellY(y) + layout.cell * 0.6f
        popAge[i] = 0f
        popBig[i] = big
        popColor[i] = color
    }

    private fun showBanner(text: String, color: Color) {
        banner = text
        bannerColor = color
        bannerAge = 0f
    }

    // --- Toucher ----------------------------------------------------------------------

    override fun touchDown(x: Float, y: Float, pointer: Int): Boolean {
        when (state) {
            State.PAUSED, State.RESULT -> return overlay.touchDown(x, y, pointer, game.ui)
            State.ENDING -> return true
            State.INTRO -> {
                if (widgets.touchDown(x, y, pointer, game.ui)) return true
                if (stateTime > 0.25f) beginPlay()
                return true
            }
            State.PLAYING -> Unit
        }
        if (widgets.touchDown(x, y, pointer, game.ui)) return true
        if (gesture != Gesture.NONE) return true
        gesturePointer = pointer
        downX = x
        downY = y
        val pad = 12f * u
        when {
            expanded(layout.fast, pad).contains(x, y) -> {
                gesture = Gesture.FAST
                fastHold = 0f
                game.ui.haptic(Haptic.TICK)
            }
            spec.allowRotation && expanded(layout.rotate, pad).contains(x, y) -> gesture = Gesture.ROTATE
            expanded(layout.next, pad).contains(x, y) -> gesture = Gesture.QUEUE_PRESS
            layout.board.contains(x, y) -> {
                gesture = Gesture.AIM
                aimAt(x, y, held = false)
            }
            else -> gesture = Gesture.NONE
        }
        return true
    }

    override fun touchDragged(x: Float, y: Float, pointer: Int): Boolean {
        if (state != State.PLAYING) return if (state == State.PAUSED || state == State.RESULT) overlay.touchDragged(x, y, pointer, game.ui) else widgets.touchDragged(x, y, pointer, game.ui)
        if (widgets.touchDragged(x, y, pointer, game.ui)) return true
        if (pointer != gesturePointer) return true
        when (gesture) {
            Gesture.AIM -> aimAt(x, y, held = false)
            Gesture.QUEUE_PRESS -> if (abs(x - downX) + abs(y - downY) > 18f * u) {
                gesture = Gesture.DRAG
                game.ui.haptic(Haptic.TICK)
                aimAt(x, y, held = true)
            }
            Gesture.DRAG -> aimAt(x, y, held = true)
            Gesture.FAST -> if (!expanded(layout.fast, 40f * u).contains(x, y)) gesture = Gesture.NONE
            else -> Unit
        }
        return true
    }

    override fun touchUp(x: Float, y: Float, pointer: Int): Boolean {
        if (state == State.PAUSED || state == State.RESULT) return overlay.touchUp(x, y, pointer, game.ui)
        if (widgets.touchUp(x, y, pointer, game.ui)) return true
        if (pointer != gesturePointer) return true
        when (gesture) {
            Gesture.AIM, Gesture.DRAG -> {
                if (state == State.PLAYING && ghost.x >= 0 && ghost.valid) place(ghost.x, ghost.y)
                else if (state == State.PLAYING && ghost.x >= 0) session.place(ghost.x, ghost.y) // déclenche le retour d'erreur
            }
            Gesture.QUEUE_PRESS -> if (spec.allowRotation) session.rotateNext() else showQueueHint()
            Gesture.ROTATE -> if (expanded(layout.rotate, 20f * u).contains(x, y)) session.rotateNext()
            Gesture.FAST -> if (fastHold < FAST_HOLD) fastHint = 1.6f
            Gesture.NONE -> Unit
        }
        cancelGesture()
        return true
    }

    private fun place(x: Int, y: Int) {
        if (!session.place(x, y) && session.refusalAt(x, y) == Refusal.COOLDOWN) {
            pendingX = x
            pendingY = y
            pendingAge = 0f
        }
    }

    private fun showQueueHint() {
        showBanner(tr["banner.queueHint"], Palette.CYAN)
    }

    private fun aimAt(x: Float, y: Float, held: Boolean) {
        ghost.active = true
        ghost.piece = session.queue.next
        ghost.heldX = if (held) x else Float.NaN
        ghost.heldY = if (held) y else Float.NaN
        val cx = layout.colAt(x)
        val cy = layout.rowAt(y)
        val inside = layout.board.contains(x, y)
        ghost.x = if (inside) cx else -1
        ghost.y = if (inside) cy else -1
        updateGhostValidity()
    }

    private fun updateGhostValidity() {
        if (!ghost.active || ghost.x < 0) return
        val refusal = session.refusalAt(ghost.x, ghost.y)
        ghost.valid = refusal == null || refusal == Refusal.COOLDOWN
        ghost.replacing = ghost.valid && session.wouldReplace(ghost.x, ghost.y)
        ghost.piece = session.queue.next
    }

    private fun cancelGesture() {
        gesture = Gesture.NONE
        gesturePointer = -1
        ghost.active = false
        ghost.x = -1
        ghost.heldX = Float.NaN
    }

    private val tmpRect = Rectangle()
    private fun expanded(r: Rectangle, pad: Float): Rectangle = tmpRect.set(r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad)

    override fun back(): Boolean {
        when (state) {
            State.PLAYING, State.INTRO -> openPause()
            State.PAUSED -> resumePlay()
            State.RESULT -> quitAfterResult()
            State.ENDING -> Unit
        }
        return true
    }

    private fun quitAfterResult() {
        if (outcome == RunOutcome.NEXT || outcome == RunOutcome.RETRY) saveNextRound()
        game.setScreen(if (run.mode == GameMode.LEVELS) LevelSelectScreen(game) else MenuScreen(game))
    }

    /** Entre deux manches, la partie continue d'exister : on sauvegarde la suivante. */
    private fun saveNextRound() {
        if (bot != null) return
        val next = GameSession(run.currentSpec())
        game.save.saveGame(SavedGame(run.state(), next.spec, next.capture()))
    }

    override fun pause() {
        if (state == State.PLAYING || state == State.INTRO) openPause() else if (state != State.RESULT) saveGame()
    }

    // --- Résultats ---------------------------------------------------------------------

    private fun resultButtons(): List<Button> {
        val list = ArrayList<Button>()
        val r = result ?: return list
        when (outcome) {
            RunOutcome.NEXT -> list += Button(
                tr[if (run.mode == GameMode.ARCADE) "result.nextWave" else "result.nextRound"],
                "play",
            ) { startRound() }
            RunOutcome.RETRY -> list += Button(tr["result.continue"], "heart", subtitle = tr.plural("result.livesLeft", run.lives)) { startRound() }
            RunOutcome.GAME_OVER -> list += Button(tr["result.newGame"], "restart") {
                startRound(Run(run.mode, seed = TimeUtils.millis()))
            }
            RunOutcome.DONE -> {
                if (run.mode == GameMode.LEVELS) {
                    val n = run.level
                    if (r.success && n < Campaign.size) {
                        list += Button(tr["result.nextLevel"], "play") { startRound(Run(GameMode.LEVELS, run.seed, n + 1)) }
                        list += Button(tr["result.replay"], "restart", ButtonStyle.SECONDARY) { startRound(Run(GameMode.LEVELS, run.seed, n)) }
                    } else {
                        list += Button(tr[if (r.success) "result.replay" else "result.retry"], "restart") { startRound(Run(GameMode.LEVELS, run.seed, n)) }
                    }
                } else {
                    list += Button(tr["result.replay"], "restart") { startRound(Run(GameMode.CHALLENGE, run.seed, challengeDay = run.challengeDay)) }
                }
            }
            null -> Unit
        }
        list += Button(tr[if (run.mode == GameMode.LEVELS) "result.levels" else "result.menu"], if (run.mode == GameMode.LEVELS) "levels" else "home", ButtonStyle.SECONDARY) { quitAfterResult() }
        return list
    }

    private fun resultButtonsTop(): Float = height * 0.46f

    // --- Dessin -------------------------------------------------------------------------

    override fun render(batch: SpriteBatch) {
        val sx = renderer.shakeX
        val sy = renderer.shakeY
        batch.begin()
        game.backdrop.draw(batch, width, height, game.time, u)
        batch.end()

        batch.transformMatrix.setToTranslation(sx, sy, 0f)
        batch.begin()
        renderer.drawBoard(batch, session, layout, ghost, game.save.settings.nextCellHint)
        drawRefused(batch)
        batch.end()

        if (game.save.settings.glow && game.bloom.available) {
            game.bloom.capture(batch) { renderer.drawGlow(batch, session, layout) }
            batch.transformMatrix.idt()
            game.bloom.composite(batch, width, height, 0.9f)
            batch.transformMatrix.setToTranslation(sx, sy, 0f)
        }
        batch.begin()
        renderer.drawParticles(batch)
        drawPopups(batch)
        batch.end()
        batch.transformMatrix.idt()

        batch.begin()
        game.backdrop.vignette(batch, width, height, 0.55f)
        drawHud(batch)
        widgets.draw(game.ui, 1f)
        drawBanner(batch)
        when (state) {
            State.INTRO -> drawIntro(batch)
            State.PAUSED -> drawPause(batch)
            State.RESULT, State.ENDING -> drawResult(batch)
            State.PLAYING -> Unit
        }
        batch.end()
    }

    private fun drawRefused(batch: SpriteBatch) {
        if (refusedAge > 0.45f || refusedX < 0) return
        val c = layout.cell
        val a = 1f - refusedAge / 0.45f
        val x = layout.cellX(refusedX) + (kotlin.math.sin(refusedAge * 60f) * c * 0.04f * a)
        game.ui.rect(x, layout.cellY(refusedY), c, c, Palette.MAGENTA, 0.25f * a)
    }

    private fun drawPopups(batch: SpriteBatch) {
        val f = game.assets.fonts
        for (i in popText.indices) {
            val age = popAge[i]
            val t = popText[i] ?: continue
            val life = if (popBig[i]) 1.3f else 0.85f
            if (age >= life) continue
            val p = age / life
            val a = if (p < 0.15f) p / 0.15f else 1f - Ease.inQuad((p - 0.15f) / 0.85f)
            val rise = Ease.outCubic(p) * 70f * u
            game.ui.text(if (popBig[i]) f.title else f.small, t, popX[i], popY[i] + rise, popColor[i]!!, a, Align.center)
        }
    }

    private fun drawBanner(batch: SpriteBatch) {
        val text = banner ?: return
        if (bannerAge > 1.5f) return
        val p = bannerAge / 1.5f
        val a = if (p < 0.12f) p / 0.12f else 1f - Ease.inQuad((p - 0.12f) / 0.88f)
        val y = layout.board.y + layout.board.height * 0.62f + Ease.outCubic(min(1f, bannerAge * 4f)) * 20f * u
        val f = game.assets.fonts.title
        val maxText = width * 0.92f - 60f * u
        val w = min(game.ui.measure(f, text), maxText) + 60f * u
        game.ui.patch(game.assets.pill, width / 2 - w / 2, y - f.capHeight - 28f * u, w, f.capHeight + 52f * u, Color.WHITE, a * 0.9f)
        game.ui.text(f, text, width / 2, y, bannerColor, a, Align.center, maxWidth = maxText)
    }

    private fun drawHud(batch: SpriteBatch) {
        val ui = game.ui
        val f = game.assets.fonts
        val assets = game.assets

        // Titre de la manche.
        val t = layout.title
        if (hudFor != spec.id) {
            hudFor = spec.id
            hudMode = modeHeading()
            hudTitle = if (run.mode == GameMode.LEVELS || run.mode == GameMode.CHALLENGE) levelTitle().uppercase() else modeName()
        }
        // Les vies changent dès le bilan d'une manche ratée, avant le plateau suivant.
        if (hudLivesFor != run.lives) {
            hudLivesFor = run.lives
            hudLives = livesLabel()
        }
        ui.text(f.body, hudMode, t.x, t.y + t.height * 0.62f + f.body.capHeight / 2, Palette.TEXT, 1f, maxWidth = t.width)
        ui.text(f.small, hudTitle, t.x, t.y + t.height * 0.24f + f.small.capHeight / 2, Palette.TEXT_DIM, 1f, maxWidth = t.width)

        // Score.
        val sc = shownScore.toInt()
        if (sc != cachedScore) {
            cachedScore = sc
            scoreText = tr.number(sc)
        }
        val s = layout.score
        ui.text(f.title, scoreText, s.x + s.width, s.y + s.height * 0.58f + f.title.capHeight / 2, Palette.AMBER_SOFT, 1f, Align.right)
        ui.text(f.small, if (run.mode == GameMode.ARCADE) hudLives else tr["hud.score"], s.x + s.width, s.y + s.height * 0.12f + f.small.capHeight / 2, Palette.TEXT_DIM, 1f, Align.right, maxWidth = s.width)

        drawGauge()
        drawRail(batch)
    }

    private var hudFor: String? = null
    private var hudMode = ""
    private var hudTitle = ""
    private var hudLives = ""
    private var hudLivesFor = -1
    private var cachedRemaining = -1
    private var remainingText = ""

    private fun modeName(): String = when (run.mode) {
        GameMode.CLASSIC -> tr["hud.classic"]
        GameMode.ARCADE -> tr["hud.arcade"]
        else -> ""
    }

    /** « MANCHE 3 », « VAGUE 2 », « NIVEAU 8 », « DÉFI DU JOUR ». */
    private fun modeHeading(): String = when (run.mode) {
        GameMode.CLASSIC -> tr.format("hud.round", "n" to run.level)
        GameMode.ARCADE -> tr.format("hud.wave", "n" to run.level)
        GameMode.LEVELS -> tr.format("hud.level", "n" to run.level)
        GameMode.CHALLENGE -> tr["hud.daily"]
    }

    /** Titre traduit du niveau de campagne ou du défi du jour. */
    private fun levelTitle(): String = when (run.mode) {
        GameMode.LEVELS -> tr["level.${run.level}.title"]
        GameMode.CHALLENGE -> tr["challenge.${LevelGenerator.challengeKind(run.challengeDay).key}.title"]
        else -> ""
    }

    private fun livesLabel(): String {
        val dots = when (run.lives) {
            3 -> "  • • •"
            2 -> "  • •"
            1 -> "  •"
            else -> ""
        }
        return tr.plural("hud.lives", run.lives) + dots
    }

    private fun drawGauge() {
        val ui = game.ui
        val f = game.assets.fonts
        val g = layout.gauge
        val timerW = g.width * 0.34f
        val gw = g.width - timerW - 16f * u
        val barH = 18f * u
        val barY = g.y + 4f * u
        val target = spec.target
        val filled = session.filled
        val key = filled * 1000 + target
        if (key != cachedGauge) {
            cachedGauge = key
            gaugeText = "$filled / $target"
        }
        val reached = filled >= target
        val countW = ui.text(f.small, gaugeText, g.x + gw, g.y + g.height - 2f * u, if (reached) Palette.GREEN else Palette.CYAN, 1f, Align.right)
        ui.text(f.small, tr["hud.length"], g.x, g.y + g.height - 2f * u, Palette.TEXT_DIM, maxWidth = gw - countW - 16f * u)
        ui.patch(game.assets.barTrack, g.x, barY, gw, barH)
        val max3 = RoundResult.starThreshold(target, 3)
        val scaleMax = if (run.mode == GameMode.LEVELS) max3.toFloat() else target.toFloat()
        val frac = min(1f, filled / scaleMax)
        if (frac > 0f) ui.patch(game.assets.barFill, g.x, barY, max(barH, gw * frac), barH, if (reached) Palette.GREEN else Palette.CYAN, 0.95f)
        if (run.mode == GameMode.LEVELS) {
            // Repères des étoiles.
            for (stars in 1..3) {
                val th = RoundResult.starThreshold(target, stars)
                val mx = g.x + gw * min(1f, th / scaleMax)
                val on = filled >= th
                ui.icon(game.assets.icon("star"), mx - 2f * u, barY + barH / 2, 30f * u, if (on) Palette.AMBER else Palette.TEXT_FAINT, 1f)
            }
        }

        // Puce de droite : compte à rebours, puis débit.
        val tx = g.x + g.width - timerW
        val chipH = g.height
        ui.patch(game.assets.pill, tx, g.y, timerW, chipH)
        val cy = g.y + chipH / 2 + f.body.capHeight / 2
        when (session.phase) {
            Phase.COUNTDOWN -> {
                val secs = kotlin.math.ceil(session.countdownLeft.toDouble()).toInt()
                if (secs != cachedTimer) {
                    cachedTimer = secs
                    timerText = tr.format("hud.countdown", "n" to secs)
                }
                val urgent = secs <= 5
                val a = if (urgent) 0.6f + 0.4f * Ease.pulse(renderer.time, 0.5f) else 1f
                ui.text(f.body, timerText, tx + timerW / 2, cy, if (urgent) Palette.MAGENTA else Palette.AMBER, a, Align.center, maxWidth = timerW - 28f * u)
            }
            else -> {
                val label = tr[if (session.fast) "hud.fast" else "hud.flowing"]
                ui.text(f.body, label, tx + timerW / 2, cy, if (session.fast) Palette.AMBER else Palette.CYAN, 1f, Align.center, maxWidth = timerW - 28f * u)
            }
        }
    }

    private fun drawRail(batch: SpriteBatch) {
        val ui = game.ui
        val f = game.assets.fonts
        val assets = game.assets
        val r = layout.rail
        val pad = 10f * u
        ui.patch(assets.panel, r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad, Color.WHITE, 0.9f)

        // Prochaine pièce : grande, cadre cyan, glisse depuis la droite quand la file avance.
        val n = layout.next
        val shift = 1f - Ease.outCubic(queueShift)
        val step = layout.upcoming[0].x - n.x
        ui.patch(assets.pill, n.x - 4f * u, n.y - 4f * u, n.width + 8f * u, n.height + 8f * u, Palette.CYAN, 0.5f)
        val next = session.queue.next
        val held = gesture == Gesture.DRAG
        if (next != null) {
            val rot = if (rotateAnim < 1f) 90f * (1f - Ease.outBack(rotateAnim)) else 0f
            val slotShrink = n.width * 0.08f
            renderer.drawPiece(
                batch, next,
                n.x + slotShrink + shift * step, n.y + slotShrink, n.width - 2 * slotShrink,
                alpha = if (held) 0.3f else 1f, rotation = rot,
            )
        } else {
            ui.text(f.small, tr["hud.empty"], n.x + n.width / 2, n.y + n.height / 2 + f.small.capHeight / 2, Palette.TEXT_FAINT, 1f, Align.center, maxWidth = n.width)
        }
        ui.text(f.small, tr["hud.next"], n.x + n.width / 2, n.y - 4f * u, Palette.CYAN, 0.8f, Align.center, maxWidth = n.width + 24f * u)

        for (i in 0 until 4) {
            val slot = layout.upcoming[i]
            val p = session.queue[i + 1] ?: continue
            val a = 0.95f - i * 0.16f
            val ox = shift * (if (i == 0) slot.width + 8f * u else layout.upcoming[1].x - layout.upcoming[0].x)
            val inset = slot.width * 0.09f
            renderer.drawPiece(batch, p, slot.x + ox + inset, slot.y + inset, slot.width - 2 * inset, alpha = a)
        }
        if (spec.pieceLimit > 0) {
            val rem = session.queue.remaining
            if (rem != cachedRemaining) {
                cachedRemaining = rem
                remainingText = tr.format("hud.pieces", "n" to rem)
            }
            ui.text(f.small, remainingText, layout.upcoming[0].x, r.y + r.height - 2f * u, Palette.AMBER, 1f)
        }

        // Rotation.
        if (spec.allowRotation) {
            val rr = layout.rotate
            ui.patch(if (gesture == Gesture.ROTATE) assets.buttonDown else assets.buttonCyan, rr.x, rr.y, rr.width, rr.height)
            ui.icon(assets.icon("rotate"), rr.x + rr.width / 2, rr.y + rr.height / 2, rr.width * 0.5f)
        }

        // Accélérer : bouton rond, anneau de maintien.
        val fb = layout.fast
        val pressed = gesture == Gesture.FAST
        val used = session.fast || session.isFinished
        ui.patch(if (pressed) assets.buttonDown else assets.button, fb.x, fb.y, fb.width, fb.height, if (used) Palette.TEXT_FAINT else Color.WHITE, if (used) 0.5f else 1f)
        ui.icon(assets.icon("fast"), fb.x + fb.width / 2, fb.y + fb.height / 2 + 8f * u, fb.width * 0.42f, if (used) Palette.TEXT_FAINT else Palette.AMBER, 1f)
        val fastLabel = tr[if (session.phase == Phase.COUNTDOWN) "hud.launch" else "hud.accelerate"]
        val fit = min(1f, fb.width * 0.84f / ui.measure(f.small, fastLabel))
        f.small.data.setScale(fit)
        ui.text(f.small, fastLabel, fb.x + fb.width / 2, fb.y + fb.height * 0.26f, Palette.TEXT_DIM, if (used) 0.4f else 1f, Align.center)
        f.small.data.setScale(1f)
        if (pressed) {
            val prog = min(1f, fastHold / FAST_HOLD)
            val cx = fb.x + fb.width / 2
            val cy = fb.y + fb.height / 2
            val rad = fb.width * 0.62f
            val dots = 28
            val lit = (prog * dots).toInt()
            for (k in 0 until dots) {
                val ang = Math.PI / 2 - k * 2 * Math.PI / dots
                val d = 9f * u
                batch.setColor(Palette.AMBER.r, Palette.AMBER.g, Palette.AMBER.b, if (k < lit) 1f else 0.15f)
                batch.draw(assets.dot, cx + (kotlin.math.cos(ang) * rad).toFloat() - d / 2, cy + (kotlin.math.sin(ang) * rad).toFloat() - d / 2, d, d)
            }
            batch.setColor(1f, 1f, 1f, 1f)
        }
        if (fastHint > 0f) {
            val a = min(1f, fastHint * 2f)
            ui.text(f.small, tr["hud.holdHint"], fb.x + fb.width, fb.y + fb.height + 22f * u, Palette.AMBER_SOFT, a, Align.right, maxWidth = fb.x + fb.width - r.x)
        }
    }

    private fun dim(alpha: Float) = game.ui.rect(0f, 0f, width, height, Palette.SHADE, alpha)

    private fun drawIntro(batch: SpriteBatch) {
        val ui = game.ui
        val f = game.assets.fonts
        val a = Ease.outCubic(stateTime / 0.35f)
        dim(0.55f * a)
        val pw = min(width * 0.86f, 620f * u)
        val lines = introLines()
        // Hauteur réelle des lignes (certaines passent sur deux lignes).
        val textW = pw - 80f * u
        var linesH = 0f
        for (line in lines) {
            ui.layout.setText(f.small, line, Palette.TEXT_DIM, textW, Align.left, true)
            linesH += ui.layout.height + 18f * u
        }
        val ph = 250f * u + linesH
        val px = width / 2 - pw / 2
        val py = layout.board.y + layout.board.height / 2 - ph / 2 + (1f - a) * 40f * u
        ui.patch(game.assets.panel, px, py, pw, ph, Color.WHITE, a)
        val inner = pw - 60f * u
        var y = py + ph - 44f * u
        ui.text(f.small, modeHeading(), width / 2, y, Palette.CYAN, a, Align.center, maxWidth = inner)
        y -= 20f * u + f.title.capHeight
        val bigTitle = when (run.mode) {
            GameMode.CLASSIC -> tr["mode.classic"]
            GameMode.ARCADE -> tr["mode.arcade"]
            else -> levelTitle()
        }
        ui.text(f.title, bigTitle, width / 2, y + f.title.capHeight, Palette.TEXT, a, Align.center, maxWidth = inner)
        y -= 34f * u
        ui.text(f.body, tr.plural("intro.target", spec.target), width / 2, y, Palette.AMBER_SOFT, a, Align.center, maxWidth = inner)
        y -= 26f * u
        y -= 16f * u
        for (line in lines) {
            ui.text(f.small, line, px + 40f * u, y, Palette.TEXT_DIM, a, Align.left, textW, wrap = true)
            y -= ui.layout.height + 18f * u
        }
        val blink = 0.5f + 0.5f * Ease.pulse(stateTime, 1.2f)
        ui.text(f.body, tr["intro.tap"], width / 2, py + 50f * u, Palette.TEXT, a * blink, Align.center, maxWidth = inner)
    }

    private var introCache: List<String>? = null
    private var introFor: String? = null

    private fun introLines(): List<String> {
        if (introFor == spec.id && introCache != null) return introCache!!
        val l = ArrayList<String>()
        if (run.mode == GameMode.LEVELS) l += tr["level.${run.level}.hint"]
        if (run.mode == GameMode.CHALLENGE) l += tr["challenge.${LevelGenerator.challengeKind(run.challengeDay).key}.desc"]
        l += tr.format("intro.timing", "countdown" to spec.countdown.toInt(), "pace" to tr.decimal(spec.secondsPerCell))
        if (spec.allowRotation) l += tr["intro.rotate"]
        else if (run.mode == GameMode.CLASSIC && run.level == 1) l += tr["intro.noRotate"]
        if (spec.requireDrain) l += tr["intro.drain"]
        if (spec.pieceLimit > 0) l += tr.plural("intro.limit", spec.pieceLimit)
        if (run.mode == GameMode.ARCADE) l += tr.format("intro.lives", "n" to run.lives)
        introCache = l
        introFor = spec.id
        return l
    }

    private fun drawPause(batch: SpriteBatch) {
        val ui = game.ui
        val f = game.assets.fonts
        val a = Ease.outCubic(stateTime / 0.25f)
        dim(0.72f * a)
        ui.text(f.big, tr["pause.title"], width / 2, height * 0.74f, Palette.TEXT, a, Align.center, maxWidth = width * 0.9f)
        val line = tr.format("pause.score", "mode" to modeTitle(), "score" to tr.number(run.totalScore + session.score))
        ui.text(f.small, line, width / 2, height * 0.74f - f.big.capHeight - 22f * u, Palette.TEXT_DIM, a, Align.center, maxWidth = width * 0.9f)
        overlay.draw(ui, a)
    }

    private fun modeTitle(): String = when (run.mode) {
        GameMode.CLASSIC -> tr.format("mode.classic.round", "n" to run.level)
        GameMode.ARCADE -> tr.format("mode.arcade.wave", "n" to run.level)
        GameMode.LEVELS -> tr.format("mode.level", "n" to run.level, "title" to levelTitle())
        GameMode.CHALLENGE -> tr["mode.daily"]
    }

    private fun drawResult(batch: SpriteBatch) {
        val r = result ?: return
        val ui = game.ui
        val f = game.assets.fonts
        val ending = state == State.ENDING
        val a = if (ending) 0f else Ease.outCubic(stateTime / 0.35f)
        if (ending) return
        dim(0.70f * a)
        val pw = min(width * 0.9f, 640f * u)
        val top = height * 0.86f
        val panelTop = top + 70f * u
        // Le panneau suit les boutons, eux-mêmes placés juste sous le contenu (image précédente).
        val panelBottom = (overlay.widgets.lastOrNull()?.bounds?.y ?: (height * 0.2f)) - 24f * u
        game.ui.patch(game.assets.panel, width / 2 - pw / 2 - 10f * u, panelBottom, pw + 20f * u, panelTop - panelBottom, Color.WHITE, a * 0.96f)
        val gameOver = outcome == RunOutcome.GAME_OVER
        val title = when {
            gameOver -> tr["result.gameOver"]
            r.success -> tr[if (r.drainReached) "result.drain" else "result.success"]
            else -> tr["result.leak"]
        }
        val col = if (r.success) Palette.GREEN else Palette.MAGENTA
        val s = 0.85f + 0.15f * Ease.outBack(stateTime / 0.4f)
        f.big.data.setScale(s)
        ui.text(f.big, title, width / 2, top, col, a, Align.center, maxWidth = pw - 40f * u)
        f.big.data.setScale(1f)
        var y = top - f.big.capHeight - 40f * u
        if (run.mode == GameMode.LEVELS || run.mode == GameMode.CHALLENGE) {
            // Étoiles, révélées une à une.
            val size = 92f * u
            for (k in 0 until 3) {
                val appear = Ease.outBack((stateTime - 0.3f - k * 0.22f) / 0.3f, 2.5f)
                val on = k < r.stars
                val sx = width / 2 + (k - 1) * size * 1.15f
                val sz = size * (if (on) appear.coerceAtLeast(0f) else 1f)
                ui.icon(game.assets.icon(if (on) "star" else "star_outline"), sx, y - size / 2, if (on) sz else size * 0.9f, if (on) Palette.AMBER else Palette.TEXT_FAINT, a)
                if (on && stateTime > 0.3f + k * 0.22f && stateTime - dt() <= 0.3f + k * 0.22f) game.audio.play(Sfx.STAR, pitch = 1f + k * 0.12f)
            }
            y -= size + 22f * u
        }
        val lx = width / 2 - pw / 2 + 30f * u
        val rx = width / 2 + pw / 2 - 30f * u
        fun line(label: String, value: String, color: Color = Palette.TEXT) {
            val vw = ui.text(f.body, value, rx, y, color, a, Align.right)
            ui.text(f.body, label, lx, y, Palette.TEXT_DIM, a, maxWidth = rx - lx - vw - 20f * u)
            y -= f.body.lineHeight * 1.12f
        }
        line(tr["result.filled"], "${r.filled} / ${r.target}", if (r.filled >= r.target) Palette.GREEN else Palette.MAGENTA)
        line(tr["result.pipes"], "+${tr.number(r.pipePoints)}")
        if (r.crossBonus > 0) line(tr.format("result.crossings", "n" to r.crossings), "+${tr.number(r.crossBonus)}", Palette.AMBER)
        if (r.drainBonus > 0) line(tr["result.drainBonus"], "+${tr.number(r.drainBonus)}", Palette.AMBER)
        if (r.replacePenalty > 0) line(tr["result.replacements"], "−${tr.number(r.replacePenalty)}", Palette.MAGENTA)
        if (r.unusedPenalty > 0) line(tr.format("result.unused", "n" to r.unusedPieces), "−${tr.number(r.unusedPenalty)}", Palette.MAGENTA)
        y -= 6f * u
        ui.rect(lx, y + f.body.lineHeight * 0.35f, rx - lx, 2f * u, Palette.TEXT_FAINT, a)
        y -= 8f * u
        line(tr["result.round"], tr.number(r.score), Palette.AMBER_SOFT)
        if (run.mode == GameMode.CLASSIC || run.mode == GameMode.ARCADE) line(tr["result.total"], tr.number(run.totalScore), Palette.AMBER_SOFT)
        if (newRecord) {
            val blink = 0.6f + 0.4f * Ease.pulse(stateTime, 0.8f)
            ui.text(f.body, tr["result.record"], width / 2, y - 10f * u, Palette.AMBER, a * blink, Align.center, maxWidth = pw - 40f * u)
            y -= f.body.lineHeight * 1.4f
        }
        var total = 0f
        for (w in overlay.widgets) total += w.bounds.height + 18f * u
        var by = max(y - 36f * u, game.insetBottom + 24f * u + total)
        for (w in overlay.widgets) {
            w.bounds.y = by - w.bounds.height
            by -= w.bounds.height + 18f * u
        }
        overlay.draw(ui, a)
    }

    private fun dt(): Float = Gdx.graphics.deltaTime

    companion object {
        const val STEP = 1f / 120f
        const val FAST_HOLD = 0.4f
    }
}
