package dev.stefan.fluxline.core

import kotlin.math.max

enum class Phase { COUNTDOWN, FLOWING, FINISHED }

/** Comment se termine le réseau en aval du fluide (voir [GameSession.trail]). */
enum class TrailEnd {
    /** Une case vide attend une pièce. */
    EMPTY_CELL,
    /** Une pièce occupe la case mais n'a pas de port compatible (ou est déjà remplie). */
    BLOCKED,
    /** Le réseau sort du plateau. */
    EDGE,
    /** Le réseau mène à l'arrivée. */
    DRAIN,
}

/**
 * Où le fluide finira si rien ne change : longueur du réseau déjà posé en aval
 * du fluide, et la case où ce réseau s'arrête. Pour [TrailEnd.EMPTY_CELL] et
 * [TrailEnd.BLOCKED], (x, y) est la case concernée et [port] le côté par lequel
 * le fluide y arriverait ; pour [TrailEnd.EDGE], (x, y) est la dernière case du
 * réseau et [port] le côté par lequel il sort.
 */
data class Trail(val length: Int, val end: TrailEnd, val x: Int, val y: Int, val port: Direction?)

/** Version réutilisable de [Trail] pour la boucle de rendu. */
class TrailBuffer {
    var length = 0
    var end = TrailEnd.EDGE
    var x = 0
    var y = 0
    var port: Direction? = null

    fun set(length: Int, end: TrailEnd, x: Int, y: Int, port: Direction?): TrailBuffer {
        this.length = length
        this.end = end
        this.x = x
        this.y = y
        this.port = port
        return this
    }
}

/**
 * Une manche : le plateau, le distributeur, le fluide et le score.
 *
 * La simulation est déterministe : avancée par [update] à pas fixe, une même
 * suite d'actions produit toujours le même résultat. Elle n'alloue rien en
 * régime permanent, sauf les événements, émis à rythme humain.
 */
class GameSession(val spec: LevelSpec) {
    val board = Board(spec.cols, spec.rows)
    val queue = PieceQueue(spec.queueSeed, VISIBLE_PIECES, spec.pieceLimit)

    var phase: Phase = Phase.COUNTDOWN
        private set
    var countdownLeft: Float = spec.countdown
        private set
    /** Nombre de tuyaux remplis (la longueur qui compte pour l'objectif). */
    var filled: Int = 0
        private set
    var fast: Boolean = false
        private set
    var cooldown: Float = 0f
        private set
    var elapsed: Float = 0f
        private set
    var result: RoundResult? = null
        private set

    // Tête du fluide : le conduit en cours de remplissage.
    var headX: Int = 0
        private set
    var headY: Int = 0
        private set
    var headChannel: Int = 0
        private set
    var headEntry: Direction? = null
        private set
    var headProgress: Float = 0f
        private set

    private var pipePoints = 0
    private var crossBonusPoints = 0
    private var drainBonusPoints = 0
    private var replacePenalty = 0
    private var crossings = 0
    private var replacements = 0
    private var piecesPlaced = 0
    private var drainReached = false
    private var targetAnnounced = false
    private var lastTick = Int.MAX_VALUE

    private val pending = ArrayDeque<GameEvent>()
    private val visited = BooleanArray(board.size * 2)

    init {
        board.loadLayout(spec)
        val (sx, sy) = board.findSource()
        headX = sx
        headY = sy
    }

    /** Score courant de la manche (les pénalités de fin ne sont pas encore déduites). */
    val score: Int get() = max(0, pipePoints + crossBonusPoints + drainBonusPoints - replacePenalty)

    val isFinished: Boolean get() = phase == Phase.FINISHED

    val targetReached: Boolean get() = filled >= spec.target

    fun pollEvent(): GameEvent? = pending.removeFirstOrNull()

    // --- Actions du joueur -------------------------------------------------------

