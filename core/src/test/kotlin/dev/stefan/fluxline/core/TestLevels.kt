package dev.stefan.fluxline.core

/** Petits plateaux sur mesure pour les tests. */
object TestLevels {
    fun spec(
        vararg rows: String,
        target: Int = 3,
        countdown: Float = 2f,
        spc: Float = 1f,
        rotation: Boolean = false,
        requireDrain: Boolean = false,
        limit: Int = 0,
        seed: Long = 42,
    ) = LevelSpec(
        id = "test",
        title = "Test",
        layout = rows.toList(),
        target = target,
        countdown = countdown,
        secondsPerCell = spc,
        queueSeed = seed,
        allowRotation = rotation,
        requireDrain = requireDrain,
        pieceLimit = limit,
    )

    /** Pose directement une pièce choisie (sans passer par le distributeur). */
    fun GameSession.force(x: Int, y: Int, piece: Piece) {
        board.setPiece(x, y, piece, isFixed = false, byPlayer = true)
    }

    /** Avance la simulation à pas fixe de 1/120 s. */
    fun GameSession.run(seconds: Float, step: Float = 1f / 120f) {
        var t = 0f
        while (t < seconds && !isFinished) {
            update(step)
            t += step
        }
    }

    fun GameSession.drainEvents(): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        while (true) out += pollEvent() ?: break
        return out
    }
}
