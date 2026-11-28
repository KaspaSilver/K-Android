package com.kachat.app.util

import com.kachat.app.R
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Release audit 2026-11-27 L2: the error sentences and fallbacks are translated, by their English. */
class UserFacingErrorFallbackTest {
    private fun englishStrings(): Map<String, String> {
        val res = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        val out = mutableMapOf<String, String>()
        res.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                out[el.getAttribute("name")] = el.textContent.replace("\\'", "'").replace("\\\"", "\"")
            }
        }
        return out
    }

    @Test
    fun everyKnownSentenceIsTheEnglishOfItsString() {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val english = englishStrings()
        val wrong = UserFacingError.KNOWN_SENTENCES.mapNotNull { (sentence, id) ->
            val name = names[id] ?: return@mapNotNull "$sentence: no resource"
            if (english[name] != sentence) "$sentence: $name reads \"${english[name]}\"" else null
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun theCallSitesFallbacksAreKnown() {
        listOf(
            "Failed to send", "Something went wrong", "Could not load this folder.", "Failed to build transaction",
            "Edit failed", "Reaction failed", "Failed to create group", "Backup failed", "Could not connect",
        ).forEach { assertTrue(it, UserFacingError.KNOWN_SENTENCES.containsKey(it)) }
    }

    @Test
    fun anHttpErrorNamesItsCode() {
        val e = retrofit2.HttpException(retrofit2.Response.error<Any>(503, "".toResponseBody(null)))
        // No app resources in a unit test: the English.
        assertEquals("The server returned an error (503). Try again in a moment.", UserFacingError.message(e, "Failed to send"))
        assertEquals("Failed to send", UserFacingError.message(null as Throwable?, "Failed to send"))
    }
}
