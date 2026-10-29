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

/** CosId's generators as the node's (decision D171), on CosId's runtime as its starter starts it. */
class CosIdGeneratorsTest {
    private val order = AggregateType("shop", "order")
    private val cart = AggregateType("shop", "cart")
    private val ledger = AggregateType("shop", "ledger")
    private val radix62 = Regex("[0-9A-Za-z]{15}")

    @Test
    fun `the global ids should be CosId's default generator's, 15 radix-62 characters that sort like their time`() {
        cosIdRunner().run { context ->
            val global = CosIdGlobalIdGenerator(context.getBean(CosIdRuntime::class.java))
            global.name.assert().isEqualTo("default")
            global.toString().assert().isEqualTo("CosIdGlobalIdGenerator(default)")
            val ids = (1..2_000).map { global.next() }
            ids.forEach { it.assert().matches(radix62.pattern) }
            ids.toSet().size.assert().isEqualTo(ids.size)
            // The text sorts as the ids were issued: time, then machine, then sequence, each at a fixed width.
            ids.sorted().assert().isEqualTo(ids)
        }
    }

    @Test
    fun `a global generator CosId does not have should fail naming the key`() {
        cosIdRunner().run { context ->
            val failure = runCatching { CosIdGlobalIdGenerator(context.getBean(CosIdRuntime::class.java), "missing") }
                .exceptionOrNull() as WowConfigurationException
            failure.key.assert().isEqualTo(CosIdProperties.GLOBAL_GENERATOR)
            failure.message.assert().contains("missing").contains("cosid.generators.missing")
        }
    }

    @Test
    fun `a type's ids should be CosId's generator of its qualified name, else the global ids`() {
        cosIdRunner().run { context ->
            val runtime = context.getBean(CosIdRuntime::class.java)
            val aggregates = CosIdAggregateIdGenerator(runtime, CosIdGlobalIdGenerator(runtime))
            // Step 2: the generator named `shop.ledger`, configured as cosid.generators.[shop.ledger]: decimal ids.
            CosIdAggregateIdGenerator.nameOf(ledger).assert().isEqualTo("shop.ledger")
            aggregates.next(ledger).toLong().assert().isPositive()
            // A generator named after the aggregate's simple name is no convention: `order` is not `shop.order`'s.
            // Step 3: a type without a generator of its own takes the global ids, as unique and as ordered.
            val orders = (1..100).map { aggregates.next(order) }
            orders.forEach { it.assert().matches(radix62.pattern) }
            orders.toSet().size.assert().isEqualTo(orders.size)
            orders.sorted().assert().isEqualTo(orders)
            aggregates.next(cart).assert().matches(radix62.pattern)
        }
    }

    @Test
    fun `CosId's generators should be found by name, whatever their strategy, and nothing by another`() {
        cosIdRunner().run { context ->
            val runtime = context.getBean(CosIdRuntime::class.java)
            val aggregates = CosIdAggregateIdGenerator(runtime, CosIdGlobalIdGenerator(runtime))
            aggregates.find("counter")!!.next(order).toLong().assert().isPositive()
            aggregates.find("order")!!.next(order).assert().matches(radix62.pattern)
            aggregates.find("missing").assert().isNull()
        }
    }
}
