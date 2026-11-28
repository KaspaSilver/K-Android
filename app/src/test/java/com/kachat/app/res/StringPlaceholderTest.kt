package com.kachat.app.res

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every string resource uses Android's format placeholders. An iOS one copied from a .lproj
 * (`%@`, `%1$@`, `%lld`) makes `getString(id, args)` throw UnknownFormatConversionException, a
 * crash in that language only (audit, 2026-11-27: three kUSDT strings in 18 languages).
 */
class StringPlaceholderTest {
    private val iosPlaceholder = Regex("""%(\d+\$)?(@|lld|llu|ld|lu|zd)""")

    @Test
    fun noIosPlaceholdersInAnyLocale() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val bad = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (iosPlaceholder.containsMatchIn(line)) "${file.parentFile.name}/${file.name}:${i + 1}" else null
                }
            }
        assertTrue("iOS-style placeholders: $bad", bad.isEmpty())
    }
}
