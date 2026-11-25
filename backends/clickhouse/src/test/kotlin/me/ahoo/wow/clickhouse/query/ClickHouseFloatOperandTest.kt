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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.SCHEMA
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.TABLE
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A number operand of a `Float32` or `Float64` column (a dataset's, which the schema reads as a decimal field) is bound
 * as the double the column compares it as, never its every digit written out: `1e-1000000` would have been a string of a
 * million characters, `1e-500000000` half a gigabyte (#7070). One past a double's range, or a nonzero one that rounds
 * to zero, is refused, since the column holds neither.
 */
class ClickHouseFloatOperandTest {
    private val parameters = SqlParameters()
    private val compiler = ClickHouseFilterCompiler(
        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), SCHEMA),
        TABLE,
        parameters,
    )
    private val float = TypedColumn(ClickHouseColumn("c", "Float64"))

    @Test
    fun `a number of a float column is bound as its double`() {
        compiler.bind(float, BigDecimal("2.50")).assert().isEqualTo("{p0:Float64}")
        compiler.bind(float, 5L).assert().isEqualTo("{p1:Float64}")
        compiler.bind(float, BigDecimal.ZERO.setScale(3)).assert().isEqualTo("{p2:Float64}")
        compiler.bind(float, BigInteger.TWO).assert().isEqualTo("{p3:Float64}")
        // An operand that is no number is bound as its text, as before.
        compiler.bind(float, "2.5").assert().isEqualTo("{p4:Float64}")
        parameters.statement("").parameters.values.toList().assert().containsExactly("2.5", "5.0", "0.0", "2.0", "2.5")
    }

    @Test
    fun `a number no double holds is refused, not written out`() {
        assertThrows<QuerySchemaValidationException> { compiler.bind(float, BigDecimal("1e-1000000")) }
        assertThrows<QuerySchemaValidationException> { compiler.bind(float, BigDecimal("1e400")) }
        // A subnormal double, which ClickHouse reads as zero (#7009).
        assertThrows<QuerySchemaValidationException> { compiler.bind(float, BigDecimal("1e-310")) }
    }
}