    /** Pourquoi la prochaine pièce ne peut pas être posée en (x, y), ou null si elle le peut. */
    fun refusalAt(x: Int, y: Int): Refusal? {
        require(board.inBounds(x, y)) { "case hors plateau ($x, $y)" }
        if (phase == Phase.FINISHED) return Refusal.FINISHED
        if (board.piece(x, y) != null && board.isFixed(x, y)) return Refusal.FIXED
        if (board.isTouched(x, y)) return Refusal.FLUID
        if (queue.next == null) return Refusal.EMPTY_QUEUE
        if (cooldown > 0f) return Refusal.COOLDOWN
        return null
    }

    /** Vrai si poser en (x, y) remplacerait une pièce existante. */
    fun wouldReplace(x: Int, y: Int): Boolean = board.piece(x, y) != null && !board.isFixed(x, y)

    /** Pose la prochaine pièce en (x, y). Renvoie faux (et émet [GameEvent.Refused]) si c'est impossible. */
    fun place(x: Int, y: Int): Boolean {
        val refusal = refusalAt(x, y)
        if (refusal != null) {
            pending.addLast(GameEvent.Refused(x, y, refusal))
            return false
        }
        val previous = board.piece(x, y)
        val piece = queue.pop()!!
        board.setPiece(x, y, piece, isFixed = false, byPlayer = true)
        piecesPlaced++
        if (previous != null) {
            replacements++
            replacePenalty += Scoring.REPLACE_PENALTY
            cooldown = Scoring.REPLACE_COOLDOWN
        }
        pending.addLast(GameEvent.Placed(x, y, piece, previous))
        return true
    }

    /** Fait pivoter la prochaine pièce (modes qui l'autorisent). */
    fun rotateNext(): Boolean {
        if (!spec.allowRotation || phase == Phase.FINISHED) return false
        val rotated = queue.rotateNext() ?: return false
        pending.addLast(GameEvent.Rotated(rotated))
        return true
    }

    /**
     * Accélère le fluide jusqu'à la fin de la manche (et le lâche immédiatement
     * s'il attendait encore). Les tuyaux remplis en accéléré rapportent double.
     */
    fun accelerate() {
        if (phase == Phase.FINISHED || fast) return
        fast = true
        if (phase == Phase.COUNTDOWN) startFlow()
        pending.addLast(GameEvent.FastForward)
    }

    // --- Simulation --------------------------------------------------------------

    fun update(dt: Float) {
        if (phase == Phase.FINISHED || dt <= 0f) return
        elapsed += dt
        if (cooldown > 0f) cooldown = max(0f, cooldown - dt)
        when (phase) {
            Phase.COUNTDOWN -> {
                countdownLeft -= dt
                val whole = kotlin.math.ceil(countdownLeft.toDouble()).toInt()
                if (whole in 1..COUNTDOWN_TICKS && whole < lastTick) {
                    lastTick = whole
                    pending.addLast(GameEvent.CountdownTick(whole))
                }
                if (countdownLeft <= 0f) {
                    val overshoot = -countdownLeft
                    startFlow()
                    advanceFlow(overshoot)
                }
            }
            Phase.FLOWING -> advanceFlow(dt)
            Phase.FINISHED -> Unit
        }
    }

    private fun startFlow() {
        countdownLeft = 0f
        phase = Phase.FLOWING
        board.startChannel(headX, headY, 0, null)
        headChannel = 0
        headEntry = null
        headProgress = 0f
        pending.addLast(GameEvent.FlowStarted)
        pending.addLast(GameEvent.ChannelEntered(headX, headY, 0, null))
    }

    /** Durée de remplissage du conduit de tête, en secondes, à la vitesse actuelle. */
    fun headDuration(): Float {
        val piece = board.piece(headX, headY) ?: return spec.secondsPerCell
        val base = spec.secondsPerCell * piece.fillFactor
        return if (fast) base / spec.fastFactor else base
    }

    private fun advanceFlow(dt: Float) {
        var remaining = dt
        // Borne de sécurité : un pas ne traverse jamais plus de cases qu'il n'y en a.
        var guard = board.size * 2 + 2
        while (remaining > 0f && phase == Phase.FLOWING && guard-- > 0) {
            val duration = headDuration()
            val needed = (1f - headProgress) * duration
            if (remaining < needed) {
                headProgress += remaining / duration
                board.setFill(headX, headY, headChannel, headProgress)
                return
            }
            remaining -= needed
            headProgress = 1f
            completeHead()
            if (phase == Phase.FLOWING) moveToNext()
        }
    }

