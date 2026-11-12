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
import me.ahoo.wow.query.error.QueryExecutionException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The typed value of a path below a `jsonb` column names the column once (PG-9, D421), and reads every value as the
 * form before it did, kept here as the reference: the same `NULL` or the same text, for a number, a text and a
 * boolean, on the cases where a JSON path could read differently (arrays at the leaf and on the way, a numeric
 * segment over an array and over an object's key, nested values, other types).
 */
class PostgresTypedValueDifferentialTest {
    @Test
    fun `the typed value reads every case as the form that named the column twice`() {
        val rows = CASES.joinToString(", ") { (label, json, _) -> "('$label', '$json'::jsonb)" }
        val columns = CASES.flatMap { (label, _, path) ->
            TYPES.map { type ->
                val now = PostgresSnapshotQueryExpressions.value("s", path, type)
                "CASE WHEN label = '$label' THEN ${reference("s", path, type)}::text END AS \"r_${label}_$type\", " +
                    "CASE WHEN label = '$label' THEN $now::text END AS \"n_${label}_$type\""
            }
        }
        val differences = mutableListOf<String>()
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT label, ${columns.joinToString(", ")} FROM (VALUES $rows) v(label, s)")
                    .use { result ->
                        while (result.next()) {
                            val label = result.getString("label")
                            TYPES.forEach { type ->
                                val reference = result.getString("r_${label}_$type")
                                val now = result.getString("n_${label}_$type")
                                if (reference != now) differences += "$label as $type: $reference against $now"
                            }
                        }
                    }
            }
        }
        differences.assert().isEmpty()
    }

    @Test
    fun `a jsonpath filter's question mark is no placeholder of a prepared statement`() {
        val price = PostgresSnapshotQueryExpressions.value("s", listOf("p"), PostgresQueryType.NUMERIC)
        PostgresFixture.dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM (VALUES ('{\"p\": 3}'::jsonb), ('{\"p\": [3]}'), ('{\"p\": \"3\"}')) v(s) WHERE $price = ?",
            ).use { statement ->
                statement.setBigDecimal(1, 3.toBigDecimal())
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1).assert().isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `a key that is not a path segment never reaches the SQL text`() {
        listOf("", "a'b", "a\\b", "a\$b", "a.b", "a\"b", "名", "a b").forEach { key ->
            TYPES.forEach { type ->
                assertThrows<QueryExecutionException> { PostgresSnapshotQueryExpressions.value("s", listOf(key), type) }
            }
        }
    }

    private companion object {
        val TYPES = listOf(PostgresQueryType.NUMERIC, PostgresQueryType.TEXT, PostgresQueryType.BOOLEAN)

        /** Label, JSON, path: each read as a number, a text and a boolean. */
        val CASES = listOf(
            Triple("decimal", """{"p": 1.10}""", listOf("p")),
            Triple("negative", """{"p": -0.5}""", listOf("p")),
            Triple("large", """{"p": 1e30}""", listOf("p")),
            Triple("zero", """{"p": 0}""", listOf("p")),
            Triple("numeric_text", """{"p": "100"}""", listOf("p")),
            Triple("text", """{"p": "Ann"}""", listOf("p")),
            Triple("empty_text", """{"p": ""}""", listOf("p")),
            Triple("escaped_quote", """{"p": "a\"b"}""", listOf("p")),
            Triple("non_ascii", """{"p": "名字"}""", listOf("p")),
            Triple("true_text", """{"p": "true"}""", listOf("p")),
            Triple("boolean_true", """{"p": true}""", listOf("p")),
            Triple("boolean_false", """{"p": false}""", listOf("p")),
            Triple("json_null", """{"p": null}""", listOf("p")),
            Triple("missing", """{}""", listOf("p")),
            Triple("object", """{"p": {"y": 1}}""", listOf("p")),
            Triple("number_array_leaf", """{"p": [1, 2]}""", listOf("p")),
            Triple("text_array_leaf", """{"p": ["a"]}""", listOf("p")),
            Triple("boolean_array_leaf", """{"p": [true]}""", listOf("p")),
            Triple("object_array_on_the_way", """{"a": [{"b": 1}, {"b": "x"}]}""", listOf("a", "b")),
            Triple("numeric_segment_over_array", """{"a": [5, "x", true]}""", listOf("a", "0")),
            Triple("numeric_segment_over_object", """{"a": {"0": 7}}""", listOf("a", "0")),
            Triple("nested_text", """{"a": {"b": "x"}}""", listOf("a", "b")),
            Triple("nested_below_scalar", """{"a": 1}""", listOf("a", "b")),
        )

        /** The typed value as Wow wrote it before PG-9: a `CASE` on `jsonb_typeof`, naming the column twice. */
        fun reference(base: String, path: List<String>, type: PostgresQueryType): String {
            val literal = path.joinToString(",", "{", "}")
            val node = "($base #> '$literal')"
            val text = "$base #>> '$literal'"
            return when (type) {
                PostgresQueryType.TEXT -> "((CASE WHEN jsonb_typeof($node) = 'string' THEN $text END) COLLATE \"C\")"
                PostgresQueryType.BOOLEAN -> "(CASE WHEN jsonb_typeof($node) = 'boolean' THEN ($text)::boolean END)"
                else -> "(CASE WHEN jsonb_typeof($node) = 'number' THEN ($text)::numeric END)"
            }
        }
    }
}
