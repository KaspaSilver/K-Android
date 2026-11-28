package com.kachat.app.res

import com.kachat.app.repository.StoredKasPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds keep the field names of what Gson saves and reads (release audit 2026-11-27 L1,
 * L7). `proguard-rules.pro` keeps every class in `services`, `models` and `util`; a class anywhere
 * else that Gson reads (`fromJson(..., X::class.java)` or `TypeToken<...X...>`) needs a rule of
 * its own, or R8 renames its fields and saved JSON reads back empty or shifted. Classes nested
 * inside a target as field types aren't seen here: keep them next to their parent's rule.
 */
class GsonKeepRulesTest {
    private fun dir(vararg c: String): File = c.map(::File).first { it.exists() }
    private val src get() = dir("src/main/java", "app/src/main/java")
    private val rules get() = dir("proguard-rules.pro", "app/proguard-rules.pro").readText()

    private val notAppTypes = setOf(
        "List", "MutableList", "Map", "MutableMap", "Set", "Array", "String", "Long", "Int", "Double",
        "Float", "Boolean", "Any", "JsonObject", "JsonElement", "JsonArray",
    )
    private val keptPackage = Regex("""com\.kachat\.app\.(services|models|util)(\..*)?""")

    @Test
    fun everyGsonTargetOutsideKeptPackagesHasAKeepRule() {
        val files = src.walkTopDown().filter { it.extension == "kt" }.map { it to it.readText() }.toList()
        val targets = mutableSetOf<String>()
        val fromJson = Regex("""fromJson(?:<[^(]*>)?\([^;\n]*?([A-Za-z0-9_.<>]+)::class\.java""")
        val typeToken = Regex("""TypeToken<(.*?)>\(\)""")
        val ident = Regex("""[A-Z][A-Za-z0-9_]*""")
        files.forEach { (_, text) ->
            fromJson.findAll(text).forEach { m -> ident.findAll(m.groupValues[1]).forEach { targets += it.value } }
            typeToken.findAll(text).forEach { m -> ident.findAll(m.groupValues[1]).forEach { targets += it.value } }
        }
        targets -= notAppTypes
        val missing = mutableListOf<String>()
        for ((file, text) in files) {
            val pkg = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: continue
            if (keptPackage.matches(pkg)) continue
            for (name in targets) {
                if (!Regex("""\bclass $name\b""").containsMatchIn(text)) continue
                val rule = Regex("""-keepclassmembers class ${Regex.escape(pkg)}\.([\w$]*\$)?$name \{""")
                if (!rule.containsMatchIn(rules)) missing += "$pkg.$name (${file.name})"
            }
        }
        assertTrue("Gson classes R8 may rename, add a -keepclassmembers rule:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun portfolioAndSendShareOnePriceRecord() {
        val json = StoredKasPrice(1_700_000_000_000, 0.123, -2.5).toJson()
        assertTrue(json.contains("\"fetchedAt\"") && json.contains("\"price\"") && json.contains("\"change\""))
        assertEquals(StoredKasPrice(1_700_000_000_000, 0.123, -2.5), StoredKasPrice.fromJson(json))
        // The record an earlier release build wrote with R8-renamed fields reads as none, not 0.
        assertNull(StoredKasPrice.fromJson("""{"a":1700000000000,"b":0.123,"c":-2.5}"""))
        assertNull(StoredKasPrice.fromJson("""{"fetchedAt":1,"price":0.0}"""))
        assertNull(StoredKasPrice.fromJson("not json"))
        assertNull(StoredKasPrice.fromJson(null))
        assertEquals(null, StoredKasPrice.fromJson("""{"fetchedAt":5,"price":0.2}""")?.change)
    }
}
