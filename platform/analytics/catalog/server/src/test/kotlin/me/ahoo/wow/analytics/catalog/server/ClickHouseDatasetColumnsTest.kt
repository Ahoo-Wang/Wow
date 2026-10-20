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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclarationBuilder
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ClickHouseDatasetColumnsTest {
    private fun declared(type: String) =
        QueryFieldDeclarationBuilder().apply { ClickHouseDatasetColumns.declare(this, type) }.build()

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "String|STRING|false",
            "LowCardinality(Nullable(String))|STRING|true",
            "FixedString(4)|STRING|false",
            "UUID|STRING|false",
            "Enum8('a' = 1)|STRING|false",
            "UInt64|INTEGER|false",
            "Nullable(Int32)|INTEGER|true",
            "Float64|DECIMAL|false",
            "Decimal(18, 2)|DECIMAL|false",
            "Bool|BOOLEAN|false",
        ],
    )
    fun `a scalar column should be declared as the backend reads it`(
        type: String,
        value: QueryValueType,
        nullable: Boolean
    ) {
        ClickHouseDatasetColumns.servable(type).assert().isTrue()
        val field = declared(type)
        field.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.SCALAR))
        field.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(value)))
        field.nullable.assert().isEqualTo(DeclarationValue.Set(nullable))
    }

    @Test
    fun `dates should be text of a date, arrays of text arrays, and other types not served`() {
        declared("DateTime64(3, 'UTC')").semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        declared("Date32").semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        val array = declared("Array(LowCardinality(String))")
        array.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        ClickHouseDatasetColumns.servable("Array(FixedString(2))").assert().isTrue()
        listOf("Map(String, String)", "Array(Int64)", "Tuple(a String)", "JSON").forEach {
            ClickHouseDatasetColumns.servable(it).assert().describedAs(it).isFalse()
            declared(it).kind.assert().isEqualTo(DeclarationValue.Unset)
        }
    }
}
