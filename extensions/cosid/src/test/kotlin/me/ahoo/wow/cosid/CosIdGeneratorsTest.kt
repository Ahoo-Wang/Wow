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

package me.ahoo.wow.cosid

import me.ahoo.cosid.CosId
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.cosid.snowflake.MillisecondSnowflakeId
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** CosId's generators as the node's (decision D171). */
class CosIdGeneratorsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")

    /** A generator of fixed ids, without a machine id, as a segment generator is. */
    private class Counting(private val prefix: Long) : IdGenerator {
        private var next = 0L

        override fun generate(): Long = prefix + ++next

        override fun generateAsString(): String = generate().toString()
    }

    @Test
    fun `the global ids should come from CosId's generator of the name, else from its shared one`() {
        val provider = DefaultIdGeneratorProvider().apply {
            share = Counting(100)
            set(CosId.COSID, Counting(200))
            set("orders", Counting(300))
        }
        val global = CosIdGlobalIdGenerator(provider)
        global.name.assert().isEqualTo(CosId.COSID)
        global.next().assert().isEqualTo("201")
        global.next().assert().isEqualTo("202")
        CosIdGlobalIdGenerator(provider, "orders").next().assert().isEqualTo("301")
        CosIdGlobalIdGenerator(provider, "missing").next().assert().isEqualTo("101")
    }

    @Test
    fun `a type's ids should come from CosId's generator of the aggregate's name`() {
        val provider = DefaultIdGeneratorProvider().apply { set("order", Counting(500)) }
        val aggregates = CosIdAggregateIdGenerator(provider) { error("No machine id is needed.") }
        aggregates.next(order).assert().isEqualTo("501")
        aggregates.next(order).assert().isEqualTo("502")
    }

    @Test
    fun `a type without a generator of its own should get a Radix62 CosId generator of the machine id`() {
        var asked = 0
        val aggregates = CosIdAggregateIdGenerator(DefaultIdGeneratorProvider()) {
            asked++
            7
        }
        val ids = (1..100).map { aggregates.next(cart) }
        ids.toSet().size.assert().isEqualTo(ids.size)
        ids.forEach { it.contains('-').assert().isFalse() }
        Radix62CosIdGenerator(7).stateParser.asState(ids.first()).machineId.assert().isEqualTo(7)
        aggregates.next(order)
        asked.assert().isEqualTo(2)
    }

    @Test
    fun `CosId's generators should be found by name, and nothing by a name it does not have`() {
        val provider = DefaultIdGeneratorProvider().apply { set("legacy-orders", Counting(900)) }
        val aggregates = CosIdAggregateIdGenerator(provider) { 1 }
        aggregates.find("legacy-orders")!!.next(order).assert().isEqualTo("901")
        aggregates.find("missing").assert().isNull()
    }

    @Test
    fun `the machine id should be a CosId or Snowflake generator's, and none of another`() {
        CosIdAggregateIdGenerator.machineIdOf(ClockSyncCosIdGenerator(Radix62CosIdGenerator(3))).assert().isEqualTo(3)
        CosIdAggregateIdGenerator.machineIdOf(MillisecondSnowflakeId(5)).assert().isEqualTo(5)
        assertThrownBy<IllegalStateException> { CosIdAggregateIdGenerator.machineIdOf(Counting(0)) }
            .hasMessageContaining("no machine id")
    }
}
