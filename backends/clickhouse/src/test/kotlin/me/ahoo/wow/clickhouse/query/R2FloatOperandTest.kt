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

package me.ahoo.wow.clickhouse.query

import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.SCHEMA
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.TABLE
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.error.QueryRequestException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Round two of the deep review, security: a decimal operand of a `Float32` or `Float64` column (a dataset's, which the
 * schema reads as a decimal field and admits any number for) is bound as `toPlainString()`, its every digit written
 * out. `1e-1000000` (10 characters in a query body) is bound as a string of a million characters; `1e-500000000` would
 * be half a gigabyte, and a scale near `Int.MAX_VALUE` an `OutOfMemoryError`, which the query routes do not catch.
 * Nothing bounds an operand's scale before it is written out; such a value is refused as a query the caller can mend.
 */
class R2FloatOperandTest {
    private val compiler = ClickHouseFilterCompiler(
        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), SCHEMA),
        TABLE,
        SqlParameters(),
    )

    @Test
    fun `a decimal operand of a float column whose digits no double holds is refused, not written out`() {
        assertThrows<QueryRequestException> {
            compiler.bind(TypedColumn(ClickHouseColumn("c", "Float64")), BigDecimal("1e-1000000"))
        }
    }
}
