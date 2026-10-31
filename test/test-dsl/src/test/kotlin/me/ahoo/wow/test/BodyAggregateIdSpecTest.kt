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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.aggregate
import org.junit.jupiter.api.Test

/** A command whose body names its aggregate (`@AggregateId`) names the specification's, as admission requires. */
class BodyAggregateIdSpecTest {
    private data class Stock(val productId: String?, val quantity: Int)
    private data class Stocked(val quantity: Int)

    private val model = aggregate<Any>("inventory", "product") {
        initial { Any() }
        decide<Stock>(Creation.ALLOWED) { _, body -> Decision.events(Stocked(body.quantity)) }
        evolve<Stocked> { }
        aggregateId<Stock> { it.productId }
    }

    @Test
    fun `a body that names the specification's aggregate, or none, is sent`() = aggregateSpec(model, id = "mug") {
        whenCommand(Stock("mug", 1)) { expectEvents(Stocked(1)) }
        whenCommand(Stock(null, 2)) { expectEvents(Stocked(2)) }
    }

    @Test
    fun `a body that names another aggregate fails the specification`() {
        assertThrownBy<IllegalArgumentException> {
            aggregateSpec(model, id = "mug") { whenCommand(Stock("cup", 1)) { expectEvents(Stocked(1)) } }
        }.hasMessageContaining("Stock names the aggregate cup in its body, but the specification is of mug")
    }
}
