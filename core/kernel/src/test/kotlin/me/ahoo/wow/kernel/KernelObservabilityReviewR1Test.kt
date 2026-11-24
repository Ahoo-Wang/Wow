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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deep review round 1 of the host, configuration and observability: what an observer is told of the commands an
 * aggregate's lane commits as one batch ([Kernel.executeBatch]), against what it is told of a command alone.
 */
class KernelObservabilityReviewR1Test {
    /** Records each execution and append that ends, with its outcome, and each rejection observed. */
    private class Recording : Observer {
        val ended = CopyOnWriteArrayList<String>()
        val rejections = CopyOnWriteArrayList<ObservedEvent.Rejected>()

        private fun stage(name: String) = object : Observation {
            private var outcome: ObservedOutcome? = null

            override fun outcome(outcome: ObservedOutcome) {
                this.outcome = outcome
            }

            override fun end() {
                ended += "$name ${outcome ?: ObservedOutcome.OK}"
            }
        }

        override fun executing(command: Command<*>, commandType: String): Observation = stage("execute")

        override fun appending(stream: EventStreamRecord): Observation = stage("append")

        override fun observed(event: ObservedEvent) {
            if (event is ObservedEvent.Rejected) rejections += event
        }
    }

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    private val observer = Recording()
    private val kernel = Kernel(
        InMemoryEventLog(),
        clock = { 0L },
        states = MapCache(),
        options = KernelOptions(recentRequests = 256),
        observer = observer,
    )
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private var sequence = 0

    private fun queued(body: Any) = QueuedCommand(Command("command-${++sequence}", "request-$sequence", ref, body))

    @Test
    fun `the commands a batch commits should each end their execution and append, as a command alone does`() =
        runTest {
            kernel.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
            // Alone: both stages end, so the span is exported and wow.command.duration recorded.
            observer.ended.assert().containsExactlyInAnyOrder("append committed", "execute committed")
            observer.ended.clear()

            val outcomes = kernel.executeBatch(cartModel, listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1))))

            outcomes.assert().allMatch { (it as BatchOutcome.Done).outcome is CommandOutcome.Committed }
            observer.ended.assert().containsExactlyInAnyOrder(
                "append committed",
                "append committed",
                "execute committed",
                "execute committed",
            )
        }

    @Test
    fun `a batch member rejected without events should be observed rejected once, however often it is decided`() =
        runTest {
            kernel.execute(cartModel, Command("create", "create", ref, CreateCart("alice")))
            val full = queued(AddItem("c", 1))
            // The cart is full at the third product: the batch hands that command back, to be decided alone.
            val outcomes = kernel.executeBatch(
                cartModel,
                listOf(queued(AddItem("a", 1)), queued(AddItem("b", 1)), full)
            )
            outcomes[2].assert().isEqualTo(BatchOutcome.Again)
            // As the executor runs a command handed back at the head of an otherwise empty lane: alone.
            val alone = kernel.execute(cartModel, full.command)
            (alone as CommandOutcome.Rejected).rejection.code.assert().isEqualTo("cart.full")

            observer.rejections.map { it.code }.assert().containsExactly("cart.full")
        }
}
