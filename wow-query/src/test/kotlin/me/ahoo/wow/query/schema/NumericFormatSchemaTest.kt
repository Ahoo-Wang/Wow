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
import me.ahoo.wow.api.query.annotation.QueryDecimal
import me.ahoo.wow.api.query.annotation.QueryMoney
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.schema.NumericFormat
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.QueryBudget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

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
        schemaOf("amount" to decimal(NumericFormat.Decimal(2)))
        schemaOf("amount" to decimal(NumericFormat.Money(currency = "CNY", scale = 2)))
    }

    @Test
    fun `a wrong numeric format is a schema conflict`() {
        listOf(
            // Not numeric.
            arrayOf(
                "amount" to scalarFixture().let {
                    QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), semanticType = money)
                },
                "currency" to scalarFixture()
            ),
            // No currency field, or one in another scope, or not a single string.
            arrayOf("amount" to decimal(money)),
            arrayOf(
                "amount" to decimal(money),
                "lines" to arrayFixture(QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf("currency" to scalarFixture()))),
            ),
            arrayOf("amount" to decimal(money), "currency" to arrayFixture(scalarFixture())),
            arrayOf("amount" to decimal(money), "currency" to scalarFixture(QueryValueType.INTEGER)),
        ).forEach { properties ->
            assertThrows<QuerySchemaConflictException> { schemaOf(*properties) }
        }
    }

    @Test
    fun `annotations declare the format and a field keeps one semantic type`() {
        val declaration = QueryTypeFact(
            QueryValueKind.OBJECT,
            setOf(QueryValueType.OBJECT),
            nullable = false,
            properties = mapOf(
                "price" to decimalFact(QueryDecimal(scale = 4)),
                "total" to decimalFact(QueryMoney(currency = "JPY")),
                "amount" to decimalFact(QueryMoney(currencyField = "currency", scale = 2)),
            ),
        ).toDeclaration(QueryField("state"))
        val properties = declaration.properties.valueOr(emptyMap())
        properties.getValue("price").semanticType.assert().isEqualTo(DeclarationValue.Set(NumericFormat.Decimal(4)))
        properties.getValue("total").semanticType.assert()
            .isEqualTo(DeclarationValue.Set(NumericFormat.Money(currency = "JPY", scale = 0)))
        properties.getValue("amount").semanticType.assert().isEqualTo(DeclarationValue.Set(money))

        listOf(
            decimalFact(QueryDecimal(scale = 2), QueryMoney(currency = "CNY")),
            decimalFact(QueryDecimal(scale = 2), QueryTemporal()),
            decimalFact(QueryMoney(currencyField = "currency")),
            decimalFact(QueryMoney(currency = "CNY", currencyField = "currency", scale = 2)),
            QueryTypeFact(
                QueryValueKind.OBJECT,
                setOf(QueryValueType.OBJECT),
                member = QueryMemberFact("member", Any::class.java, listOf(QueryDecimal(scale = 2))),
            ),
        ).forEach { fact ->
            assertThrows<QuerySchemaConflictException> {
                QueryTypeFact(
                    QueryValueKind.OBJECT,
                    setOf(QueryValueType.OBJECT),
                    nullable = false,
                    properties = mapOf("value" to fact),
                ).toDeclaration(QueryField("state"))
            }
        }
    }

    @Test
    fun `the descriptor publishes the format as the field's semantic`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "aggregateId" to scalarFixture(),
                "state" to objectFixture("amount" to decimal(money), "currency" to scalarFixture()),
            ),
        )
        val fields = schema.describe(QueryBudget.HTTP_DEFAULT, 100).fields.associateBy { it.path }
        fields.getValue("state.amount").semantic.assert().isEqualTo(money)
    }

    private fun decimalFact(vararg annotations: Annotation) = QueryTypeFact(
        QueryValueKind.SCALAR,
        setOf(QueryValueType.DECIMAL),
        nullable = false,
        member = QueryMemberFact("member", BigDecimal::class.java, annotations.toList()),
    )
}
