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

package me.ahoo.wow.runtime.wait

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a wait chain arrives (decision D190): the node a command of the chain reaches checks the hops of its own
 * context that the node the wait started on may have let through unchecked.
 */
class WaitArrivalTest {
    data class Reserve(val order: String)
    data class Reserved(val order: String)

    class Stock(val id: String)

    private val stock = aggregate<Stock>("shop", "stock") {
        initial { Stock(it.id) }
        decide<Reserve>(Creation.ALLOWED) { _, body -> Decision.events(Reserved(body.order)) }
        evolve<Reserved> { }
    }
    private val origin = Origin("node-2", "10.0.0.2:9090", "sales")
    private val sent = CopyOnWriteArrayList<CommandResult>()
    private val directory = object : SubscriptionDirectory {
        override fun kindOf(context: String, subscription: String): FunctionKind? =
            FunctionKind.PROJECT.takeIf { context == "shop" && subscription == "stock.projector" }

        override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false
    }
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog(partitions = 2))),
        WaitRegistry(),
        directory,
        LocalSignalSender("node-1", WaitRegistry()) { _, signal -> sent += signal.result },
        WaitOptions(Origin("node-1", "127.0.0.1:9090", "shop")),
    )

    private fun reserve(id: String) = Command("c-$id", "r-$id", stock.type.ref("t", id), Reserve("o-$id"))

    private fun wait(vararg hops: Hop) = WaitContext("w-1", origin, hops.toList(), System.currentTimeMillis() + 10_000)

    @Test
    fun `a command whose next hop names a subscription its context lacks executes and signals what it reaches`(): Unit =
        runBlocking {
            val settlement = coordinator.forward(
                stock,
                reserve("s-1"),
                wait(Hop.committed("shop"), Hop.handled("ghost.saga", "shop")),
            )
            settlement.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            sent.map { it.hop }.assert().containsExactly(Hop.committed("shop"))
            // A next hop of another context, or a known subscription, is not this node's to doubt.
            coordinator.forward(stock, reserve("s-2"), wait(Hop.committed("shop"), Hop.handled("ghost.saga", "sales")))
            coordinator.forward(stock, reserve("s-3"), wait(Hop.committed("shop"), Hop.handled("stock.projector")))
            sent.map { it.commandId }.assert().containsExactly("c-s-1", "c-s-2", "c-s-3")
        }

    @Test
    fun `a command the chain expects in another context executes, and the wait is not followed`(): Unit =
        runBlocking {
            val settlement = coordinator.forward(stock, reserve("s-4"), wait(Hop.committed("payment")))
            settlement.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            sent.assert().isEmpty()
        }

    @Test
    fun `the hops of a wait chain are capped`() {
        WaitOptions(origin).maxHops.assert().isEqualTo(WaitOptions.DEFAULT_MAX_HOPS)
        WaitOptions(origin).unknownContexts.assert().isEqualTo(UnknownContexts.ALLOW)
        WaitOptions(origin, maxHops = WaitOptions.MAX_HOPS_CAP).maxHops.assert().isEqualTo(32)
        assertThrows<IllegalArgumentException> { WaitOptions(origin, maxHops = WaitOptions.MAX_HOPS_CAP + 1) }
    }
}
