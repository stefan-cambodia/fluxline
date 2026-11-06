package dev.stefan.fluxline.core

/**
 * Un conduit à l'intérieur d'une pièce : il relie deux ports. `null` désigne le
 * centre de la case — c'est ainsi que sont modélisées la source (le fluide naît
 * au centre) et l'arrivée (il y disparaît).
 */
class Channel(val a: Direction?, val b: Direction?) {
    fun hasPort(port: Direction): Boolean = a == port || b == port

    /** L'autre extrémité du conduit quand on y entre par [entry]. */
    fun exitFor(entry: Direction?): Direction? = if (a == entry) b else a
}

/**
 * Catégories de pièces. Seules les pièces [STANDARD] sortent du distributeur ;
 * les autres sont posées par le niveau et ne peuvent pas être remplacées.
 */
enum class PieceFamily { STANDARD, SOURCE, DRAIN, RESERVOIR, ONE_WAY, BLOCK }

/**
 * Toutes les pièces du jeu. Chaque pièce connaît ses conduits, donc ses ports :
 * la circulation du fluide se décide uniquement à partir de ces données, jamais
 * à partir de ce qui est dessiné.
 *
 * [code] est le caractère utilisé dans les plans de niveaux (voir [LevelSpec]).
 * [fillFactor] multiplie la durée de remplissage d'un conduit (un réservoir est
 * lent, la source et l'arrivée sont des demi-cases).
 */
enum class Piece(
    val code: Char,
    val family: PieceFamily,
    val channels: List<Channel>,
    val fillFactor: Float = 1f,
    /** Pour un sens unique : la seule direction dans laquelle le fluide peut circuler. */
    val flowDirection: Direction? = null,
) {
    STRAIGHT_H('-', PieceFamily.STANDARD, listOf(Channel(Direction.W, Direction.E))),
    STRAIGHT_V('|', PieceFamily.STANDARD, listOf(Channel(Direction.N, Direction.S))),
    ELBOW_NE('L', PieceFamily.STANDARD, listOf(Channel(Direction.N, Direction.E))),
    ELBOW_ES('F', PieceFamily.STANDARD, listOf(Channel(Direction.E, Direction.S))),
    ELBOW_SW('7', PieceFamily.STANDARD, listOf(Channel(Direction.S, Direction.W))),
    ELBOW_WN('J', PieceFamily.STANDARD, listOf(Channel(Direction.W, Direction.N))),
    CROSS(
        '+',
        PieceFamily.STANDARD,
        listOf(Channel(Direction.W, Direction.E), Channel(Direction.N, Direction.S)),
    ),

    SOURCE_N('^', PieceFamily.SOURCE, listOf(Channel(null, Direction.N)), 0.6f),
    SOURCE_E('>', PieceFamily.SOURCE, listOf(Channel(null, Direction.E)), 0.6f),
    SOURCE_S('v', PieceFamily.SOURCE, listOf(Channel(null, Direction.S)), 0.6f),
    SOURCE_W('<', PieceFamily.SOURCE, listOf(Channel(null, Direction.W)), 0.6f),

    DRAIN_N('n', PieceFamily.DRAIN, listOf(Channel(Direction.N, null)), 0.6f),
    DRAIN_E('e', PieceFamily.DRAIN, listOf(Channel(Direction.E, null)), 0.6f),
    DRAIN_S('s', PieceFamily.DRAIN, listOf(Channel(Direction.S, null)), 0.6f),
    DRAIN_W('w', PieceFamily.DRAIN, listOf(Channel(Direction.W, null)), 0.6f),

    RESERVOIR_H('H', PieceFamily.RESERVOIR, listOf(Channel(Direction.W, Direction.E)), 3f),
    RESERVOIR_V('V', PieceFamily.RESERVOIR, listOf(Channel(Direction.N, Direction.S)), 3f),

    ONE_WAY_N('N', PieceFamily.ONE_WAY, listOf(Channel(Direction.S, Direction.N)), flowDirection = Direction.N),
    ONE_WAY_E('E', PieceFamily.ONE_WAY, listOf(Channel(Direction.W, Direction.E)), flowDirection = Direction.E),
    ONE_WAY_S('S', PieceFamily.ONE_WAY, listOf(Channel(Direction.N, Direction.S)), flowDirection = Direction.S),
    ONE_WAY_W('W', PieceFamily.ONE_WAY, listOf(Channel(Direction.E, Direction.W)), flowDirection = Direction.W),

    BLOCK('#', PieceFamily.BLOCK, emptyList());

    val isStandard: Boolean get() = family == PieceFamily.STANDARD

    /** Port de sortie d'une source, port d'entrée d'une arrivée. */
    val endPort: Direction?
        get() = when (family) {
            PieceFamily.SOURCE -> channels[0].b
            PieceFamily.DRAIN -> channels[0].a
            else -> null
        }

    fun hasPort(port: Direction): Boolean = channels.any { it.hasPort(port) }

    /**
     * Index du conduit qui accepte du fluide arrivant par [port], ou -1.
     * Un sens unique refuse le fluide qui arrive à contre-courant.
     */
    fun channelAccepting(port: Direction): Int {
        for (i in channels.indices) {
            if (!channels[i].hasPort(port)) continue
            if (flowDirection != null && port != flowDirection.opposite) return -1
            return i
        }
        return -1
    }

    /** Quart de tour horaire — uniquement pour les pièces du distributeur. */
    fun rotatedClockwise(): Piece = when (this) {
        STRAIGHT_H -> STRAIGHT_V
        STRAIGHT_V -> STRAIGHT_H
        ELBOW_NE -> ELBOW_ES
        ELBOW_ES -> ELBOW_SW
        ELBOW_SW -> ELBOW_WN
        ELBOW_WN -> ELBOW_NE
        CROSS -> CROSS
        else -> this
    }

    companion object {
        val STANDARD_PIECES: List<Piece> = entries.filter { it.isStandard }

        private val BY_CODE: Map<Char, Piece> = entries.associateBy { it.code }

        fun byCode(code: Char): Piece? = BY_CODE[code]

        fun source(direction: Direction): Piece = when (direction) {
            Direction.N -> SOURCE_N
            Direction.E -> SOURCE_E
            Direction.S -> SOURCE_S
            Direction.W -> SOURCE_W
        }

        fun drain(port: Direction): Piece = when (port) {
            Direction.N -> DRAIN_N
            Direction.E -> DRAIN_E
            Direction.S -> DRAIN_S
            Direction.W -> DRAIN_W
        }

        fun oneWay(flow: Direction): Piece = when (flow) {
            Direction.N -> ONE_WAY_N
            Direction.E -> ONE_WAY_E
            Direction.S -> ONE_WAY_S
            Direction.W -> ONE_WAY_W
        }

        fun reservoir(horizontal: Boolean): Piece = if (horizontal) RESERVOIR_H else RESERVOIR_V
    }
}