    private fun completeHead() {
        val piece = board.piece(headX, headY)!!
        board.completeChannel(headX, headY, headChannel)
        when (piece.family) {
            PieceFamily.SOURCE -> Unit
            PieceFamily.DRAIN -> {
                drainReached = true
                drainBonusPoints += Scoring.DRAIN_BONUS
                pending.addLast(GameEvent.DrainReached(headX, headY))
                finish(success = filled >= spec.target)
            }
            else -> {
                filled++
                val points = Scoring.pipePoints(piece, filled, spec.target, fast)
                pipePoints += points
                val other = 1 - headChannel
                val cross = piece.channels.size == 2 && board.channelState(headX, headY, other) == ChannelState.FULL
                if (cross) {
                    crossings++
                    crossBonusPoints += Scoring.CROSS_BONUS
                }
                pending.addLast(GameEvent.ChannelFilled(headX, headY, headChannel, points, cross, filled))
                if (!targetAnnounced && filled >= spec.target) {
                    targetAnnounced = true
                    pending.addLast(GameEvent.TargetReached)
                }
            }
        }
    }

    private fun moveToNext() {
        val piece = board.piece(headX, headY)!!
        val exit = piece.channels[headChannel].exitFor(headEntry) ?: return
        val nx = headX + exit.dx
        val ny = headY + exit.dy
        if (!board.inBounds(nx, ny)) return leak(exit)
        val next = board.piece(nx, ny) ?: return leak(exit)
        val channel = next.channelAccepting(exit.opposite)
        if (channel < 0 || board.channelState(nx, ny, channel) != ChannelState.EMPTY) return leak(exit)
        headX = nx
        headY = ny
        headChannel = channel
        headEntry = exit.opposite
        headProgress = 0f
        board.startChannel(nx, ny, channel, exit.opposite)
        pending.addLast(GameEvent.ChannelEntered(nx, ny, channel, exit.opposite))
    }

    private fun leak(port: Direction) {
        pending.addLast(GameEvent.Leaked(headX, headY, port))
        finish(success = !spec.requireDrain && filled >= spec.target)
    }

