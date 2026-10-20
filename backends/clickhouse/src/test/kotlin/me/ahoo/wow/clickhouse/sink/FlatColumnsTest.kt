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

package me.ahoo.wow.clickhouse.sink

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FlatColumnsTest {
    @Test
    fun `top-level scalars should become typed columns, decimals read exactly`() {
        FlatColumns.of(SinkFixtures.ORDER_STATE_SCHEMA, setOf("version")).assert().containsExactly(
            FlatColumn("status", "String", "JSONExtractString(state, 'status')"),
            FlatColumn("note", "Nullable(String)", "JSONExtract(state, 'note', 'Nullable(String)')"),
            FlatColumn("quantity", "Int64", "JSONExtract(state, 'quantity', 'Int64')"),
            FlatColumn("total", "Decimal(38, 2)", "toDecimal128OrZero(state_numbers['total'], 2)"),
            // A money's scale is its currency's default digits.
            FlatColumn("price", "Decimal(38, 0)", "toDecimal128OrZero(state_numbers['price'], 0)"),
            FlatColumn(
                "discount",
                "Nullable(Decimal(38, 18))",
                "toDecimal128OrNull(state_numbers['discount'], 18)",
            ),
            FlatColumn("rate", "Float64", "JSONExtract(state, 'rate', 'Float64')"),
            FlatColumn("paid", "Bool", "JSONExtract(state, 'paid', 'Bool')"),
            // A property named as a system column is prefixed.
            FlatColumn("state_version", "Int64", "JSONExtract(state, 'version', 'Int64')", "version"),
        )
    }

    @Test
    fun `a declared scale should win and a decimal without one should default`() {
        val schema = mapOf(
            "properties" to mapOf(
                "a" to mapOf("type" to "number", "x-wow-money" to mapOf("currency" to "CNY", "scale" to 4)),
                "b" to mapOf("type" to "number", "x-wow-decimal" to emptyMap<String, Any>()),
                "c" to mapOf("type" to listOf("integer", "null")),
                "d" to mapOf("type" to listOf("boolean", "null")),
                "it's" to mapOf("type" to "string"),
            ),
        )
        FlatColumns.of(schema, emptySet()).map { it.name to it.type }.assert().containsExactly(
            "a" to "Decimal(38, 4)",
            "b" to "Decimal(38, 18)",
            "c" to "Nullable(Int64)",
            "d" to "Nullable(Bool)",
            "it's" to "String",
        )
        FlatColumns.of(schema, emptySet()).last().expression.assert().isEqualTo("JSONExtractString(state, 'it\\'s')")
    }

    @Test
    fun `what is not a single scalar should be left out`() {
        val schema = mapOf(
            "properties" to mapOf(
                "object" to mapOf("type" to "object"),
                "union" to mapOf("type" to listOf("string", "integer")),
                "unknown" to mapOf("type" to 1),
                "reference" to mapOf("\$ref" to "#/\$defs/X"),
                "notASchema" to "string",
            ),
        )
        FlatColumns.of(schema, emptySet()).assert().isEmpty()
        FlatColumns.of(null, emptySet()).assert().isEmpty()
        FlatColumns.of(mapOf("type" to "object"), emptySet()).assert().isEmpty()
    }
}
