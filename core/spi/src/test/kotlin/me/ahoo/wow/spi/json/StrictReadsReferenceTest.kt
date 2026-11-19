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

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The JSON values reference's last column (`documentation/docs/{en,zh}/reference/json-values.md`) states no value that
 * is not a row of [StrictReadsTable], and none otherwise than its row.
 *
 * In each language, a sentence of the column that reads (`Reads`, `读取`, `把`) or refuses (`Refuses`, `拒绝`) names its
 * inputs as JSON in code spans: every code span that is JSON is one, and one followed by ` as ` (`读作`) and another
 * span is read and written back as that span. A span that is no JSON (`int`, `Z`) is prose; a sentence of neither kind
 * names no JSON.
 */
class StrictReadsReferenceTest {
    /** A value the page states of the classes of its row: [read], or refused; written back as [written] if named. */
    private data class Statement(
        val page: String,
        val classes: List<String>,
        val json: String,
        val read: Boolean,
        val written: String?,
    )

    private val root = File(requireNotNull(System.getProperty(ROOT)) { "System property $ROOT is not set." })

    private val statements: List<Statement> = LANGUAGES.flatMap { (language, words) ->
        val page = "documentation/docs/$language/reference/json-values.md"
        File(root, page).readLines().filter { it.startsWith("| `") }.flatMap { line ->
            val cells = line.split(" | ")
            val classes = SPAN.findAll(cells.first()).map { it.groupValues[1] }.toList()
            words.sentencesOf(cells.last().removeSuffix(" |")).flatMap { statementsOf(page, classes, it, words) }
        }
    }

    private fun statementsOf(page: String, classes: List<String>, sentence: String, words: Words): List<Statement> {
        val read = when {
            words.reads.any(sentence::startsWith) -> true
            words.refuses.any(sentence::startsWith) -> false
            else -> null
        }
        val spans = SPAN.findAll(sentence).toList()
        val statements = mutableListOf<Statement>()
        var index = 0
        while (index < spans.size) {
            val span = spans[index]
            val json = span.groupValues[1]
            index++
            if (!isJson(json)) continue
            requireNotNull(read) { "$page: ${classes.first()}: \"$sentence\" names $json and reads or refuses none" }
            val next = spans.getOrNull(index)
            val written = next?.takeIf { words.writtenAs(sentence.substring(span.range.last + 1, it.range.first)) }
            if (written != null) index++
            statements += Statement(page, classes, json, read, written?.groupValues?.get(1))
        }
        return statements
    }

    @Test
    fun `every value the column states should be a row of the table, read or refused as the column says`() {
        val rows = StrictReadsTable.rows
        val wrong = statements.mapNotNull { statement ->
            val described = "${statement.page}: ${statement.classes} ${statement.json}"
            val matching = rows.filter { referenceName(it.type) in statement.classes && it.json == statement.json }
            val expected = matching.firstOrNull()?.expected
            when {
                expected == null -> "$described: no row"
                statement.read != (expected is StrictReadsTable.Reads) -> "$described: the page and the table differ"
                statement.written != null && (expected as StrictReadsTable.Reads).written != statement.written ->
                    "$described: written back as ${statement.written} on the page, ${expected.written} in the table"

                else -> null
            }
        }
        statements.assert().describedAs("the column states no value").isNotEmpty()
        wrong.assert().describedAs(wrong.joinToString("\n")).isEmpty()
    }

    /** How a language's column says that a sentence reads or refuses, and that a value is written back as another. */
    private class Words(
        val reads: List<String>,
        val refuses: List<String>,
        val sentencesOf: (String) -> List<String>,
        val writtenAs: (String) -> Boolean,
    )

    private companion object {
        const val ROOT = "wow-test.repository.root"
        val SPAN = Regex("`([^`]+)`")

        val LANGUAGES = mapOf(
            "en" to Words(
                reads = listOf("Reads"),
                refuses = listOf("Refuses"),
                sentencesOf = { cell -> cell.split(Regex("""(?<=\.) (?=[A-Z])""")) },
                writtenAs = { between -> between == " as " },
            ),
            "zh" to Words(
                reads = listOf("读取", "把"),
                refuses = listOf("拒绝"),
                sentencesOf = { cell -> cell.split("。").filter { it.isNotBlank() } },
                writtenAs = { between -> between.trim().trim('，', ',').trim() == "读作" },
            ),
        )

        fun isJson(text: String): Boolean = runCatching { WowJson.mapper.readTree(text) }.isSuccess

        /** A class as the page names it: by its simple name, Kotlin's duration by its qualified one. */
        fun referenceName(type: Class<*>): String =
            if (type == kotlin.time.Duration::class.java) type.name else type.simpleName
    }
}
