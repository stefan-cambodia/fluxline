package dev.stefan.fluxline.game

import dev.stefan.fluxline.core.Campaign
import dev.stefan.fluxline.core.LevelGenerator
import dev.stefan.fluxline.game.i18n.Language
import dev.stefan.fluxline.game.i18n.Strings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.security.MessageDigest

/**
 * Les traductions : complètes, cohérentes avec le français, entièrement
 * affichables par la police de leur langue, et le khmer pré-mis en forme à jour.
 */
class I18nTest {
    private val assets = File(System.getProperty("fluxline.assets") ?: "../assets")

    private fun table(file: String): Map<String, String> = Strings.parse(File(assets, file).readText())
    private fun source(lang: Language) = table("i18n/${lang.code}.properties")
    private fun runtime(lang: Language) = table(lang.stringsFile)

    private val pluralSuffix = Regex("\\.(one|few|many|other)$")
    private val placeholder = Regex("\\{([a-zA-Z]+)\\}")

    /** Clé « logique » : les formes plurielles d'une même clé sont regroupées. */
    private fun groups(t: Map<String, String>): Map<String, List<String>> =
        t.entries.groupBy({ it.key.replace(pluralSuffix, "") }, { it.value })

    @ParameterizedTest
    @EnumSource(Language::class)
    fun `memes cles et memes parametres que le francais`(lang: Language) {
        val ref = groups(source(Language.FR))
        val tr = groups(source(lang))
        assertEquals(ref.keys - tr.keys, emptySet<String>(), "clés manquantes en ${lang.code}")
        assertEquals(tr.keys - ref.keys, emptySet<String>(), "clés en trop en ${lang.code}")
        for ((key, values) in tr) {
            val expected = ref.getValue(key).flatMap { v -> placeholder.findAll(v).map { it.groupValues[1] } }.toSet()
            for (v in values) {
                val got = placeholder.findAll(v).map { it.groupValues[1] }.toSet()
                // {n} peut manquer d'une forme plurielle (« une vie ») mais aucun paramètre inconnu.
                assertTrue(expected.containsAll(got), "${lang.code} $key : paramètres $got, attendus $expected")
                assertTrue((expected - "n").all { it in got }, "${lang.code} $key : paramètre oublié dans « $v »")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    fun `chaque pluriel a les formes de sa langue`(lang: Language) {
        val t = source(lang)
        val needed = when (lang) {
            Language.FR, Language.EN -> listOf("one", "other")
            Language.RU -> listOf("one", "few", "many", "other")
            Language.ZH, Language.KM -> listOf("other")
        }
        val bases = source(Language.FR).keys.filter { pluralSuffix.containsMatchIn(it) }.map { it.replace(pluralSuffix, "") }.toSet()
        assertTrue(bases.isNotEmpty())
        for (base in bases) for (form in needed) assertTrue("$base.$form" in t, "${lang.code} : $base.$form manquant")
    }

    @ParameterizedTest
    @EnumSource(Language::class)
    fun `la police de la langue affiche chacun de ses caracteres`(lang: Language) {
        val values = runtime(lang).values
        // Les titres de niveau passent en capitales dans l'affichage du jeu.
        val chars = (values + values.map { it.uppercase() }).flatMap { s -> s.codePoints().toArray().toList() }.toSortedSet()
        for (weight in listOf("Bold", "SemiBold")) {
            val font = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, File(assets, lang.fontFile(weight)))
            val missing = chars.filter { it != '\n'.code && !font.canDisplay(it) }
            assertTrue(missing.isEmpty(), "${lang.fontFile(weight)} : glyphes absents ${missing.joinToString { "U+%04X".format(it) }}")
        }
    }

    @Test
    fun `le khmer pre-mis en forme est a jour`() {
        val src = File(assets, "i18n/km.properties")
        val shaped = File(assets, "i18n/km.shaped.properties")
        val digest = MessageDigest.getInstance("SHA-256").digest(src.readBytes()).joinToString("") { "%02x".format(it) }
        assertTrue(
            shaped.readLines().any { it == "# source-sha256 = $digest" },
            "km.properties a changé : relancer python3 tools/gen_fonts.py",
        )
        assertEquals(source(Language.KM).keys, runtime(Language.KM).keys)
        // Plus aucun caractère khmer brut : tout passe par les syllabes pré-assemblées.
        val raw = runtime(Language.KM).values.joinToString("").filter { it in '\u1780'..'\u17FF' }
        assertEquals("", raw)
        // « | » ne sert qu'à marquer les coupures dans la source khmère.
        for (lang in Language.entries) if (lang != Language.KM) assertTrue(source(lang).values.none { '|' in it }, lang.code)
    }

    @Test
    fun `chaque cle utilisee par le code existe`() {
        val fr = source(Language.FR)
        val src = File(System.getProperty("user.dir"), "src/main/kotlin")
        val literal = Regex("\"((?:menu|mode|hud|banner|intro|tutorial|pause|result|levels|options|stats|credits|lang)\\.[A-Za-z0-9.]+)\"")
        val used = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        assertTrue(used.size > 80, "analyse du code : seulement ${used.size} clés trouvées")
        for (key in used) assertTrue(key in fr || "$key.other" in fr, "clé absente des traductions : $key")
        // Clés construites à l'exécution.
        for (n in 1..Campaign.size) {
            assertTrue("level.$n.title" in fr && "level.$n.hint" in fr, "niveau $n")
        }
        for (kind in LevelGenerator.ChallengeKind.entries) {
            assertTrue("challenge.${kind.key}.title" in fr && "challenge.${kind.key}.desc" in fr, kind.key)
        }
    }

    @Test
    fun `regles de pluriel`() {
        fun ru(n: Int) = Strings.pluralCategory(Language.RU, n)
        assertEquals(listOf("one", "few", "many", "many", "one", "few", "many", "many", "many"), listOf(1, 2, 5, 11, 21, 22, 25, 111, 12).map(::ru))
        assertEquals(listOf("one", "one", "other"), listOf(0, 1, 2).map { Strings.pluralCategory(Language.FR, it) })
        assertEquals(listOf("other", "one", "other"), listOf(0, 1, 2).map { Strings.pluralCategory(Language.EN, it) })
        val t = Strings(Language.RU, source(Language.RU))
        assertEquals("Осталась 1 жизнь", t.plural("result.livesLeft", 1))
        assertEquals("Осталось 2 жизни", t.plural("result.livesLeft", 2))
        assertEquals("Цель: 25 труб", t.plural("intro.target", 25))
    }

    @Test
    fun `nombres et parametres`() {
        val fr = Strings(Language.FR, source(Language.FR))
        val en = Strings(Language.EN, source(Language.EN))
        assertEquals("12\u00A0345", fr.number(12345))
        assertEquals("1,234,567", en.number(1234567))
        assertEquals("950", en.number(950))
        assertEquals("2,6", fr.decimal(2.6f))
        assertEquals("2.6", en.decimal(2.6f))
        assertEquals("Level 3 · First flow", en.format("mode.level", "n" to 3, "title" to en["level.1.title"]))
        // Une clé absente se rabat sur l'anglais, puis sur elle-même.
        val partial = Strings(Language.RU, mapOf("menu.continue" to "Продолжить"), en)
        assertEquals("Продолжить", partial["menu.continue"])
        assertEquals("Classic", partial["menu.classic"])
        assertEquals("nope.key", partial["nope.key"])
    }

    @Test
    fun `lecture du format et langue du systeme`() {
        val t = Strings.parse("# commentaire\n\n a = b \nnl = x\\ny\nsp = \\u00A0\nu = \\u0436\n")
        assertEquals(mapOf("a" to "b", "nl" to "x\ny", "sp" to "\u00A0", "u" to "ж"), t)
        assertEquals(Language.ZH, Language.forSystem("zh-TW"))
        assertEquals(Language.KM, Language.forSystem("km_KH"))
        assertEquals(Language.EN, Language.forSystem("es"))
        assertEquals(Language.FR, Language.forSystem("fr"))
    }
}
