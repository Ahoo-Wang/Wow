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

package me.ahoo.wow.runtime.state

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.binding.AddItems
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.subscription.RuntimeFailures
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.VersionPages
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.StateRebuilt.Outcome
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which rebuilds of the shared states meet the node's [ReplayGate] (X-118): a replay of more than one page of the log
 * ([VersionPages.SIZE] streams) does; one of a page or less is one statement and runs at once, so a subscription
 * whose reads miss its views is not held to the query bound of the log's store. Each rebuild is observed
 * ([ObservedEvent.StateRebuilt]).
 */
class SharedStateRebuildGateTest {
    /** A gate that refuses every replay, as the query bulkhead of a store under write pressure may. */
    private object RefusingGate : ReplayGate {
        override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T =
            throw StorageBusyException("The log's store runs as many queries as it allows at once.")
    }

    /** A gate that admits every replay, then fails it, as a store that fails the read it was admitted for. */
    private object FailingGate : ReplayGate {
        override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T {
            read()
            throw IllegalStateException("The log's store failed the read.")
        }
    }

    /** A gate that admits every replay, as an idle bulkhead does. */
    private object AdmittingGate : ReplayGate {
        override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T = read()
    }

    private val codec = EventCodec()
    private val log = InMemoryEventLog()
    private val kernel = Kernel(log, codec = codec)
    private val cart = cartModel()
    private val observed = CopyOnWriteArrayList<ObservedEvent.StateRebuilt>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            if (event is ObservedEvent.StateRebuilt) observed += event
        }
    }
    private val ref: AggregateRef = CART.ref(TENANT, "alice")

    private fun reader(gate: ReplayGate): SharedStateReader {
        val views = SharedStates(codec, mapOf(READER to setOf(CART)), gate = gate, observer = observer)
        return SharedStateReader(READER, listOf(cart), kernel, codec, views)
    }

    /** [count] commits of the cart by another node, which records no view here. */
    private suspend fun commits(count: Int): List<EventStreamRecord> {
        val elsewhere = Executor(kernel)
        repeat(count) {
            val command = Command("c-$it", "r-$it", ref, AddItems(listOf("item-$it")), scope = Scope(ownerId = "alice"))
            elsewhere.execute(cart, command)
        }
        return log.read(ref).toList()
    }

    private fun SerializedState.items(): Set<String> = codec.decodeState(cart, state).items

    @Test
    fun `a page runs past a gate that refuses every replay, a page and one stream does not`(): Unit = runBlocking {
        val streams = commits(PAGE + 1)
        // From version 1 to a page: one statement, run at once.
        reader(RefusingGate).read(streams[PAGE - 1]).items().assert().hasSize(PAGE)
        observed.single().let {
            it.gated.assert().isFalse()
            it.outcome.assert().isEqualTo(Outcome.RAN)
        }
        // A page and one stream: the gate's, which refuses it as a busy store, retried as backpressure.
        val refused = assertThrows<StorageBusyException> {
            runBlocking { reader(RefusingGate).read(streams[PAGE]) }
        }
        RuntimeFailures.DEFAULT.classify(refused, null).assert().isEqualTo(FailureClass.BACKPRESSURE)
        observed.last().let {
            it.gated.assert().isTrue()
            it.outcome.assert().isEqualTo(Outcome.REFUSED)
            it.type.assert().isEqualTo(CART)
        }
    }

    @Test
    fun `the replay is counted from the nearest base, a view the reader left, not from version 1`(): Unit =
        runBlocking {
            val streams = commits(PAGE + 3)
            val reader = reader(RefusingGate)
            // Its first rebuild, of two streams, leaves a view at version 2.
            reader.read(streams[1]).items().assert().hasSize(2)
            // From that view: a page and one stream is refused, a page runs.
            assertThrownBy<StorageBusyException> { runBlocking { reader.read(streams[PAGE + 2]) } }
            reader.read(streams[PAGE + 1]).items().assert().hasSize(PAGE + 2)
            observed.map { it.gated to it.outcome }.assert()
                .containsExactly(false to Outcome.RAN, true to Outcome.REFUSED, false to Outcome.RAN)
        }

    @Test
    fun `a view that does not decode is no base, and the replay is counted from where the rebuild starts`(): Unit =
        runBlocking {
            val streams = commits(PAGE + 1)
            val views = SharedStates(codec, mapOf(READER to setOf(CART)), gate = RefusingGate, observer = observer)
            views.put(ref, SerializedState(2, "not a cart"))
            val reader = SharedStateReader(READER, listOf(cart), kernel, codec, views)
            // Counted from that view, a page; the rebuild replays from version 1, a page and one stream: the gate's.
            assertThrownBy<StorageBusyException> { runBlocking { reader.read(streams[PAGE]) } }
            observed.single().let {
                it.gated.assert().isTrue()
                it.outcome.assert().isEqualTo(Outcome.REFUSED)
            }
        }

    @Test
    fun `a gated rebuild that runs is observed with its wait for the permit`(): Unit = runBlocking {
        val streams = commits(PAGE + 1)
        reader(AdmittingGate).read(streams[PAGE]).items().assert().hasSize(PAGE + 1)
        observed.single().let {
            it.gated.assert().isTrue()
            it.outcome.assert().isEqualTo(Outcome.RAN)
            it.waitMillis.assert().isGreaterThanOrEqualTo(0)
        }
    }

    @Test
    fun `a gated rebuild the gate admitted is observed as ran, whatever its read comes to`(): Unit = runBlocking {
        val streams = commits(PAGE + 1)
        assertThrownBy<IllegalStateException> { runBlocking { reader(FailingGate).read(streams[PAGE]) } }
        observed.map { it.gated to it.outcome }.assert().containsExactly(true to Outcome.RAN)
    }

    private companion object {
        const val READER = "reader"
        const val PAGE = VersionPages.SIZE
    }
}
