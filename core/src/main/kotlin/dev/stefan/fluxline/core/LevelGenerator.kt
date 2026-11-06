package dev.stefan.fluxline.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fabrique les plateaux des modes Classique, Arcade et du défi du jour.
 *
 * Chaque plateau est vérifié avant d'être rendu : la sortie de la source donne
 * sur une case libre et la zone accessible depuis elle est assez vaste pour
 * l'objectif. Un plateau qui échoue est retiré (jusqu'à [MAX_TRIES] fois, puis
 * les obstacles sont abandonnés : le jeu ne bloque jamais).
 */
object LevelGenerator {
    private const val MAX_TRIES = 60

    /** Paramètres de difficulté d'une manche générée. */
    data class Difficulty(
        val target: Int,
        val countdown: Float,
        val secondsPerCell: Float,
        val blocks: Int,
        val reservoirs: Int,
        val oneWays: Int,
        val drain: Boolean,
    )

    fun classicDifficulty(level: Int): Difficulty {
        val n = max(1, level)
        return Difficulty(
            target = min((11 + 3 * n) / 2, 30),
            countdown = max(9f, 30f - 1.2f * (n - 1)),
            secondsPerCell = max(1.05f, 3.8f * 0.94f.pow(n - 1)),
            blocks = if (n < 3) 0 else min(n - 1, 9),
            reservoirs = when {
                n >= 12 -> 2
                n >= 5 -> 1
                else -> 0
            },
            oneWays = when {
                n >= 13 -> 2
                n >= 7 -> 1
                else -> 0
            },
            drain = n >= 4 && n % 3 == 1,
        )
    }

    fun arcadeDifficulty(level: Int): Difficulty {
        val n = max(1, level)
        return Difficulty(
            target = min((11 + 3 * n) / 2, 28),
            countdown = max(6f, 20f - 1.3f * (n - 1)),
            secondsPerCell = max(0.85f, 3.0f * 0.92f.pow(n - 1)),
            blocks = min(1 + n, 10),
            reservoirs = if (n >= 3) 1 else 0,
            oneWays = if (n >= 4) min(1 + (n - 4) / 4, 3) else 0,
            drain = n % 2 == 0,
        )
    }

    fun classic(level: Int, seed: Long): LevelSpec {
        val d = classicDifficulty(level)
        return generate("classic-$level", "Manche $level", d, Rng.mix(seed, level.toLong(), 0xC1A5L), allowRotation = false)
    }

    fun arcade(level: Int, seed: Long): LevelSpec {
        val d = arcadeDifficulty(level)
        return generate("arcade-$level", "Vague $level", d, Rng.mix(seed, level.toLong(), 0xA5CAL), allowRotation = true)
    }

    /**
     * Les différentes familles d'objectifs du défi du jour. [key] désigne les
     * textes traduits (challenge.<key>.title et .desc) ; [title] est un nom interne.
     */
    enum class ChallengeKind(val key: String, val title: String) {
        COUNTED("counted", "Pièces comptées"),
        RUSH("rush", "Contre la montre"),
        NETWORK("network", "Grand réseau"),
        DRAIN("drain", "Rejoindre l'arrivée"),
    }

    fun challengeKind(day: Int): ChallengeKind {
        val rng = Rng(Rng.mix(day.toLong(), 0xDA11L))
        return ChallengeKind.entries[rng.nextInt(ChallengeKind.entries.size)]
    }

    /** Le défi du jour [day] (au format AAAAMMJJ) : le même pour tous les joueurs ce jour-là. */
    fun daily(day: Int): LevelSpec {
        val kind = challengeKind(day)
        val seed = Rng.mix(day.toLong(), 0xDA11L, 7)
        val base = classicDifficulty(6)
        val (d, limit, requireDrain) = when (kind) {
            ChallengeKind.COUNTED -> Triple(base.copy(target = 14, countdown = 30f, secondsPerCell = 2.4f), 20, false)
            ChallengeKind.RUSH -> Triple(base.copy(target = 12, countdown = 9f, secondsPerCell = 1.5f), 0, false)
            ChallengeKind.NETWORK -> Triple(base.copy(target = 28, countdown = 40f, secondsPerCell = 2.2f), 0, false)
            ChallengeKind.DRAIN -> Triple(base.copy(target = 12, countdown = 24f, secondsPerCell = 2.2f, drain = true), 0, true)
        }
        val spec = generate("daily-$day", kind.title, d, seed, allowRotation = true, minDrainDistance = if (requireDrain) 6 else 4)
        return spec.copy(pieceLimit = limit, requireDrain = requireDrain && spec.hasDrain)
    }

    // --- Génération --------------------------------------------------------------

    fun generate(
        id: String,
        title: String,
        d: Difficulty,
        seed: Long,
        allowRotation: Boolean,
        cols: Int = LevelSpec.COLS,
        rows: Int = LevelSpec.ROWS,
        minDrainDistance: Int = 4,
    ): LevelSpec {
        val rng = Rng(seed)
        for (attempt in 0..MAX_TRIES) {
            // En dernier recours, un plateau sans obstacle est toujours jouable.
            val relaxed = attempt == MAX_TRIES
            val grid = tryLayout(rng, d, cols, rows, relaxed, minDrainDistance) ?: continue
            return LevelSpec(
                id = id,
                title = title,
                layout = grid.map { String(it) },
                target = d.target,
                countdown = d.countdown,
                secondsPerCell = d.secondsPerCell,
                queueSeed = rng.nextLong(),
                allowRotation = allowRotation,
            )
        }
        error("génération impossible pour $id")
    }

