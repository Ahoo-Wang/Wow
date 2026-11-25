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

package me.ahoo.wow.query.tck

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.EXACT_COMPUTED_VALUE
import me.ahoo.wow.query.tck.SnapshotAggregationCases.Companion.EXACT_PLAIN_AGGREGATE
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The query language reference's table of how each backend computes a number (`reference/query-language.md`, Search
 * and expressions, English and Chinese), against what each backend's contract subclass lists in its
 * `semanticDivergences`: a backend in a row's "in doubles" cell lists the row's case, one in its "in the stored
 * decimals" cell does not, and the contract holds each to that answer (#6969). A backend is every subclass of
 * [QueryBackendSpec] and [DatasetQueryBackendSpec] in the repository but the third-party store's.
 */
class BackendNumbersReferenceTest {
    private val root = File(requireNotNull(System.getProperty(ROOT)) { "System property $ROOT is not set." })

    @Test
    fun `every backend should be one the table names, by its contract subclasses`() {
        val subclasses = listOf("src/test", "src/integrationTest").flatMap { set ->
            MODULES.map { File(root, "$it/$set") }
        }.filter(File::isDirectory).flatMap { it.walk().filter { file -> file.extension == "kt" } }
            .filter { SPEC.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
        subclasses.assert().containsExactlyInAnyOrderElementsOf(BACKENDS.values.flatten())
    }

    @Test
    fun `each row's backends in doubles should be the ones that list its case, in English and in Chinese`() {
        val listed = BACKENDS.mapValues { (backend, files) ->
            val lists = files.map { divergencesOf(File(root, it).readText()) }
            lists.distinct().assert().describedAs("the cases $backend's subclasses list").hasSize(1)
            lists.first()
        }
        PAGES.forEach { (page, names) ->
            val rows = File(root, page).readLines().filter { it.startsWith("| ") }
                .map { line -> line.trim('|').split(" | ").map(String::trim) }
            ROWS.forEach { (marker, case) ->
                val row = rows.singleOrNull { it.first().startsWith(marker.of(page)) }
                row.assert().describedAs("$page: the row of $case").isNotNull()
                val (exact, doubles) = row!!.drop(1).map { cell -> backendsOf(cell, names) }
                val inDoubles = listed.filterValues { case in it }.keys
                doubles.assert().describedAs("$page, $case: in doubles").isEqualTo(inDoubles)
                exact.assert().describedAs("$page, $case: in the stored decimals").isEqualTo(BACKENDS.keys - inDoubles)
            }
        }
    }

    /** The two rows, by the start of their first cell on each page. */
    private class Marker(val en: String, val zh: String) {
        fun of(page: String): String = if ("/zh/" in page) zh else en
    }

    private companion object {
        const val ROOT = "wow-test.repository.root"

        /** The modules whose tests hold a query backend's contract subclass. */
        val MODULES = listOf(
            "query/query",
            "backends/postgres",
            "backends/mongo",
            "backends/elasticsearch",
            "backends/clickhouse",
        )

        /** A contract subclass of a query backend: its class extends one of the two specs. */
        val SPEC = Regex("""class \w+\s*:\s*(QueryBackendSpec|DatasetQueryBackendSpec)\(\)""")

        /** Each backend the table names, by its contract subclasses. */
        val BACKENDS: Map<String, List<String>> = mapOf(
            "memory" to listOf(
                "query/query/src/test/kotlin/me/ahoo/wow/query/memory/InMemoryQueryBackendTest.kt",
                "query/query/src/test/kotlin/me/ahoo/wow/query/memory/InMemoryDatasetQueryBackendTest.kt",
            ),
            "PostgreSQL" to listOf(
                "backends/postgres/src/integrationTest/kotlin/me/ahoo/wow/postgres/query/PostgresSnapshotQueryBackendTest.kt",
                "backends/postgres/src/integrationTest/kotlin/me/ahoo/wow/postgres/query/PostgresCLocaleQueryBackendTest.kt",
            ),
            "MongoDB" to listOf(
                "backends/mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/MongoSnapshotQueryBackendTest.kt",
            ),
            "Elasticsearch" to listOf(
                "backends/elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/" +
                    "ElasticsearchSnapshotQueryBackendTest.kt",
            ),
            "ClickHouse" to listOf(
                "backends/clickhouse/src/integrationTest/kotlin/me/ahoo/wow/clickhouse/query/" +
                    "ClickHouseDatasetQueryBackendTest.kt",
            ),
        )

        /** The pages, and the name each gives a backend that is not its own. */
        val PAGES: Map<String, Map<String, String>> = mapOf(
            "documentation/docs/en/reference/query-language.md" to emptyMap(),
            "documentation/docs/zh/reference/query-language.md" to mapOf("内存" to "memory"),
        )

        val ROWS: Map<Marker, String> = mapOf(
            Marker("A plain field's", "普通字段的") to EXACT_PLAIN_AGGREGATE,
            Marker("A computed value", "计算值") to EXACT_COMPUTED_VALUE,
        )

        /** The two cases, by the constant a subclass names each with. */
        val CASES = mapOf(
            "EXACT_PLAIN_AGGREGATE" to EXACT_PLAIN_AGGREGATE,
            "EXACT_COMPUTED_VALUE" to EXACT_COMPUTED_VALUE,
        )

        /** Which of the two cases [source]'s `semanticDivergences` lists, by its constant or its id: its `setOf(…)`. */
        fun divergencesOf(source: String): Set<String> {
            val declared = source.indexOf("override val semanticDivergences")
            if (declared < 0) return emptySet()
            val open = source.indexOf("setOf(", declared) + "setOf".length
            var depth = 0
            val close = (open until source.length).first { at ->
                when (source[at]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                depth == 0
            }
            val listed = source.substring(open, close)
            return CASES.filter { (constant, id) -> constant in listed || "\"$id\"" in listed }.values.toSet()
        }

        /** The backends a cell names, each by the name [BACKENDS] knows it by; a parenthesis qualifies one. */
        fun backendsOf(cell: String, names: Map<String, String>): Set<String> =
            cell.split(", ", "、").map { it.substringBefore(" (").substringBefore("（").trim() }
                .filter(String::isNotEmpty).map { names[it] ?: it }.toSet()
    }
}
