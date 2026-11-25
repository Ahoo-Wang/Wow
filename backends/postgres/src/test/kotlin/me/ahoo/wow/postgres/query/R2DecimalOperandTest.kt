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

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.json.QueryJson
import org.junit.jupiter.api.Test

/**
 * Round two of the deep review, security: a decimal operand of 12 characters, `1e-500000000`, is a `BigDecimal` of
 * scale 500,000,000 once read (Wow's mapper reads floats as `BigDecimal`), within Jackson's number length limit. The
 * query is admitted and the operand bound as it is (`SqlParameter.Numeric`), and pgjdbc 42.7.14 sends a `NUMERIC` in
 * binary by default: `ByteConverter.numeric` computes `BigInteger.TEN.pow(scale)`, about 1.66e9 bits, on the JDBC
 * thread that holds a store query permit and a pooled connection, where no query timeout interrupts it. Nothing bounds
 * an operand's scale before that cost is paid; such a value is refused as a query the caller can mend.
 */
class R2DecimalOperandTest {
    private val compiler = PostgresSnapshotSqlCompiler(QueryTestSchemas.TABLE)

    @Test
    fun `a decimal operand whose scale no column can hold is refused before it is bound`() {
        val query = QueryJson.mapper.readValue(
            """{"filter":{"op":"eq","field":"state.amount","value":1e-500000000}}""",
            ListQuery::class.java,
        )
        assertThrownBy<QueryRequestException> {
            compiler.list(QueryAdmission.Trusted.list(query, QueryTestSchemas.SCHEMA))
        }
    }
}
