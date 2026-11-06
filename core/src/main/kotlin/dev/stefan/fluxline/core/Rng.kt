package dev.stefan.fluxline.core

/**
 * Générateur pseudo-aléatoire SplitMix64. Son état tient dans un seul `Long`,
 * ce qui le rend trivial à sauvegarder et garantit qu'une graine donne toujours
 * la même partie, sur toutes les plateformes (contrairement à java.util.Random
 * dont on ne contrôle pas l'implémentation).
 */
class Rng(var state: Long) {

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Entier uniforme dans [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound doit être positif" }
        return ((nextLong() ushr 33) % bound).toInt()
    }

    /** Entier uniforme dans [from, until). */
    fun nextInt(from: Int, until: Int): Int = from + nextInt(until - from)

    /** Réel uniforme dans [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()

    fun chance(probability: Float): Boolean = nextFloat() < probability

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }

    companion object {
        /** Mélange une graine « humaine » (date, numéro de niveau) en un état bien réparti. */
        fun mix(vararg parts: Long): Long {
            val r = Rng(0x5EED_F1A5L)
            var acc = 0L
            for (p in parts) {
                r.state = r.state xor p
                acc = acc xor r.nextLong()
            }
            return acc
        }
    }
}
