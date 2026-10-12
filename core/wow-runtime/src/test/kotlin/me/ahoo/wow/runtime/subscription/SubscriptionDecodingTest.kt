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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class SubscriptionDecodingTest {
    data class Added(val amount: Int)
    data class Removed(val amount: Int)

    class Counter {
        var total = 0
    }

    private val model = aggregate<Counter>("shop", "cart") {
        initial { Counter() }
        evolve<Added> { total += it.amount }
        evolve<Removed> { total -= it.amount }
    }
    private val codec = EventCodec()
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()

    private fun runtime(subscription: Subscription) =
        SubscriptionRuntime("group", log, checkpoints, InMemoryFailureLedger(), listOf(subscription), testOptions())

    @Test
    fun `a model decoder hands the handler typed events`(): Unit = runBlocking {
        val events = CopyOnWriteArrayList<Any>()
        val runtime = runtime(
            Subscription("typed", SubscriptionFilter("shop"), decoder = EntryDecoder.models(listOf(model), codec)) {
                events.addAll(it.events)
            },
        )
        appender.append("a", events = listOf(codec.encode(model, Added(2)), codec.encode(model, Removed(1))))
        runtime.start(setOf(0))
        eventually { events.size == 2 }
        runtime.stop()
        events.assert().isEqualTo(listOf(Added(2), Removed(1)))
    }

    @Test
    fun `the record decoder hands over the stored records`(): Unit = runBlocking {
        val received = CopyOnWriteArrayList<SubscribedEntry>()
        val runtime = runtime(Subscription("raw", SubscriptionFilter("shop")) { received += it })
        appender.append("a")
        runtime.start(setOf(0))
        eventually { received.size == 1 }
        runtime.stop()
        received.single().events.assert().isEqualTo(listOf(EventRecord(ADDED, 1, "{}")))
        received.single().position!!.sequence.assert().isEqualTo(1L)
        received.single().ref.assert().isEqualTo(cart("a"))
    }

    @Test
    fun `entries outside the filter are not handled but move the checkpoint`(): Unit = runBlocking {
        val handled = Handled()
        val runtime = runtime(
            Subscription("filtered", SubscriptionFilter("shop", aggregates = setOf("cart"), events = setOf(ADDED))) {
                handled.record(it)
            },
        )
        appender.append("a") // handled
        appender.append("o", type = AggregateType("shop", "order")) // another aggregate
        appender.append("i", type = AggregateType("billing", "cart")) // another context
        appender.append("a", events = listOf(EventRecord("shop.cart.Removed", 1, "{}"))) // another event
        appender.append("a") // handled
        runtime.start(setOf(0))
        eventually { checkpoints.load("group", "filtered", 0) == 5L }
        runtime.stop()
        handled.entries.map { it.first to it.second }.assert().isEqualTo(listOf("a" to 1, "a" to 3))
    }

    @Test
    fun `definitions refuse blank names`() {
        runCatching { Subscription(" ", SubscriptionFilter("shop")) {} }.isFailure.assert().isTrue()
        runCatching { SubscriptionFilter(" ") }.isFailure.assert().isTrue()
        runCatching { RetryPolicy(maxAttempts = 0) }.isFailure.assert().isTrue()
        runCatching { SubscriptionOptions(maxInFlight = 0) }.isFailure.assert().isTrue()
        RetryPolicy(initialBackoff = kotlin.time.Duration.ZERO, multiplier = 3.0).backoff(3).assert()
            .isEqualTo(kotlin.time.Duration.ZERO)
    }
}