    private fun finish(success: Boolean) {
        var unused = 0
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            if (board.isPlacedByPlayer(x, y) && !board.isTouched(x, y)) unused++
        }
        val unusedPenalty = unused * Scoring.UNUSED_PENALTY
        val total = max(0, pipePoints + crossBonusPoints + drainBonusPoints - replacePenalty - unusedPenalty)
        val r = RoundResult(
            success = success,
            filled = filled,
            target = spec.target,
            drainReached = drainReached,
            pipePoints = pipePoints,
            crossBonus = crossBonusPoints,
            drainBonus = drainBonusPoints,
            replacePenalty = replacePenalty,
            unusedPieces = unused,
            unusedPenalty = unusedPenalty,
            score = total,
            crossings = crossings,
            replacements = replacements,
            piecesPlaced = piecesPlaced,
            durationSeconds = elapsed,
        )
        result = r
        phase = Phase.FINISHED
        pending.addLast(GameEvent.RoundEnded(r))
    }

    // --- Lecture du réseau -------------------------------------------------------

    /**
     * Suit le réseau posé en aval du fluide (ou de la source avant l'écoulement),
     * sans rien modifier. Sert au robot de test et à l'aide visuelle.
     */
    fun trail(): Trail {
        val b = trace(scratch)
        return Trail(b.length, b.end, b.x, b.y, b.port)
    }

    private val scratch = TrailBuffer()

    /** Variante sans allocation de [trail] : le résultat est écrit dans [out]. */
    fun trace(out: TrailBuffer): TrailBuffer {
        visited.fill(false)
        var x = headX
        var y = headY
        var channel = headChannel
        var entry = headEntry
        val start = board.piece(x, y)!!
        // Le tuyau en cours de remplissage n'est pas encore compté dans [filled] :
        // il fait partie de ce que le fluide va parcourir.
        val headCounts = start.family != PieceFamily.SOURCE && start.family != PieceFamily.DRAIN &&
            board.channelState(x, y, channel) != ChannelState.FULL
        var length = if (headCounts) 1 else 0
        visited[board.index(x, y) * 2 + channel] = true
        var exit = start.channels[channel].exitFor(entry) ?: return out.set(0, TrailEnd.DRAIN, x, y, null)
        while (true) {
            val nx = x + exit.dx
            val ny = y + exit.dy
            if (!board.inBounds(nx, ny)) return out.set(length, TrailEnd.EDGE, x, y, exit)
            val next = board.piece(nx, ny) ?: return out.set(length, TrailEnd.EMPTY_CELL, nx, ny, exit.opposite)
            val ch = next.channelAccepting(exit.opposite)
            if (ch < 0) return out.set(length, TrailEnd.BLOCKED, nx, ny, exit.opposite)
            val slot = board.index(nx, ny) * 2 + ch
            if (visited[slot] || board.channelState(nx, ny, ch) != ChannelState.EMPTY) {
                return out.set(length, TrailEnd.BLOCKED, nx, ny, exit.opposite)
            }
            visited[slot] = true
            if (next.family == PieceFamily.DRAIN) return out.set(length, TrailEnd.DRAIN, nx, ny, exit.opposite)
            if (next.family != PieceFamily.SOURCE) length++
            entry = exit.opposite
            channel = ch
            x = nx
            y = ny
            exit = next.channels[ch].exitFor(entry) ?: return out.set(length, TrailEnd.DRAIN, x, y, null)
        }
    }

    // --- Sauvegarde --------------------------------------------------------------

    fun capture(): SessionState = SessionState(
        board = board.encode(),
        queue = queue.snapshot(),
        phase = phase,
        countdownLeft = countdownLeft,
        filled = filled,
        fast = fast,
        cooldown = cooldown,
        elapsed = elapsed,
        headX = headX,
        headY = headY,
        headChannel = headChannel,
        headEntry = headEntry,
        headProgress = headProgress,
        pipePoints = pipePoints,
        crossBonusPoints = crossBonusPoints,
        drainBonusPoints = drainBonusPoints,
        replacePenalty = replacePenalty,
        crossings = crossings,
        replacements = replacements,
        piecesPlaced = piecesPlaced,
        drainReached = drainReached,
        targetAnnounced = targetAnnounced,
        lastTick = lastTick,
    )

    fun restore(s: SessionState) {
        require(s.phase != Phase.FINISHED) { "une manche terminée ne se restaure pas" }
        board.decode(s.board)
        queue.restore(s.queue)
        phase = s.phase
        countdownLeft = s.countdownLeft
        filled = s.filled
        fast = s.fast
        cooldown = s.cooldown
        elapsed = s.elapsed
        headX = s.headX
        headY = s.headY
        headChannel = s.headChannel
        headEntry = s.headEntry
        headProgress = s.headProgress
        pipePoints = s.pipePoints
        crossBonusPoints = s.crossBonusPoints
        drainBonusPoints = s.drainBonusPoints
        replacePenalty = s.replacePenalty
        crossings = s.crossings
        replacements = s.replacements
        piecesPlaced = s.piecesPlaced
        drainReached = s.drainReached
        targetAnnounced = s.targetAnnounced
        lastTick = s.lastTick
        pending.clear()
    }

    companion object {
        const val VISIBLE_PIECES = 5
        /** Les dernières secondes du compte à rebours sont annoncées une à une. */
        const val COUNTDOWN_TICKS = 5
    }
}

class SessionState(
    val board: BoardState,
    val queue: QueueState,
    val phase: Phase,
    val countdownLeft: Float,
    val filled: Int,
    val fast: Boolean,
    val cooldown: Float,
    val elapsed: Float,
    val headX: Int,
    val headY: Int,
    val headChannel: Int,
    val headEntry: Direction?,
    val headProgress: Float,
    val pipePoints: Int,
    val crossBonusPoints: Int,
    val drainBonusPoints: Int,
    val replacePenalty: Int,
    val crossings: Int,
    val replacements: Int,
    val piecesPlaced: Int,
    val drainReached: Boolean,
    val targetAnnounced: Boolean,
    val lastTick: Int,
)
