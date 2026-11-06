package dev.stefan.fluxline.core

import kotlin.math.abs

/**
 * Un joueur automatique honnête : il ne voit que ce que voit le joueur (le
 * plateau et la prochaine pièce) et agit au plus une fois toutes les
 * [thinkTime] secondes, comme un humain attentif mais pas surhumain.
 *
 * Il sert à vérifier que chaque niveau est faisable, à mesurer l'équilibrage, et
 * à jouer les démonstrations (captures, tests de performance).
 *
 * Stratégie : pour chaque case et chaque orientation permise, il pose la pièce
 * « pour de faux », suit le réseau obtenu ([GameSession.trail]) et note le
 * résultat — longueur du réseau en aval du fluide, espace libre devant lui,
 * rapprochement de l'arrivée quand elle est utile. Il garde la meilleure option.
 *
 * Par défaut il ne se trompe jamais. Pour imiter un joueur humain (tests
 * d'équilibrage), [mistakes] lui fait parfois choisir une option plausible mais
 * pas la meilleure, et [rotateTime] compte le temps de chaque toucher de rotation.
 */
class AutoPlayer(
    private val thinkTime: Float = 0.7f,
    /** Planifier un itinéraire (meilleur sur les plateaux encombrés) ou jouer au coup par coup. */
    private val planning: Boolean = false,
    /** Probabilité, à chaque pose, de choisir l'une des options suivantes plutôt que la meilleure. */
    private val mistakes: Float = 0f,
    /** Secondes ajoutées au temps de réflexion par rotation de la pièce. */
    private val rotateTime: Float = 0f,
    seed: Long = 1L,
) {
    private var timer = 0f
    private var lastRotations = 0
    private val rng = Rng(seed)

    /** Options notées de la décision en cours (seulement quand [mistakes] > 0). */
    private var optScore = IntArray(0)
    private var optCell = IntArray(0)
    private var optCount = 0

    private var seen = BooleanArray(0)
    private var stack = IntArray(0)
    private var dist = IntArray(0)

    fun update(session: GameSession, dt: Float) {
        if (session.isFinished) return
        timer -= dt
        if (timer > 0f) return
        lastRotations = 0
        act(session)
        timer = thinkTime + lastRotations * rotateTime
    }

    /** Une décision. Renvoie vrai si le joueur a agi. */
    fun act(session: GameSession): Boolean {
        val board = session.board
        if (seen.size != board.size) {
            seen = BooleanArray(board.size)
            stack = IntArray(board.size)
            dist = IntArray(board.size)
        }
        val next = session.queue.next ?: return false
        if (session.cooldown > 0f || session.isFinished) return false

        val baseline = session.trail()
        val baseScore = scoreTrail(session, baseline)

        // Le réseau mène déjà à l'arrivée avec un objectif assuré : on encaisse.
        if (baseline.end == TrailEnd.DRAIN && session.filled + baseline.length >= session.spec.target) {
            if (!session.fast) session.accelerate()
            return true
        }

        // Une erreur humaine se décide avant de réfléchir : elle peut détourner aussi du plan.
        val blunder = mistakes > 0f && rng.chance(mistakes)

        // D'abord le plan : poser la pièce sur l'itinéraire prévu, ou la garer à l'écart.
        if (planning && !blunder) {
            when (planAct(session, baseline, next)) {
                PlanDecision.ACTED -> return true
                PlanDecision.FAILED -> return false
                PlanDecision.NO_PLAN -> Unit
            }
        }

        // Réseau déjà bien plus long que l'objectif : on encaisse au lieu d'encombrer le plateau.
        val comfortable = maxOf(session.spec.target + 3, (session.spec.target * 3 + 1) / 2)
        if (!session.fast && !session.spec.requireDrain && baseline.end != TrailEnd.DRAIN &&
            session.filled + baseline.length >= comfortable
        ) {
            session.accelerate()
            return true
        }

        val rotations = if (session.spec.allowRotation) 4 else 1
        if (mistakes > 0f && optScore.size < board.size * 4) {
            optScore = IntArray(board.size * 4)
            optCell = IntArray(board.size * 4)
        }
        optCount = 0
        var bestScore = Int.MIN_VALUE
        var bestX = -1
        var bestY = -1
        var bestRot = 0
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            if (session.refusalAt(x, y) != null) continue
            val replacing = board.piece(x, y) != null
            var piece = next
            for (r in 0 until rotations) {
                if (r > 0) {
                    piece = piece.rotatedClockwise()
                    if (piece == next) break
                }
                var score = evaluate(session, x, y, piece)
                if (replacing) score -= REPLACE_COST
                score += neighbourhood(session, baseline, x, y, piece)
                // À note égale, garer loin du chantier.
                score += minOf(abs(x - baseline.x) + abs(y - baseline.y), 8) * 2
                if (mistakes > 0f) {
                    optScore[optCount] = score
                    optCell[optCount] = (board.index(x, y) shl 2) or r
                    optCount++
                }
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                    bestRot = r
                }
            }
        }
        if (bestX < 0) return false

        if (blunder && optCount > 1) {
            // Une erreur humaine : l'une des options les mieux notées après la meilleure.
            val cell = plausibleMistake()
            bestX = (cell shr 2) % board.cols
            bestY = (cell shr 2) / board.cols
            bestRot = cell and 3
            repeat(bestRot) { session.rotateNext() }
            lastRotations = bestRot
            return session.place(bestX, bestY)
        }

        // Le réseau est complet et l'objectif atteint, mais plus rien ne l'améliore : on accélère.
        if (!session.fast && !session.spec.requireDrain && bestScore <= baseScore &&
            session.filled + baseline.length >= session.spec.target + 2 && baseline.end != TrailEnd.EMPTY_CELL
        ) {
            session.accelerate()
            return true
        }
        repeat(bestRot) { session.rotateNext() }
        lastRotations = bestRot
        return session.place(bestX, bestY)
    }

    /** Tire l'une des [MISTAKE_CHOICES] options classées juste après la meilleure. */
    private fun plausibleMistake(): Int {
        val ranked = (0 until optCount).sortedByDescending { optScore[it] }
        val pick = 1 + rng.nextInt(minOf(MISTAKE_CHOICES, optCount - 1))
        return optCell[ranked[pick]]
    }

    /** Pose la pièce sans la poser vraiment, suit le réseau, et rend la note obtenue. */
    private fun evaluate(session: GameSession, x: Int, y: Int, piece: Piece): Int {
        val board = session.board
        val previous = board.piece(x, y)
        val previousByPlayer = board.isPlacedByPlayer(x, y)
        board.setPiece(x, y, piece, isFixed = false, byPlayer = true)
        val trail = session.trail()
        val score = scoreTrail(session, trail)
        board.setPiece(x, y, previous, isFixed = false, byPlayer = previousByPlayer)
        return score
    }

    private fun scoreTrail(session: GameSession, t: Trail): Int {
        val spec = session.spec
        val board = session.board
        val total = session.filled + t.length
        val reached = total >= spec.target
        val lengthScore = t.length * 100
        return when (t.end) {
            TrailEnd.DRAIN -> if (reached) 100_000 + lengthScore else -20_000 + t.length
            TrailEnd.EMPTY_CELL -> lengthScore + minOf(freeSpace(board, t.x, t.y), 30) * 8 + freeSpace(board, t.x, t.y) + drainSeek(session, t, total)
            TrailEnd.BLOCKED -> {
                val replaceable = !board.isFixed(t.x, t.y) && !board.isTouched(t.x, t.y)
                when {
                    replaceable -> lengthScore + freeSpace(board, t.x, t.y) * 5 - 150 + drainSeek(session, t, total)
                    reached && !spec.requireDrain -> lengthScore - 600
                    else -> -10_000 + t.length
                }
            }
            TrailEnd.EDGE -> if (reached && !spec.requireDrain) lengthScore - 600 else -10_000 + t.length
        }
    }

    /**
     * Quand l'arrivée existe : tant que le réseau est trop court pour l'objectif,
     * on ne s'en approche pas ; une fois assez long, on s'en rapproche.
     */
    private fun drainSeek(session: GameSession, t: Trail, total: Int): Int {
        if (!session.board.let { b -> hasDrain(b) }) return 0
        val d = distanceToDrain(session.board, t.x, t.y)
        if (d < 0) return if (session.spec.requireDrain) -3_000 else 0
        val weight = if (session.spec.requireDrain) 60 else 15
        return if (total + d >= session.spec.target) -d * weight else 0
    }

    private fun hasDrain(board: Board): Boolean {
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            if (board.piece(x, y)?.family == PieceFamily.DRAIN) return true
        }
        return false
    }

    /**
     * Petits ajustements « humains » : préparer le terrain juste devant le front
     * (une pièce qui s'ouvre vers lui), et ne pas encombrer ses abords sinon.
     */
    private fun neighbourhood(session: GameSession, baseline: Trail, x: Int, y: Int, piece: Piece): Int {
        if (baseline.end != TrailEnd.EMPTY_CELL) return 0
        val dx = x - baseline.x
        val dy = y - baseline.y
        if (abs(dx) + abs(dy) != 1) return 0
        val towardsFront = when {
            dx == 1 -> Direction.W
            dx == -1 -> Direction.E
            dy == 1 -> Direction.N
            else -> Direction.S
        }
        // Ne jamais boucher la case d'où vient le fluide.
        if (baseline.port != null && baseline.x + baseline.port.dx == x && baseline.y + baseline.port.dy == y) return -200
        val ch = piece.channelAccepting(towardsFront)
        if (ch < 0) return -30
        val exit = piece.channels[ch].exitFor(towardsFront)!!
        val nx = x + exit.dx
        val ny = y + exit.dy
        val board = session.board
        if (!board.inBounds(nx, ny)) return -30
        return if (board.piece(nx, ny) == null) 30 else 5
    }

    /** Cases vides ou remplaçables atteignables depuis (x, y). */
    private fun freeSpace(board: Board, x: Int, y: Int): Int {
        seen.fill(false)
        var top = 0
        var count = 0
        stack[top++] = board.index(x, y)
        seen[board.index(x, y)] = true
        while (top > 0) {
            val i = stack[--top]
            count++
            val cx = i % board.cols
            val cy = i / board.cols
            for (d in Direction.entries) {
                val nx = cx + d.dx
                val ny = cy + d.dy
                if (!board.inBounds(nx, ny)) continue
                val j = board.index(nx, ny)
                if (seen[j]) continue
                if (board.piece(nx, ny) != null && (board.isFixed(nx, ny) || board.isTouched(nx, ny))) continue
                seen[j] = true
                stack[top++] = j
            }
        }
        return count
    }

    /** Distance (en cases libres) de (x, y) à la case d'entrée de l'arrivée, ou -1. */
    private fun distanceToDrain(board: Board, x: Int, y: Int): Int {
        var tx = -1
        var ty = -1
        for (yy in 0 until board.rows) for (xx in 0 until board.cols) {
            val p = board.piece(xx, yy)
            if (p?.family == PieceFamily.DRAIN) {
                val port = p.endPort!!
                tx = xx + port.dx
                ty = yy + port.dy
            }
        }
        if (!board.inBounds(tx, ty)) return -1
        dist.fill(-1)
        var head = 0
        var tail = 0
        stack[tail++] = board.index(x, y)
        dist[board.index(x, y)] = 0
        while (head < tail) {
            val i = stack[head++]
            val cx = i % board.cols
            val cy = i / board.cols
            if (cx == tx && cy == ty) return dist[i]
            for (d in Direction.entries) {
                val nx = cx + d.dx
                val ny = cy + d.dy
                if (!board.inBounds(nx, ny)) continue
                val j = board.index(nx, ny)
                if (dist[j] >= 0) continue
                if (board.piece(nx, ny) != null && (board.isFixed(nx, ny) || board.isTouched(nx, ny))) continue
                dist[j] = dist[i] + 1
                stack[tail++] = j
            }
        }
        return -1
    }

    // --- Planification ----------------------------------------------------------

    private enum class PlanDecision { ACTED, FAILED, NO_PLAN }

    /** Une case de l'itinéraire : le fluide y entrera par [entry] et en sortira par [exit]. */
    private class Step(val x: Int, val y: Int, val entry: Direction, val exit: Direction)

    private var route: List<Step> = emptyList()
    private var routeVisited = BooleanArray(0)
    private val path = ArrayList<Step>()
    private var bestPath: List<Step> = emptyList()
    private var budget = 0
    private var need = 0
    private var drainX = -1
    private var drainY = -1
    private var drainPort: Direction? = null

    private fun planAct(session: GameSession, baseline: Trail, next: Piece): PlanDecision {
        val board = session.board
        val start = when (baseline.end) {
            TrailEnd.EMPTY_CELL -> true
            TrailEnd.BLOCKED -> !board.isFixed(baseline.x, baseline.y) && !board.isTouched(baseline.x, baseline.y)
            else -> false
        }
        if (!start || baseline.port == null) {
            route = emptyList()
            return PlanDecision.NO_PLAN
        }
        if (!routeStillValid(session, baseline)) replan(session, baseline)
        if (route.isEmpty()) return PlanDecision.NO_PLAN

        // La première case de l'itinéraire qui attend une pièce que l'on a.
        val rotations = if (session.spec.allowRotation) 4 else 1
        for ((i, step) in route.withIndex()) {
            if (board.isFixed(step.x, step.y)) continue
            val current = board.piece(step.x, step.y)
            if (current != null && matches(current, step)) continue
            if (session.refusalAt(step.x, step.y) != null) continue
            var piece = next
            for (r in 0 until rotations) {
                if (r > 0) {
                    piece = piece.rotatedClockwise()
                    if (piece == next) break
                }
                if (matches(piece, step)) {
                    repeat(r) { session.rotateNext() }
                    lastRotations = r
                    return if (session.place(step.x, step.y)) PlanDecision.ACTED else PlanDecision.FAILED
                }
            }
            // Le fluide arrive bientôt sur cette case et la pièce ne convient pas :
            // on laisse le joueur glouton improviser.
            if (i == 0 && !session.spec.requireDrain && timeToFrontier(session, baseline) < thinkTime * 2.2f) {
                return PlanDecision.NO_PLAN
            }
        }
        return if (parkOffRoute(session)) PlanDecision.ACTED else PlanDecision.NO_PLAN
    }

    private fun matches(piece: Piece, step: Step): Boolean {
        val ch = piece.channelAccepting(step.entry)
        return ch >= 0 && piece.channels[ch].exitFor(step.entry) == step.exit
    }

    private fun routeStillValid(session: GameSession, baseline: Trail): Boolean {
        if (route.isEmpty()) return false
        // Le début de l'itinéraire a pu être construit (et rejoint par le réseau) :
        // on le retire et on garde la suite, avec les pièces déjà posées d'avance.
        val start = route.indexOfFirst { it.x == baseline.x && it.y == baseline.y && it.entry == baseline.port }
        if (start < 0) return false
        if (start > 0) route = route.subList(start, route.size).toList()
        val board = session.board
        for (step in route) {
            val p = board.piece(step.x, step.y)
            if (p == null) continue
            if (board.isTouched(step.x, step.y)) return false
            if (board.isFixed(step.x, step.y) && !matches(p, step)) return false
        }
        return true
    }

    private fun timeToFrontier(session: GameSession, baseline: Trail): Float {
        val spc = session.spec.secondsPerCell / if (session.fast) session.spec.fastFactor else 1f
        val head = (1f - session.headProgress) * session.headDuration()
        return session.countdownLeft + head + baseline.length * spc
    }

    private fun replan(session: GameSession, baseline: Trail) {
        val board = session.board
        if (routeVisited.size != board.size) routeVisited = BooleanArray(board.size)
        drainX = -1
        drainY = -1
        drainPort = null
        if (session.spec.requireDrain) {
            for (y in 0 until board.rows) for (x in 0 until board.cols) {
                val p = board.piece(x, y)
                if (p?.family == PieceFamily.DRAIN) {
                    drainX = x
                    drainY = y
                    drainPort = p.endPort
                }
            }
        }
        val remaining = maxOf(1, session.spec.target - (session.filled + baseline.length))
        bestPath = emptyList()
        // On vise un peu plus long que l'objectif, puis on se contente de moins si besoin.
        for (margin in intArrayOf(4, 2, 0)) {
            need = remaining + if (drainPort != null) 0 else margin
            routeVisited.fill(false)
            path.clear()
            budget = 25_000
            if (dfs(session, baseline.x, baseline.y, baseline.port!!, 0)) {
                route = ArrayList(path)
                return
            }
        }
        route = bestPath
    }

    private fun usable(session: GameSession, x: Int, y: Int, entry: Direction): Boolean {
        val board = session.board
        if (!board.inBounds(x, y) || routeVisited[board.index(x, y)]) return false
        val p = board.piece(x, y) ?: return true
        if (board.isTouched(x, y)) return false
        if (!board.isFixed(x, y)) return true
        if (p.family == PieceFamily.DRAIN || p.family == PieceFamily.SOURCE || p.family == PieceFamily.BLOCK) return false
        return p.channelAccepting(entry) >= 0
    }

    private fun dfs(session: GameSession, x: Int, y: Int, entry: Direction, depth: Int): Boolean {
        if (budget-- <= 0) return false
        val board = session.board
        routeVisited[board.index(x, y)] = true
        val fixedPiece = if (board.isFixed(x, y)) board.piece(x, y) else null
        val exits = ArrayList<Direction>(3)
        val atDrainEntry = drainPort != null && x == drainX + drainPort!!.dx && y == drainY + drainPort!!.dy
        if (atDrainEntry) {
            // La case devant l'arrivée ne sert qu'à y entrer.
            exits += drainPort!!.opposite
        } else if (fixedPiece != null) {
            fixedPiece.channels[fixedPiece.channelAccepting(entry)].exitFor(entry)?.let { exits += it }
        } else {
            for (d in Direction.entries) if (d != entry) exits += d
        }
        val newDepth = depth + 1
        // Ordre de Warnsdorff : longer les murs d'abord, pour ne pas morceler l'espace.
        exits.sortBy { exitKey(session, x + it.dx, y + it.dy, it.opposite, newDepth) }
        for (exit in exits) {
            path += Step(x, y, entry, exit)
            if (path.size > bestPath.size) bestPath = ArrayList(path)
            val nx = x + exit.dx
            val ny = y + exit.dy
            if (drainPort == null) {
                if (newDepth >= need && board.inBounds(nx, ny) && usable(session, nx, ny, exit.opposite)) return true
            } else if (nx == drainX && ny == drainY && exit.opposite == drainPort && newDepth >= need) {
                return true
            }
            val intoDrainEntry = drainPort != null && nx == drainX + drainPort!!.dx && ny == drainY + drainPort!!.dy
            if (intoDrainEntry && newDepth + 1 < need) {
                path.removeAt(path.size - 1)
                continue
            }
            if (usable(session, nx, ny, exit.opposite) && dfs(session, nx, ny, exit.opposite, newDepth)) return true
            path.removeAt(path.size - 1)
        }
        routeVisited[board.index(x, y)] = false
        return false
    }

    private fun exitKey(session: GameSession, nx: Int, ny: Int, entry: Direction, depth: Int): Int {
        val board = session.board
        if (!usable(session, nx, ny, entry)) {
            val isDrain = nx == drainX && ny == drainY && entry == drainPort
            return if (isDrain) -1000 else 1000
        }
        var onward = 0
        for (d in Direction.entries) {
            val ax = nx + d.dx
            val ay = ny + d.dy
            if (board.inBounds(ax, ay) && !routeVisited[board.index(ax, ay)] && board.piece(ax, ay) == null) onward++
        }
        var key = onward * 10
        if (board.piece(nx, ny) != null && !board.isFixed(nx, ny)) key += 5
        if (drainPort != null) {
            val man = abs(nx - (drainX + drainPort!!.dx)) + abs(ny - (drainY + drainPort!!.dy))
            if (depth + man + 2 >= need) key = man * 10 + onward
        }
        return key
    }

    /** Gare la prochaine pièce hors de l'itinéraire, le plus loin possible du front. */
    private fun parkOffRoute(session: GameSession): Boolean {
        val board = session.board
        val onRoute = BooleanArray(board.size)
        for (step in route) onRoute[board.index(step.x, step.y)] = true
        val front = route.first()
        var bestScore = Int.MIN_VALUE
        var bx = -1
        var by = -1
        for (y in 0 until board.rows) for (x in 0 until board.cols) {
            if (onRoute[board.index(x, y)] || session.refusalAt(x, y) != null) continue
            if (isDrainEntry(board, x, y)) continue
            var score = minOf(abs(x - front.x) + abs(y - front.y), 10) * 4
            if (board.piece(x, y) != null) score -= 60
            var freeNeighbours = 0
            for (d in Direction.entries) {
                val ax = x + d.dx
                val ay = y + d.dy
                if (board.inBounds(ax, ay) && board.piece(ax, ay) == null && !onRoute[board.index(ax, ay)]) freeNeighbours++
            }
            score -= freeNeighbours * 3
            if (score > bestScore) {
                bestScore = score
                bx = x
                by = y
            }
        }
        if (bx < 0) return false
        return session.place(bx, by)
    }

    private fun isDrainEntry(board: Board, x: Int, y: Int): Boolean {
        for (d in Direction.entries) {
            val ax = x + d.dx
            val ay = y + d.dy
            if (!board.inBounds(ax, ay)) continue
            val p = board.piece(ax, ay) ?: continue
            if (p.family == PieceFamily.DRAIN && p.endPort == d.opposite) return true
        }
        return false
    }

    private companion object {
        const val REPLACE_COST = 90
        const val MISTAKE_CHOICES = 5
    }
}
