package dev.stefan.fluxline.core

/** Une partie interrompue : de quoi la reprendre exactement là où elle était. */
class SavedGame(val run: RunState, val spec: LevelSpec, val session: SessionState)

/**
 * Sérialise une partie en cours dans un texte « clé=valeur », une ligne par
 * champ. Le plateau est enregistré en entier (et pas seulement sa graine) : une
 * mise à jour du générateur ne peut donc jamais corrompre une partie sauvegardée.
 *
 * Le décodage est strict : au moindre doute il échoue (et la partie est alors
 * simplement oubliée) plutôt que de restaurer un état incohérent.
 */
object SaveCodec {
    const val VERSION = 1

    fun encode(game: SavedGame): String {
        val out = StringBuilder()
        fun put(key: String, value: Any?) {
            out.append(key).append('=').append(value.toString().replace('\n', ' ')).append('\n')
        }
        val r = game.run
        put("version", VERSION)
        put("run.mode", r.mode.key)
        put("run.seed", r.seed)
        put("run.level", r.level)
        put("run.score", r.totalScore)
        put("run.lives", r.lives)
        put("run.attempt", r.attempt)
        put("run.day", r.challengeDay)

        val sp = game.spec
        put("spec.id", sp.id)
        put("spec.title", sp.title)
        put("spec.layout", sp.layout.joinToString("/"))
        put("spec.target", sp.target)
        put("spec.countdown", sp.countdown)
        put("spec.spc", sp.secondsPerCell)
        put("spec.seed", sp.queueSeed)
        put("spec.rotation", sp.allowRotation)
        put("spec.drain", sp.requireDrain)
        put("spec.limit", sp.pieceLimit)
        put("spec.fast", sp.fastFactor)

        val s = game.session
        put("board.pieces", s.board.pieces)
        put("board.fixed", s.board.fixed)
        put("board.player", s.board.placedByPlayer)
        put("board.fill", s.board.fill.joinToString(","))
        put("board.states", s.board.states.joinToString(","))
        put("board.entries", s.board.entries.joinToString(","))
        put("queue.rng", s.queue.rngState)
        put("queue.slots", s.queue.slots)
        put("queue.drawn", s.queue.drawn)
        put("queue.consumed", s.queue.consumed)
        put("s.phase", s.phase.name)
        put("s.countdown", s.countdownLeft)
        put("s.filled", s.filled)
        put("s.fast", s.fast)
        put("s.cooldown", s.cooldown)
        put("s.elapsed", s.elapsed)
        put("s.headX", s.headX)
        put("s.headY", s.headY)
        put("s.headChannel", s.headChannel)
        put("s.headEntry", s.headEntry?.name ?: "-")
        put("s.headProgress", s.headProgress)
        put("s.pipePoints", s.pipePoints)
        put("s.crossPoints", s.crossBonusPoints)
        put("s.drainPoints", s.drainBonusPoints)
        put("s.replacePenalty", s.replacePenalty)
        put("s.crossings", s.crossings)
        put("s.replacements", s.replacements)
        put("s.placed", s.piecesPlaced)
        put("s.drainReached", s.drainReached)
        put("s.targetAnnounced", s.targetAnnounced)
        put("s.lastTick", s.lastTick)
        // Pas de saut de ligne final : Android indente le XML des préférences
        // après un texte qui se termine par « \n », et ces espaces reviennent à la lecture.
        return out.toString().trimEnd()
    }

    /** Décode une sauvegarde ; renvoie null si elle est absente, d'une autre version ou abîmée. */
    fun decode(text: String?): SavedGame? {
        if (text.isNullOrBlank()) return null
        return try {
            decodeStrict(text)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun decodeStrict(text: String): SavedGame {
        val map = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            require(eq > 0) { "ligne invalide" }
            map[line.substring(0, eq)] = line.substring(eq + 1)
        }
        fun str(k: String) = requireNotNull(map[k]) { "champ manquant : $k" }
        fun int(k: String) = str(k).toInt()
        fun long(k: String) = str(k).toLong()
        fun float(k: String) = str(k).toFloat()
        fun bool(k: String) = str(k).toBooleanStrict()
        fun bytes(k: String): ByteArray = str(k).split(',').map { it.toByte() }.toByteArray()

        require(int("version") == VERSION) { "version inconnue" }
        val run = RunState(
            mode = GameMode.byKey(str("run.mode")),
            seed = long("run.seed"),
            level = int("run.level"),
            totalScore = int("run.score"),
            lives = int("run.lives"),
            attempt = int("run.attempt"),
            challengeDay = int("run.day"),
        )
        val spec = LevelSpec(
            id = str("spec.id"),
            title = str("spec.title"),
            layout = str("spec.layout").split('/'),
            target = int("spec.target"),
            countdown = float("spec.countdown"),
            secondsPerCell = float("spec.spc"),
            queueSeed = long("spec.seed"),
            allowRotation = bool("spec.rotation"),
            requireDrain = bool("spec.drain"),
            pieceLimit = int("spec.limit"),
            fastFactor = float("spec.fast"),
        )
        val size = spec.cols * spec.rows
        val board = BoardState(
            cols = spec.cols,
            rows = spec.rows,
            pieces = str("board.pieces"),
            fixed = str("board.fixed"),
            placedByPlayer = str("board.player"),
            fill = str("board.fill").split(',').map { it.toFloat() }.toFloatArray(),
            states = bytes("board.states"),
            entries = bytes("board.entries"),
        )
        require(board.pieces.length == size && board.fixed.length == size && board.placedByPlayer.length == size)
        require(board.fill.size == size * 2 && board.states.size == size * 2 && board.entries.size == size * 2)
        require(board.states.all { it in 0..2 } && board.entries.all { it in -1..4 })
        require(board.pieces.all { it == '.' || Piece.byCode(it) != null })
        require(str("queue.slots").all { it == '.' || Piece.byCode(it)?.isStandard == true })
        val session = SessionState(
            board = board,
            queue = QueueState(long("queue.rng"), str("queue.slots"), int("queue.drawn"), int("queue.consumed")),
            phase = Phase.valueOf(str("s.phase")),
            countdownLeft = float("s.countdown"),
            filled = int("s.filled"),
            fast = bool("s.fast"),
            cooldown = float("s.cooldown"),
            elapsed = float("s.elapsed"),
            headX = int("s.headX"),
            headY = int("s.headY"),
            headChannel = int("s.headChannel"),
            headEntry = str("s.headEntry").let { if (it == "-") null else Direction.valueOf(it) },
            headProgress = float("s.headProgress"),
            pipePoints = int("s.pipePoints"),
            crossBonusPoints = int("s.crossPoints"),
            drainBonusPoints = int("s.drainPoints"),
            replacePenalty = int("s.replacePenalty"),
            crossings = int("s.crossings"),
            replacements = int("s.replacements"),
            piecesPlaced = int("s.placed"),
            drainReached = bool("s.drainReached"),
            targetAnnounced = bool("s.targetAnnounced"),
            lastTick = int("s.lastTick"),
        )
        require(session.phase != Phase.FINISHED)
        require(session.headX in 0 until spec.cols && session.headY in 0 until spec.rows && session.headChannel in 0..1)
        return SavedGame(run, spec, session)
    }
}
