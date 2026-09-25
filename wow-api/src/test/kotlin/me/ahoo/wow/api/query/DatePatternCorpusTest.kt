/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Writes the corpus of `datePattern` values that `typescript/wow-client` holds
 * its build-time check to, and fails when the committed file no longer says
 * what this JVM says. Each pattern goes through a relative-time filter's
 * constructor, the entry a server-side `datePattern` takes: blank patterns are
 * refused there, the rest by `DateTimeFormatter.ofPattern`.
 *
 * Regenerate with `./gradlew :wow-api:test --tests "*DatePatternCorpusTest" -Dwow.snapshot.update=true`.
 */
class DatePatternCorpusTest {
    companion object {
        private val FIXTURE: Path =
            Path.of("..", "typescript", "wow-client", "test", "fixtures", "java-date-patterns.json")
        private val LETTERS: List<String> = (('A'..'Z') + ('a'..'z')).map(Char::toString)
        private const val MAX_REPEAT = 20
        private const val MAX_PAD_FIELD_REPEAT = 6

        /** Everything Kotlin's `isBlank` or JavaScript's `trim` treats as whitespace, and near misses. */
        private val SPACES = listOf(
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680, 0x180E, 0x2000,
            0x2007, 0x200A, 0x200B, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
        ).map { it.toChar().toString() }

        private val STRUCTURE = listOf(
            "'", "''", "'''", "''''", "'a'", "'a", "a'", "'yyyy'", "yyyy'", "'it''s'", "'p'", "'{'", "'#'",
            "[", "]", "[]", "[[", "]]", "[]]", "[[]", "[yyyy", "yyyy]", "[yyyy]]", "[[yyyy]", "['['", "']'",
            "{", "}", "#", "yyyy{MM}", "yyyy#", "{yyyy}",
            "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyyMMdd",
            "yyyyMMddHHmmss", "yyyyMMddHHmmssSSS", "uuuu-MM-dd", "EEE, d MMM yyyy HH:mm:ss Z", "h:mm a",
            "HH:mm:ss.SSSSSSSSS", "yyyy-MM-dd 'o''clock'['T'HH:mm:ss]", "yyyy-'W'ww-e", "YYYY-'W'ww-e",
            "yyyy年MM月dd日", "年月日", "é", "ü", "日", "123", "yyyy 2024",
            "p", "pp", "p'", "p'x'", "p[", "p]", "p ", "p1", "pé", "[p]", "[pH]", "pH[m]", "[pH]m", "pH'x'm",
            "pH m", "ppHH[", "pS:mm", "pym", "pSH", "HHpmm", "HHpmmss", "yyyyp", "p#", "pHp", "pHpm",
            "pH[]m", "pH[[]]m", "pH[x]m", "pH['x']m", "pH''m", "[pH]m", "[pH][m]", "[pHm]", "[pH", "pH[m",
            "HpHHm", "HHpHm", "HHpHHm", "HHpHHmm", "HpHHpmms", "HHpHHpms", "HHpHHpmms", "pHHmm", "yyyyMMddpHHmm",
            "ppppppppppHH", "pppH[m]", "H[]pHm", "HH[]pHHm", "[HH]pHHm", "HH[pHH]m", "HH'x'pHm",
        )

        /** Runs that name a field in some pattern, used to probe what a padded field may touch. */
        private fun validRuns(): List<String> =
            LETTERS.flatMap { letter -> (1..MAX_REPEAT).map(letter::repeat) }.filter(::accepts)

        private fun corpus(): Set<String> {
            val corpus = linkedSetOf("")
            corpus += SPACES
            corpus += SPACES.map { "$it$it" }
            corpus += SPACES.map { "${it}yyyy$it" }
            (0x20..0x7E).map { it.toChar().toString() }.filterNot { it in LETTERS }.forEach { corpus += it }
            LETTERS.forEach { letter -> (1..MAX_REPEAT).forEach { corpus += letter.repeat(it) } }
            (1..2).forEach { pads ->
                val pad = "p".repeat(pads)
                LETTERS.forEach { letter -> (1..MAX_PAD_FIELD_REPEAT).forEach { corpus += pad + letter.repeat(it) } }
            }
            // Adjacent value parsing: for every run that names a field, the
            // run padded, after a padded field, between two fields with a
            // padded one in the middle, and as the field a padded one follows.
            validRuns().forEach { run ->
                val (x, y) = listOf("H", "m", "s").filterNot { run.startsWith(it) }
                corpus += "p$run$y"
                corpus += "p$x$run"
                corpus += "$run$y"
                corpus += "p$run'x'$y"
                corpus += "${x}p$run$y"
                corpus += "$x${x}p$run$y"
                corpus += "${run}p$x$x$y"
                corpus += "${run}p$x$y"
            }
            corpus += STRUCTURE
            return corpus
        }

        private fun accepts(pattern: String): Boolean =
            runCatching { TodayFilter(QueryField("createTime"), datePattern = pattern) }.isSuccess

        private fun render(): String {
            val mapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()
            val entries = corpus().joinToString(",\n") { pattern ->
                "    [${mapper.writeValueAsString(pattern)}, ${accepts(pattern)}]"
            }
            return buildString {
                append("{\n")
                append(
                    "  \"source\": \"wow-api DatePatternCorpusTest: TodayFilter(datePattern), DateTimeFormatter.ofPattern\",\n"
                )
                append("  \"javaSpecificationVersion\": ")
                append(mapper.writeValueAsString(System.getProperty("java.specification.version")))
                append(",\n")
                append("  \"patterns\": [\n")
                append(entries)
                append("\n  ]\n}\n")
            }
        }
    }

    @Test
    fun `the date-pattern corpus should say what DateTimeFormatter accepts`() {
        val actual = render()
        if (System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)) {
            FIXTURE.writeText(actual)
        }
        actual.assert().isEqualTo(FIXTURE.readText())
    }
}
