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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NumericFormatSchemaTest {
    private val money = NumericFormat.Money(currencyField = "currency", scale = 2)

    private fun decimal(format: NumericFormat?) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.DECIMAL), semanticType = format)

    private fun schemaOf(vararg properties: Pair<String, QueryValueSchema>) =
        LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap()))

    @Test
    fun `money reads its currency from a single-valued string sibling, in the record or an element`() {
        schemaOf("amount" to decimal(money), "currency" to scalarFixture())
        schemaOf(
            "lines" to arrayFixture(
                QueryValueSchema(
                    QueryValueKind.OBJECT,
                    properties = mapOf("price" to decimal(money), "currency" to scalarFixture()),
                ),
            ),
        )
        // The items of an array share the siblings of the property that holds the array.
        schemaOf("amounts" to arrayFixture(decimal(money)), "currency" to scalarFixture())
        schemaOf("amount" to decimal(NumericFormat.Decimal(2)))
        schemaOf("amount" to decimal(NumericFormat.Money(currency = "CNY", scale = 2)))
        schemaOf(
            "amount" to QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL),
                semanticType = NumericFormat.Decimal(0),
            ),
        )
    }

    @Test
    fun `a wrong numeric format is a schema conflict`() {
        listOf(
            // Not numeric.
            arrayOf(
                "amount" to QueryValueSchema(
                    QueryValueKind.SCALAR,
                    valueTypes = setOf(QueryValueType.STRING),
                    semanticType = money
                ),
                "currency" to scalarFixture()
            ),
            arrayOf(
                "amount" to QueryValueSchema(
                    QueryValueKind.SCALAR,
                    valueTypes = setOf(QueryValueType.STRING, QueryValueType.DECIMAL),
                    semanticType = NumericFormat.Decimal(2),
                ),
            ),
            // No currency field, or one in another scope, or not a single string.
            arrayOf("amount" to decimal(money)),
            arrayOf(
                "amount" to decimal(money),
                "lines" to arrayFixture(
                    QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf("currency" to scalarFixture()))
                ),
            ),
            arrayOf("amount" to decimal(money), "currency" to arrayFixture(scalarFixture())),
            arrayOf("amount" to decimal(money), "currency" to scalarFixture(QueryValueType.INTEGER)),
        ).forEach { properties ->
            assertThrows<QuerySchemaConflictException> { schemaOf(*properties) }
        }
    }

    @Test
    fun `the declaration builder declares the format and a field keeps one semantic type`() {
        val declaration = querySchema {
            field("state.price") { semantic(NumericFormat.Decimal(4)) }
            field("state.total") { semantic(NumericFormat.Money.of(currency = "JPY")) }
        }
        declaration.fields.getValue(QueryField("state.price")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(NumericFormat.Decimal(4)))
        declaration.fields.getValue(QueryField("state.total")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(NumericFormat.Money(currency = "JPY", scale = 0)))
        assertThrows<QuerySchemaConflictException> {
            querySchema {
                field("state.price") {
                    semantic(NumericFormat.Decimal(2))
                    semantic(NumericFormat.Money.of(currency = "CNY"))
                }
            }
        }
    }
}
