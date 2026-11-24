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

package me.ahoo.wow.kernel

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test

/**
 * Deep review r1, second part (kernel): the saga guide promises that "handling the commit again derives the same
 * requests in whatever order the handler builds them, even when a read answers in another order, and each target
 * decides each one once". A derived command's id is its target, its command type and how many commands of that
 * type to that target the execution derived before it ([DerivedRequests]). Two commands of one type to one target,
 * built in another order on the redelivery (a read that answers in another order), swap their ids. The target
 * answers the first id with the commit of the other command and runs the second id anew: one command is decided
 * twice, the other never.
 */
class R1SagaRedeliveryOrderTest {
    data class Order(val id: String)
    data class OrderPlaced(val skus: List<String>)
    data class Reserve(val sku: String)
    data class Reserved(val sku: String)

    class Stock(val id: String) {
        val reserved = mutableListOf<String>()
    }

    private val stock = aggregate<Stock>("shop", "stock") {
        initial { Stock(it.id) }
        decide<Reserve>(Creation.ALLOWED) { _, body -> Decision.events(Reserved(body.sku)) }
        evolve<Reserved> { reserved += it.sku }
    }
    private val warehouse = stock.type.ref("t", "w-1")
    private val order = AggregateType("shop", "order").ref("t", "o-1")
    private val event = stateEvent(order, Order("o-1"), OrderPlaced(listOf("a", "b")))
    private val execution = ExecutionId("shop", "StockSaga.onPlaced", order, 1)

    /** One handling of the commit: the saga reserves each sku at the warehouse, in the order its read gave them. */
    private fun handle(skus: List<String>) =
        ExecutionSagaContext(execution, event, IdGenerators.DEFAULT, CommandTypes.SIMPLE_NAMES)
            .commandsOf(skus.map { CommandRequest.of(Reserve(it), warehouse.id, stock.type) })

    @Test
    fun `a redelivered commit whose read answers in another order reserves each sku once`() = runTest {
        val log = InMemoryEventLog()
        val kernel = Kernel(log)
        // The first handling commits the reservation of "a", then fails before the one of "b" (the store went away).
        kernel.execute(stock, handle(listOf("a", "b")).first())
        // The commit is handled again; its read answers in another order.
        handle(listOf("b", "a")).forEach { kernel.execute(stock, it) }

        val state = kernel.stateAt(stock, warehouse, 2)?.state as Stock?
        state?.reserved.assert().describedAs("the skus the warehouse reserved").containsExactlyInAnyOrder("a", "b")
    }
}
