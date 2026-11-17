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
import java.math.BigInteger

/** How an operand is bound for its column (ClickHouseFilterCompiler.bind), and which columns compare as their text. */
class ClickHouseOperandBindingTest {
    private val parameters = SqlParameters()
    private val compiler =
        ClickHouseFilterCompiler(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), SCHEMA), TABLE, parameters)

    private fun column(type: String) = TypedColumn(ClickHouseColumn("c", type))

    @Test
    fun `a UUID or an Enum compares as its text, any other column as itself`() {
        column("UUID").compared.assert().isEqualTo("toString(`c`)")
        column("Nullable(UUID)").compared.assert().isEqualTo("toString(`c`)")
        column("Enum8('a' = 1)").compared.assert().isEqualTo("toString(`c`)")
        column("Enum16('a' = 1)").compared.assert().isEqualTo("toString(`c`)")
        column("String").compared.assert().isEqualTo("`c`")
        column("LowCardinality(String)").compared.assert().isEqualTo("`c`")
        column("UInt8").compared.assert().isEqualTo("`c`")
    }

    @Test
    fun `a number is bound whole or at its own scale, from any form it comes in`() {
        compiler.bind(column("UInt8"), 256L).assert().isEqualTo("{p0:Int256}")
        compiler.bind(column("UInt64"), "18446744073709551615").assert().isEqualTo("{p1:Int256}")
        compiler.bind(column("Int8"), 1.25).assert().isEqualTo("{p2:Decimal(76, 2)}")
        compiler.bind(column("Decimal(18, 2)"), BigInteger.TEN).assert().isEqualTo("{p3:Decimal(76, 0)}")
        compiler.bind(column("Decimal(18, 2)"), "1.250").assert().isEqualTo("{p4:Decimal(76, 2)}")
        parameters.statement("").parameters.values.assert()
            .containsExactly("256", "18446744073709551615", "1.25", "10", "1.25")
    }

    @Test
    fun `a number that no Decimal256 holds, or an operand that is no number, is refused`() {
        assertThrows<QuerySchemaValidationException> { compiler.bind(column("Int64"), BigInteger.TEN.pow(80)) }
        assertThrows<QuerySchemaValidationException> { compiler.bind(column("Int64"), "ten") }
        assertThrows<QuerySchemaValidationException> { compiler.bind(column("Int64"), true) }
        assertThrows<QuerySchemaValidationException> { compiler.bind(column("DateTime64(3)"), true) }
    }
}
