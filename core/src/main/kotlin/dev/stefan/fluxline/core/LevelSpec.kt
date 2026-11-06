package dev.stefan.fluxline.core

/**
 * La description complète d'une manche : plateau de départ et paramètres.
 *
 * Le plan [layout] est une liste de lignes (la première en haut), un caractère
 * par case :
 *
 * | Caractère       | Contenu                                              |
 * |-----------------|------------------------------------------------------|
 * | `.`             | case vide                                            |
 * | `#`             | bloc infranchissable                                 |
 * | `^ > v <`       | source, ouverte vers le nord / l'est / le sud / l'ouest |
 * | `n e s w`       | arrivée, ouverte vers le nord / l'est / le sud / l'ouest |
 * | `H V`           | réservoir horizontal / vertical (lent, rapporte plus) |
 * | `N E S W`       | sens unique : le fluide n'y circule que vers ce point cardinal |
 * | `- \| L F 7 J +` | tuyau standard déjà posé (fixe)                     |
 *
 * Toute pièce présente dans le plan est fixe : le joueur ne peut ni la retirer ni
 * la remplacer.
 */
class LevelSpec(
    val id: String,
    val title: String,
    val layout: List<String>,
    /** Nombre de tuyaux que le fluide doit parcourir pour réussir. */
    val target: Int,
    /** Secondes avant que le fluide ne s'élance. */
    val countdown: Float,
    /** Durée de remplissage d'un tuyau standard, en secondes. */
    val secondsPerCell: Float,
    val queueSeed: Long,
    val allowRotation: Boolean = false,
    /** Si vrai, seule l'arrivée termine la manche avec succès. */
    val requireDrain: Boolean = false,
    /** Nombre maximal de pièces distribuées (0 = illimité). */
    val pieceLimit: Int = 0,
    /** Facteur de vitesse du bouton « Accélérer ». */
    val fastFactor: Float = 9f,
) {
    val rows: Int = layout.size
    val cols: Int = layout.firstOrNull()?.length ?: 0

    init {
        require(rows >= 3 && cols >= 3) { "$id : plateau trop petit" }
        require(layout.all { it.length == cols }) { "$id : lignes de longueurs différentes" }
        var sources = 0
        for (row in layout) for (c in row) {
            if (c == '.') continue
            val piece = requireNotNull(Piece.byCode(c)) { "$id : caractère inconnu '$c'" }
            if (piece.family == PieceFamily.SOURCE) sources++
        }
        require(sources == 1) { "$id : il faut exactement une source (trouvé $sources)" }
        require(target > 0) { "$id : objectif nul" }
        require(secondsPerCell > 0f && countdown >= 0f)
    }

    fun pieceAt(x: Int, y: Int): Piece? {
        val c = layout[y][x]
        return if (c == '.') null else Piece.byCode(c)
    }

    val hasDrain: Boolean get() = layout.any { row -> row.any { Piece.byCode(it)?.family == PieceFamily.DRAIN } }

    fun copy(
        id: String = this.id,
        title: String = this.title,
        layout: List<String> = this.layout,
        target: Int = this.target,
        countdown: Float = this.countdown,
        secondsPerCell: Float = this.secondsPerCell,
        queueSeed: Long = this.queueSeed,
        allowRotation: Boolean = this.allowRotation,
        requireDrain: Boolean = this.requireDrain,
        pieceLimit: Int = this.pieceLimit,
        fastFactor: Float = this.fastFactor,
    ) = LevelSpec(id, title, layout, target, countdown, secondsPerCell, queueSeed, allowRotation, requireDrain, pieceLimit, fastFactor)

    companion object {
        const val COLS = 7
        const val ROWS = 10
    }
}
