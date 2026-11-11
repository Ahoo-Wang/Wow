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

package me.ahoo.wow.architecture

import me.ahoo.wow.architecture.ArchitectureRules.productionSources
import java.io.File

/** What the string literals of the production sources may not hold: a design id, a meter name of its own. */
object SourceLiteralRules {
    /** A decision (`D254`) or a review finding (`R2.3-1`, `A5-14`, `Q-13`, `X-4`, `M-2`, `R4-Q`). */
    internal val DECISION_OR_REVIEW_ID =
        Regex("""\bD\d{2,3}\b|\b(?:R\d(?:\.\d+)?-[A-Z0-9]+|A\d-\d+|Q-\d+|X-\d+|M-\d+)\b""")

    /**
     * The string literals of the modules' production sources that name a decision or a review finding. Such a string
     * reaches users, in an exception, a rejection, an error description, a configuration description or generated
     * code, and they cannot look the ID up: the string says the reason instead. Comments may cite IDs.
     */
    fun idsInStringLiterals(repositoryRoot: File): List<String> =
        productionSources(repositoryRoot).flatMap { file ->
            stringLiterals(file.readText()).filter { (_, text) -> DECISION_OR_REVIEW_ID.containsMatchIn(text) }
                .map { (line, text) -> "${file.relativeTo(repositoryRoot).invariantSeparatorsPath}:$line: \"$text\"" }
        }

    /**
     * The string literals of Kotlin or Java [source], each with the line it starts on: plain and raw strings, a
     * template's expression read as part of its string. Comments and character literals are skipped.
     */
    @Suppress("CyclomaticComplexMethod")
    fun stringLiterals(source: String): List<Pair<Int, String>> {
        val literals = ArrayList<Pair<Int, String>>()
        var line = 1
        var i = 0
        fun skipTo(end: Int) {
            line += (i until end).count { source[it] == '\n' }
            i = end
        }
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> skipTo(source.indexOf('\n', i).takeIf { it >= 0 } ?: source.length)
                source.startsWith(
                    "/*",
                    i
                ) -> skipTo((source.indexOf("*/", i + 2).takeIf { it >= 0 } ?: source.length) + 2)
                source.startsWith("\"\"\"", i) -> {
                    var end = source.indexOf("\"\"\"", i + 3).takeIf { it >= 0 } ?: source.length
                    while (end + 3 < source.length && source[end + 3] == '"') end++
                    literals += line to source.substring(i + 3, end)
                    skipTo(minOf(end + 3, source.length))
                }
                source[i] == '"' -> {
                    var end = i + 1
                    while (end < source.length && source[end] != '"' && source[end] != '\n') {
                        end = when {
                            source[end] == '\\' -> end + 2
                            source.startsWith("\${", end) -> templateEnd(source, end + 2)
                            else -> end + 1
                        }
                    }
                    literals += line to source.substring(i + 1, minOf(end, source.length))
                    skipTo(minOf(end + 1, source.length))
                }
                source[i] == '\'' -> {
                    val end = source.indexOf('\'', if (source.getOrNull(i + 1) == '\\') i + 3 else i + 2)
                    i = if (end in i + 2..i + CHAR_LITERAL_MAX) end + 1 else i + 1
                }
                else -> skipTo(i + 1)
            }
        }
        return literals
    }

    private const val CHAR_LITERAL_MAX = 8

    /** The index after the `}` that closes the template expression starting at [start]. */
    private fun templateEnd(source: String, start: Int): Int {
        var depth = 1
        var end = start
        while (end < source.length && depth > 0) {
            when (source[end]) {
                '{' -> depth++
                '}' -> depth--
            }
            end++
        }
        return end
    }

    /**
     * The meters production code registers under a name it spells out (a string literal or template) instead of a
     * constant of the metric catalog, `me.ahoo.wow.observability.WowMetrics` (decision D289): `path:line: code`, minus
     * the files [allowed].
     */
    fun literalMeterNames(repositoryRoot: File, allowed: Map<String, String> = emptyMap()): List<String> =
        productionSources(repositoryRoot).flatMap { file ->
            val path = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            if (path in allowed) return@flatMap emptyList()
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                code.takeIf { METER_NAMED.find(it)?.groupValues?.get(1)?.trimStart()?.startsWith("\"") == true }
                    ?.let { "$path:${index + 1}: $it" }
            }
        }

    /** The paths of [allowed] that no longer name a meter by a literal, so their entries are stale. */
    fun staleLiteralMeterNames(repositoryRoot: File, allowed: Map<String, String>): List<String> =
        literalMeterNames(repositoryRoot).let { found ->
            allowed.keys.filter { path -> found.none { it.startsWith("$path:") } }
        }

    private val METER_NAMED = Regex(
        "(?:\\b(?:Gauge|Counter|Timer|FunctionCounter|FunctionTimer|DistributionSummary|LongTaskTimer|TimeGauge)" +
            "\\.builder|\\.(?:counter|timer|gauge|summary))\\(([^,)]*)",
    )
}
