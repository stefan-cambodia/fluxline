package dev.stefan.fluxline.game.i18n

import java.util.Locale

/** Les langues du jeu. */
enum class Language(val code: String) {
    FR("fr"),
    EN("en"),
    RU("ru"),
    ZH("zh"),
    KM("km");

    /**
     * Le fichier de textes lu par le jeu. Le khmer est lu déjà mis en forme
     * (tools/gen_fonts.py) : FreeType ne sait pas assembler son écriture.
     */
    val stringsFile: String get() = if (this == KM) "i18n/km.shaped.properties" else "i18n/$code.properties"

    /** Écriture latine : Chakra Petch suffit. */
    val latin: Boolean get() = this == FR || this == EN

    /** Chakra Petch seule, ou Chakra Petch complétée par l'écriture de la langue (tools/gen_fonts.py). */
    fun fontFile(weight: String): String = if (latin) "fonts/ChakraPetch-$weight.ttf" else "fonts/Fluxline-$code-$weight.ttf"

    /** Écritures aux nombreux glyphes : ils sont rastérisés à la demande plutôt que tous d'avance. */
    val incremental: Boolean get() = this == ZH || this == KM

    companion object {
        fun byCode(code: String?): Language? = entries.firstOrNull { it.code == code }

        /** Langue du système → langue du jeu ; l'anglais pour toute langue non traduite. */
        fun forSystem(tag: String): Language =
            byCode(tag.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) ?: EN
    }
}

/**
 * Les textes d'une langue. Les clés manquantes se rabattent sur [fallback]
 * (l'anglais), puis sur la clé elle-même — visible, donc repérable.
 *
 * Paramètres nommés : « Objectif : {n} tuyaux ». Pluriels : clé.one, clé.few,
 * clé.many, clé.other, choisis selon les règles de la langue.
 */
class Strings(
    val language: Language,
    private val table: Map<String, String>,
    private val fallback: Strings? = null,
) {
    private val groupSeparator = table["fmt.group"] ?: ","
    private val decimalSeparator = table["fmt.decimal"] ?: "."

    operator fun get(key: String): String = table[key] ?: fallback?.get(key) ?: key

    fun has(key: String): Boolean = table.containsKey(key)

    val keys: Set<String> get() = table.keys

    fun format(key: String, vararg args: Pair<String, Any>): String = fill(get(key), args, null)

    fun plural(key: String, n: Int, vararg args: Pair<String, Any>): String {
        val pattern = table["$key.${pluralCategory(language, n)}"] ?: table["$key.other"]
            ?: return fallback?.plural(key, n, *args) ?: key
        return fill(pattern, args, n)
    }

    /** Un entier avec le séparateur de milliers de la langue. */
    fun number(value: Int): String {
        val digits = kotlin.math.abs(value.toLong()).toString()
        val sb = StringBuilder()
        if (value < 0) sb.append('−')
        for (i in digits.indices) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(groupSeparator)
            sb.append(digits[i])
        }
        return sb.toString()
    }

    /** Un nombre à [digits] décimales, avec la virgule ou le point selon la langue. */
    fun decimal(value: Float, digits: Int = 1): String =
        String.format(Locale.ROOT, "%.${digits}f", value).replace(".", decimalSeparator)

    private fun fill(pattern: String, args: Array<out Pair<String, Any>>, n: Int?): String {
        if (pattern.indexOf('{') < 0) return pattern
        val sb = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            val end = if (c == '{') pattern.indexOf('}', i + 1) else -1
            if (end < 0) {
                sb.append(c)
                i++
                continue
            }
            val name = pattern.substring(i + 1, end)
            val value = args.firstOrNull { it.first == name }?.second ?: (if (name == "n") n else null)
            if (value != null) sb.append(value.toString()) else sb.append(pattern, i, end + 1)
            i = end + 1
        }
        return sb.toString()
    }

    companion object {
        /** Catégorie plurielle d'un entier (règles CLDR, réduites aux entiers). */
        fun pluralCategory(language: Language, n: Int): String {
            val a = kotlin.math.abs(n)
            return when (language) {
                Language.FR -> if (a <= 1) "one" else "other"
                Language.EN -> if (a == 1) "one" else "other"
                Language.RU -> when {
                    a % 10 == 1 && a % 100 != 11 -> "one"
                    a % 10 in 2..4 && a % 100 !in 12..14 -> "few"
                    else -> "many"
                }
                Language.ZH, Language.KM -> "other"
            }
        }

        /**
         * Lit un fichier « clé = valeur » (UTF-8). Lignes vides et commentaires (#)
         * ignorés ; échappements \n, \\ et \uXXXX. Les blancs autour de la clé et de
         * la valeur sont retirés (un blanc voulu s'écrit \u0020 ou \u00A0).
         */
        fun parse(text: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (raw in text.removePrefix("\uFEFF").lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                require(eq > 0) { "ligne invalide : $line" }
                map[line.substring(0, eq).trim()] = unescape(line.substring(eq + 1).trim())
            }
            return map
        }

        private fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                        'u' -> if (i + 5 < s.length) {
                            sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 6
                            continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
