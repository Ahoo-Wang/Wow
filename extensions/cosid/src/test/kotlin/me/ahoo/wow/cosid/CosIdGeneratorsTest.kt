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

import me.ahoo.cosid.runtime.CosIdRuntime
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test

/** CosId's generators as the node's aggregate id generators (decision D171), on CosId's runtime as its starter starts it. */
class CosIdGeneratorsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")
    private val ledger = AggregateType("shop", "ledger")
    private val radix62 = Regex("[0-9A-Za-z]{15}")

    private fun aggregatesOf(runtime: CosIdRuntime, fallback: String = "default") = CosIdAggregateIdGenerator(
        runtime,
        fallback
    )

    @Test
    fun `a type with no generator of its own should take CosId's default, 15 radix-62 characters sorting like their time`() {
        cosIdRunner().run { context ->
            val aggregates = aggregatesOf(context.getBean(CosIdRuntime::class.java))
            aggregates.fallback.assert().isEqualTo("default")
            aggregates.toString().assert().isEqualTo("CosIdAggregateIdGenerator(default)")
            val ids = (1..2_000).map { aggregates.next(cart) }
            ids.forEach { it.assert().matches(radix62.pattern) }
            ids.toSet().size.assert().isEqualTo(ids.size)
            // The text sorts as the ids were issued: time, then machine, then sequence, each at a fixed width.
            ids.sorted().assert().isEqualTo(ids)
        }
    }

    @Test
    fun `a type should take CosId's generator of its qualified name, else the fallback`() {
        cosIdRunner().run { context ->
            val aggregates = aggregatesOf(context.getBean(CosIdRuntime::class.java))
            // Step 2: the generator named `shop.ledger`, configured as cosid.generators.[shop.ledger]: decimal ids.
            CosIdAggregateIdGenerator.nameOf(ledger).assert().isEqualTo("shop.ledger")
            aggregates.next(ledger).toLong().assert().isPositive()
            // The aggregate's simple name is no convention: the generator `order` is not `shop.order`'s.
            aggregates.next(order).assert().matches(radix62.pattern)
            // Step 3: another fallback, `wow.cosid.aggregate-generator`.
            aggregatesOf(context.getBean(CosIdRuntime::class.java), "counter").next(cart).toLong().assert().isPositive()
        }
    }

    @Test
    fun `a fallback CosId does not have should fail naming the key`() {
        cosIdRunner().run { context ->
            val failure = runCatching { aggregatesOf(context.getBean(CosIdRuntime::class.java), "missing") }
                .exceptionOrNull() as WowConfigurationException
            failure.key.assert().isEqualTo(CosIdProperties.AGGREGATE_GENERATOR)
            failure.message.assert().contains("missing").contains("cosid.generators.missing")
        }
    }

    @Test
    fun `CosId's generators should be found by name, whatever their strategy, and nothing by another`() {
        cosIdRunner().run { context ->
            val aggregates = aggregatesOf(context.getBean(CosIdRuntime::class.java))
            // Step 1: wow.aggregates.[..].id-generator names any of CosId's generators, a dotted one too.
            aggregates.find("counter")!!.next(order).toLong().assert().isPositive()
            aggregates.find("shop.ledger")!!.next(order).toLong().assert().isPositive()
            aggregates.find("order")!!.next(order).assert().matches(radix62.pattern)
            aggregates.find("missing").assert().isNull()
        }
    }
}
