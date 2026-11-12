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

package me.ahoo.wow.postgres.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.postgres.query.PostgresFilterCompiler.Companion.arrayElements
import me.ahoo.wow.postgres.query.PostgresFilterCompiler.Companion.objectElements
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * The elements of a multi-valued field read its node once, in a subquery the planner keeps
 * ([PostgresFilterCompiler.arrayElements], [PostgresFilterCompiler.objectElements]). They read every case as the forms
 * before them did, kept here as the reference, which named the node in each branch of their `CASE`: the same elements,
 * and the same answers of a range, a literal match, an element match and an aggregation over them. A query over states
 * stored out of line reads well under the reference's buffers, which fails if the planner flattens the subquery.
 */
class PostgresElementsDifferentialTest {
    @Test
    fun `the elements of every case are the reference's`() {
        val columns = listOf(
            "(SELECT jsonb_agg(r.v) FROM jsonb_array_elements(${referenceArray(NODE)}) AS r(v))",
            "(SELECT jsonb_agg(e1.v) FROM ${arrayElements(NODE, "e1")})",
            "(SELECT jsonb_agg(r.v) FROM jsonb_array_elements(${referenceObjects(NODE)}) AS r(v))",
            "(SELECT jsonb_agg(e1.v) FROM ${objectElements(NODE, "e1")})",
        )
        differences(columns).assert().isEmpty()
    }

    @Test
    fun `a range, a literal match, an element match and an aggregation answer every case as the reference does`() {
        val number = PostgresSnapshotQueryExpressions.value("e1.v", emptyList(), PostgresQueryType.NUMERIC)
        val text = PostgresSnapshotQueryExpressions.value("e1.v", emptyList(), PostgresQueryType.TEXT)
        val sku = PostgresSnapshotQueryExpressions.value("e1.v", listOf("sku"), PostgresQueryType.TEXT)
        val range = "$number > 2"
        val like = "$text LIKE '%a%'"
        val match = "jsonb_typeof(e1.v) = 'object' AND $sku = 'a'"
        val group = { elements: String ->
            "(SELECT jsonb_agg(jsonb_build_array(g, c) ORDER BY g NULLS FIRST) FROM " +
                "(SELECT $sku AS g, count(*) AS c FROM $elements WHERE jsonb_typeof(e1.v) = 'object' GROUP BY 1) AS a)"
        }
        val columns = listOf(
            "EXISTS (SELECT 1 FROM jsonb_array_elements(${referenceArray(NODE)}) AS e1(v) WHERE $range)",
            "EXISTS (SELECT 1 FROM ${arrayElements(NODE, "e1")} WHERE $range)",
            "EXISTS (SELECT 1 FROM jsonb_array_elements(${referenceArray(NODE)}) AS e1(v) WHERE $like)",
            "EXISTS (SELECT 1 FROM ${arrayElements(NODE, "e1")} WHERE $like)",
            "EXISTS (SELECT 1 FROM jsonb_array_elements(${referenceObjects(NODE)}) AS e1(v) WHERE $match)",
            "EXISTS (SELECT 1 FROM ${objectElements(NODE, "e1")} WHERE $match)",
            group("jsonb_array_elements(${referenceObjects(NODE)}) AS e1(v)"),
            group(objectElements(NODE, "e1")),
        )
        differences(columns).assert().isEmpty()
    }

