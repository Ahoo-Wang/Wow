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
import org.junit.jupiter.api.Test

/**
 * Round two of the deep review, security (suspicion 5 of #7021): a field below a `jsonb` column is read with `#>` and
 * a `text[]` literal of its path segments written unquoted, `'{state,null}'`. In an array literal an unquoted `NULL`, in
 * any case, is SQL NULL, so a property or a map key named `null` (`Null`, `NULL`), which the segment pattern admits,
 * makes the path NULL and the field reads as missing: a filter on it matches no row, a sort and a group see no value.
 * Correctness, not scope: the tenant and the owner are columns.
 */
class R2NullSegmentTest {
    private fun read(json: String, path: List<String>, type: PostgresQueryType): String? =
        PostgresFixture.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val value = PostgresSnapshotQueryExpressions.value("s", path, type)
                statement.executeQuery("SELECT $value::text FROM (VALUES ('$json'::jsonb)) v(s)").use { result ->
                    result.next()
                    result.getString(1)
                }
            }
        }

    @Test
    fun `a property or a map key named null in any case is read as the value it holds`() {
        read("""{"null": 7}""", listOf("null"), PostgresQueryType.NUMERIC).assert().isEqualTo("7")
        read("""{"a": {"NULL": "x"}}""", listOf("a", "NULL"), PostgresQueryType.TEXT).assert().isEqualTo("x")
        read("""{"Null": {"b": true}}""", listOf("Null", "b"), PostgresQueryType.BOOLEAN).assert().isEqualTo("true")
    }
}