    private fun tryLayout(
        rng: Rng,
        d: Difficulty,
        cols: Int,
        rows: Int,
        relaxed: Boolean,
        minDrainDistance: Int,
    ): List<CharArray>? {
        val grid = List(rows) { CharArray(cols) { '.' } }
        // Source à l'intérieur du plateau, jamais collée au bord, tournée vers une case libre.
        val sx = rng.nextInt(1, cols - 1)
        val sy = rng.nextInt(2, rows - 2)
        val dir = Direction.of(rng.nextInt(4))
        grid[sy][sx] = Piece.source(dir).code
        // Les deux cases devant la source restent libres : le premier geste ne doit pas être forcé.
        val reserved = HashSet<Int>()
        reserved += sy * cols + sx
        for (k in 1..2) {
            val fx = sx + dir.dx * k
            val fy = sy + dir.dy * k
            if (fx in 0 until cols && fy in 0 until rows) reserved += fy * cols + fx
        }
        if (relaxed) return grid

        fun freeCell(): Pair<Int, Int>? {
            repeat(40) {
                val x = rng.nextInt(cols)
                val y = rng.nextInt(rows)
                if (grid[y][x] == '.' && (y * cols + x) !in reserved) return x to y
            }
            return null
        }

        // Spéciaux d'abord (ils ont besoin de voisins libres), blocs ensuite.
        repeat(d.reservoirs) {
            val (x, y) = freeCell() ?: return null
            val horizontal = rng.chance(0.5f)
            if (!portsOpen(grid, x, y, horizontal, cols, rows)) return null
            grid[y][x] = Piece.reservoir(horizontal).code
            reserveAround(reserved, x, y, horizontal, cols)
        }
        repeat(d.oneWays) {
            val (x, y) = freeCell() ?: return null
            val flow = Direction.of(rng.nextInt(4))
            if (!portsOpen(grid, x, y, flow.isHorizontal, cols, rows)) return null
            grid[y][x] = Piece.oneWay(flow).code
            reserveAround(reserved, x, y, flow.isHorizontal, cols)
        }
        if (d.drain) {
            var placed = false
            repeat(30) {
                if (placed) return@repeat
                val (x, y) = freeCell() ?: return@repeat
                if (abs(x - sx) + abs(y - sy) < minDrainDistance) return@repeat
                val port = Direction.of(rng.nextInt(4))
                val px = x + port.dx
                val py = y + port.dy
                if (px !in 0 until cols || py !in 0 until rows || grid[py][px] != '.') return@repeat
                grid[y][x] = Piece.drain(port).code
                reserved += py * cols + px
                placed = true
            }
            if (!placed) return null
        }
        repeat(d.blocks) {
            val (x, y) = freeCell() ?: return null
            grid[y][x] = '#'
        }

        // Assez de place pour l'objectif, avec de la marge pour les pièces mal tombées.
        val fx = sx + dir.dx
        val fy = sy + dir.dy
        if (reachableCells(grid, fx, fy, cols, rows) < d.target + 8) return null
        return grid
    }

    private fun portsOpen(grid: List<CharArray>, x: Int, y: Int, horizontal: Boolean, cols: Int, rows: Int): Boolean {
        val (ax, ay, bx, by) = if (horizontal) listOf(x - 1, y, x + 1, y) else listOf(x, y - 1, x, y + 1)
        fun open(px: Int, py: Int) = px in 0 until cols && py in 0 until rows && grid[py][px] == '.'
        return open(ax, ay) && open(bx, by)
    }

    private fun reserveAround(reserved: MutableSet<Int>, x: Int, y: Int, horizontal: Boolean, cols: Int) {
        if (horizontal) {
            reserved += y * cols + x - 1
            reserved += y * cols + x + 1
        } else {
            reserved += (y - 1) * cols + x
            reserved += (y + 1) * cols + x
        }
    }

    /** Nombre de cases franchissables accessibles depuis (x, y), la case elle-même comprise. */
    fun reachableCells(grid: List<CharArray>, x: Int, y: Int, cols: Int, rows: Int): Int {
        if (x !in 0 until cols || y !in 0 until rows || grid[y][x] != '.') return 0
        val seen = BooleanArray(cols * rows)
        val stack = ArrayDeque<Int>()
        stack.addLast(y * cols + x)
        seen[y * cols + x] = true
        var count = 0
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            count++
            val cx = i % cols
            val cy = i / cols
            for (dir in Direction.entries) {
                val nx = cx + dir.dx
                val ny = cy + dir.dy
                if (nx !in 0 until cols || ny !in 0 until rows) continue
                val j = ny * cols + nx
                if (seen[j]) continue
                val c = grid[ny][nx]
                // Les blocs et la source arrêtent l'exploration ; les spéciaux se traversent.
                if (c == '#' || Piece.byCode(c)?.family == PieceFamily.SOURCE) continue
                seen[j] = true
                stack.addLast(j)
            }
        }
        return count
    }
}