    @Test
    fun `a query over states stored out of line reads its node once`() {
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TEMP TABLE out_of_line (id int PRIMARY KEY, state jsonb NOT NULL)")
                // About 4 KB of hex a state, which compresses too little to stay in line: TOAST stores it.
                statement.execute(
                    "INSERT INTO out_of_line SELECT g, jsonb_build_object(" +
                        "'scores', jsonb_build_array(g % 100, (g * 7) % 100), " +
                        "'note', (SELECT string_agg(md5((g * 31 + i)::text), '') FROM generate_series(1, 120) AS i)) " +
                        "FROM generate_series(1, $OUT_OF_LINE_ROWS) AS g",
                )
            }
            try {
                val node = "(state #> '{scores}')"
                val number = PostgresSnapshotQueryExpressions.value("e1.v", emptyList(), PostgresQueryType.NUMERIC)
                val query = { elements: String ->
                    "SELECT count(*) FROM out_of_line WHERE EXISTS (SELECT 1 FROM $elements WHERE $number > 98)"
                }
                val reference = connection.buffers(query("jsonb_array_elements(${referenceArray(node)}) AS e1(v)"))
                val now = connection.buffers(query(arrayElements(node, "e1")))
                // A detoast reads a state's chunks, at least two pages a row: the reference reads them twice a row.
                reference.assert().isGreaterThan(OUT_OF_LINE_ROWS * 2 * 2)
                (now * 3).assert().isLessThan(reference * 2)
            } finally {
                connection.createStatement().use { it.execute("DROP TABLE out_of_line") }
            }
        }
    }

    private companion object {
        const val OUT_OF_LINE_ROWS = 300
        const val NODE = "(s #> '{p}')"

        /** Label and state: the node at `p`, as a missing key, a JSON null, scalars, objects and arrays of each. */
        val CASES = listOf(
            "missing" to """{}""",
            "json_null" to """{"p": null}""",
            "number" to """{"p": 5}""",
            "text" to """{"p": "a"}""",
            "boolean" to """{"p": true}""",
            "object" to """{"p": {"sku": "a", "q": 5}}""",
            "empty_object" to """{"p": {}}""",
            "empty_array" to """{"p": []}""",
            "numbers" to """{"p": [1, 2, 3]}""",
            "with_nulls" to """{"p": [1, null, "xa"]}""",
            "objects" to """{"p": [{"sku": "a", "q": 1}, {"sku": "b", "q": 7}]}""",
            "mixed" to """{"p": [{"sku": "a"}, 5, "sa", null, [{"sku": "c"}], []]}""",
            "array_of_arrays" to """{"p": [[{"sku": "a"}], [3]]}""",
            "nested_deep" to """{"p": [[[{"sku": "a"}]]]}""",
            "object_with_array" to """{"p": {"sku": [{"sku": "b"}]}}""",
            "objects_with_arrays" to """{"p": [{"sku": ["a", "b"]}, {"sku": "a"}]}""",
        )

        /** The array Wow expanded before the node was read once: itself, or an array of it. */
        fun referenceArray(node: String): String =
            "(CASE WHEN jsonb_typeof($node) = 'array' THEN $node ELSE jsonb_build_array($node) END)"

        /** The array of objects Wow expanded before the node was read once: itself, an object in one, or none. */
        fun referenceObjects(node: String): String =
            "(CASE WHEN jsonb_typeof($node) = 'array' THEN $node " +
                "WHEN jsonb_typeof($node) = 'object' THEN jsonb_build_array($node) ELSE '[]'::jsonb END)"

        /**
         * The cases where a column of [columns], read as text over each case's state `s`, differs from the one after
         * it: the reference, then the form read once.
         */
        fun differences(columns: List<String>): List<String> {
            val rows = CASES.joinToString(", ") { (label, json) -> "('$label', '$json'::jsonb)" }
            val select = columns.mapIndexed { index, column -> "($column)::text AS c$index" }.joinToString(", ")
            val differences = mutableListOf<String>()
            PostgresFixture.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT label, $select FROM (VALUES $rows) AS v(label, s)").use { result ->
                        while (result.next()) {
                            val label = result.getString("label")
                            for (index in columns.indices step 2) {
                                val reference = result.getString("c$index")
                                val now = result.getString("c${index + 1}")
                                if (reference != now) differences += "$label, column $index: $reference against $now"
                            }
                        }
                    }
                }
            }
            return differences
        }

        /** The buffers, hit or read, that [query] takes, from `EXPLAIN (ANALYZE, BUFFERS)` of its top node. */
        fun Connection.buffers(query: String): Int = createStatement().use { statement ->
            statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) $query").use { result ->
                // The first Buffers line is the top node's, which counts its children's and its subplans'.
                val lines = buildList { while (result.next()) add(result.getString(1)) }
                val buffers = lines.first { it.trimStart().startsWith("Buffers:") }
                PAGES.findAll(buffers).sumOf { it.groupValues[1].toInt() }
            }
        }

        /** A page hit or read, shared or local (a temporary table's): dirtied and written pages are not reads. */
        val PAGES = Regex("""\b(?:hit|read)=(\d+)""")
    }
}
