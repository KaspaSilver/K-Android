package com.kachat.app.res

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every `%` in every string resource, in every language, is safe (release audit 2026-11-27 H1).
 *
 * `getString(id, *args)` runs `String.format` even with no arguments, so "99% of this quote"
 * parsed `% o` as a placeholder and the Trestly pool sheet crashed in all 19 languages. The rules:
 * - A string with a placeholder is always formatted: each placeholder is Android's own
 *   (`%s`, `%1$s`, `%d`, `%1$d`, `%.2f`), and every literal percent is written `%%`.
 * - A string with no placeholder is read as-is (`stringResource(id)`, `getString(id)`, [tr] with no
 *   arguments). A literal `%` is fine there, but `%%` would show doubled, and the code must never
 *   pass it arguments.
 * - A plural is always formatted, so it follows the first rule.
 */
class StringPercentSignTest {
    private val androidSpec = Regex("""%(\d+\$)?(\.\d+)?[sdf]""")

    private data class Res(val where: String, val name: String, val text: String, val plural: Boolean)

    private fun resDir(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private fun srcDir(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun allStrings(): List<Res> {
        val factory = DocumentBuilderFactory.newInstance()
        return resDir().listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .flatMap { file ->
                val doc = factory.newDocumentBuilder().parse(file)
                val where = "${file.parentFile.name}/${file.name}"
                val out = mutableListOf<Res>()
                val strings = doc.getElementsByTagName("string")
                for (i in 0 until strings.length) {
                    val el = strings.item(i) as Element
                    out += Res(where, el.getAttribute("name"), el.textContent, plural = false)
                }
                val plurals = doc.getElementsByTagName("plurals")
                for (i in 0 until plurals.length) {
                    val el = plurals.item(i) as Element
                    val items = el.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        out += Res(where, el.getAttribute("name"), items.item(j).textContent, plural = true)
                    }
                }
                out
            }
    }

    /** Each `%` in [text]: true for a placeholder, false for a literal; null for a `%%`. */
    private fun percents(text: String): List<Pair<String, Boolean?>> {
        val out = mutableListOf<Pair<String, Boolean?>>()
        var i = 0
        while (true) {
            val j = text.indexOf('%', i)
            if (j < 0) break
            if (text.startsWith("%%", j)) {
                out += "%%" to null; i = j + 2; continue
            }
            val m = androidSpec.find(text, j)?.takeIf { it.range.first == j }
            if (m != null) {
                out += m.value to true; i = m.range.last + 1
            } else {
                out += text.substring(j, minOf(text.length, j + 6)) to false; i = j + 1
            }
        }
        return out
    }

    @Test
    fun everyPercentSignIsSafeInEveryLocale() {
        val bad = mutableListOf<String>()
        for (r in allStrings()) {
            val p = percents(r.text)
            val formatted = r.plural || p.any { it.second == true }
            if (formatted) {
                p.filter { it.second == false }.forEach { bad += "${r.where} ${r.name}: bare \"${it.first}\" in a formatted string (write %%)" }
            } else if (p.any { it.second == null }) {
                bad += "${r.where} ${r.name}: %% in a string that's never formatted shows doubled"
            }
        }
        assertTrue("Unsafe % signs:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun stringsWithALiteralPercentAreNeverGivenArguments() {
        val literal = allStrings().filter { r -> !r.plural && percents(r.text).any { it.second == false } }
            .map { it.name }.toSet()
        val sources = srcDir().walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.toList()
        val bad = mutableListOf<String>()
        for (name in literal) {
            val withArgs = Regex("""R\.string\.$name\s*,""")
            sources.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (withArgs.containsMatchIn(line)) bad += "${f.name}:${i + 1} formats $name, which has a literal %"
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun theRuleCatchesTheTrestlyCrash() {
        // The shape that crashed: a literal "99% of" next to nothing to fill in.
        val p = percents("never takes below 99% of this quote.")
        assertTrue(p.single().second == false)
        assertTrue(percents("%1\$s sent %2\$d").all { it.second == true })
        assertTrue(percents("100%% sure").single().second == null)
    }
}
