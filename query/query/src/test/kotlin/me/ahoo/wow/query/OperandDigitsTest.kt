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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.resolve.MAX_OPERAND_INTEGER_DIGITS
import me.ahoo.wow.query.resolve.MAX_OPERAND_SCALE
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A numeric operand whose digits pass what any store's exact decimal holds is refused at admission, before any store or
 * driver is asked (#7070): `1e-500000000` is 12 characters of a query body, and a driver pays for every digit of it
 * when it binds it.
 */
class OperandDigitsTest {
    private val amount = QueryField("state.amount")
    private val schema = gatewaySchema(
        fields = mapOf(amount to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.DECIMAL))),
    )

    private fun admit(filter: FilterExpression) = QueryAdmission.Trusted.list(ListQuery(filter, limit = 10), schema)

    private fun refused(filter: FilterExpression) {
        val refusal = assertThrows<QuerySchemaValidationException> { admit(filter) }
        refusal.violation.assert().isInstanceOf(QueryViolation.StorageUnsupported::class.java)
        refusal.violation?.field.assert().isEqualTo(amount)
    }

    /** The number a query body writes as [text], as the query routes read it. */
    private fun read(text: String) = QueryJson.mapper.readTree(text)

    @Test
    fun `a number whose exponent passes every store's decimal is refused, whichever way it passes`() {
        refused(EqualFilter(amount, read("1e-500000000")))
        refused(EqualFilter(amount, read("1e500000000")))
        refused(InFilter(amount, listOf(read("1"), read("1e-500000000"))))
        refused(EqualFilter(amount, BigDecimal.ONE.movePointLeft(MAX_OPERAND_SCALE + 1)))
        refused(EqualFilter(amount, BigInteger.TEN.pow(MAX_OPERAND_INTEGER_DIGITS)))
    }

    @Test
    fun `a number a store's decimal holds is admitted, at the bounds too`() {
        admit(EqualFilter(amount, read("12.50")))
        admit(EqualFilter(amount, read("1e-20")))
        admit(EqualFilter(amount, 7))
        admit(EqualFilter(amount, BigDecimal.ONE.movePointLeft(MAX_OPERAND_SCALE)))
        admit(EqualFilter(amount, BigInteger.TEN.pow(MAX_OPERAND_INTEGER_DIGITS - 1)))
        admit(EqualFilter(amount, read("9007199254740993")))
    }
}
