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
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A decimal operand of 12 characters, `1e-500000000`, is a `BigDecimal` of scale 500,000,000 once a query body is
 * read. Bound as it is, pgjdbc would send it as a binary `NUMERIC` and compute `BigInteger.TEN.pow(scale)` on the JDBC
 * thread, a store permit and a connection held, which no query timeout interrupts (#7070). Admission refuses it first.
 */
class PostgresOperandDigitsTest {
    private val compiler = PostgresSnapshotSqlCompiler(QueryTestSchemas.TABLE)

    private fun compiled(body: String) = compiler.list(
        QueryAdmission.Trusted.list(QueryJson.mapper.readValue(body, ListQuery::class.java), QueryTestSchemas.SCHEMA),
    )

    @Test
    fun `a decimal operand whose scale no column can hold is refused before it is bound`() {
        val refusal = assertThrows<QuerySchemaValidationException> {
            compiled("""{"filter":{"op":"eq","field":"state.amount","value":1e-500000000}}""")
        }
        refusal.violation.assert().isInstanceOf(QueryViolation.StorageUnsupported::class.java)
    }

    @Test
    fun `a decimal operand a column holds is bound as the number it is`() {
        compiled("""{"filter":{"op":"eq","field":"state.amount","value":1e-20},"limit":10}""").parameters.assert()
            .contains(SqlParameter.Numeric(java.math.BigDecimal("1e-20")))
    }
}
