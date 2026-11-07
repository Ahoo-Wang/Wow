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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
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
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.accountModel
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.subscription.RuntimeFailures
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * The rebuilds of the shared states ([SharedStates.rebuild]): one an aggregate at a time across its readers, under
 * the node's [ReplayGate], whatever the rebuild comes to.
 */
class SharedStateRebuildsTest {
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        @Volatile
        var streamsRead = 0

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
            delegate.read(ref, fromVersion, toVersion).onEach { streamsRead++ }
    }

    /** A gate that holds every replay until [held] completes, counting them; the first throws [refusal], if any. */
    private class HeldGate : ReplayGate {
        val replays = AtomicInteger()
        val held = CompletableDeferred<Unit>()

        @Volatile
        var refusal: Throwable? = null

        override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T {
            replays.incrementAndGet()
            held.await()
            refusal?.let {
                refusal = null
                throw it
            }
            return read()
        }
    }

    private val codec = EventCodec()
    private val log = CountingLog()
    private val kernel = Kernel(log, codec = codec)
    private val cart = cartModel()
    private val gate = HeldGate()
    private val views = SharedStates(codec, READERS.associateWith { setOf(CART) }, gate = gate)
    private val ref: AggregateRef = CART.ref(TENANT, "alice")

    private fun reader(name: String) = SharedStateReader(name, listOf(cart), kernel, codec, views)

    /** [count] commits of the cart by another node, which records no view here; the log reads reset. */
    private suspend fun commits(count: Int): List<EventStreamRecord> {
        val elsewhere = Executor(kernel)
        repeat(count) {
            val command = Command("c-$it", "r-$it", ref, AddItems(listOf("item-$it")), scope = Scope(ownerId = "alice"))
            elsewhere.execute(cart, command)
        }
        return log.read(ref).toList().also { log.streamsRead = 0 }
    }

    /** Lets every coroutine started so far run until it suspends. */
    private suspend fun settle() = repeat(SETTLE) { yield() }

    private fun SerializedState.items(): Set<String> = codec.decodeState(cart, state).items

    @Test
    fun `the readers that miss one aggregate at once should replay its log once`(): Unit = runBlocking {
        val streams = commits(5)
        val first = async { reader(FIRST).read(streams[3]) }
        val same = async { reader(SECOND).read(streams[3]) }
        val next = async { reader(THIRD).read(streams[4]) }
        settle()
        views.rebuilds.assert().isEqualTo(1)

        gate.held.complete(Unit)
        first.await().items().assert().hasSize(4)
        same.await().items().assert().isEqualTo(first.await().items())
        // The reader of the next version evolves the view the rebuild left with the stream in hand.
        next.await().items().assert().hasSize(5)
        gate.replays.get().assert().isEqualTo(1)
        log.streamsRead.assert().isEqualTo(4)
        views.rebuilds.assert().isZero()
    }

    @Test
    fun `a refused rebuild should fail its reader alone, and one reader that waited should rebuild next`(): Unit =
        runBlocking {
            val streams = commits(3)
            gate.refusal = StorageBusyException("The log's store runs as many queries as it allows at once.")
            val first = async { runCatching { reader(FIRST).read(streams[2]) } }
            val second = async { reader(SECOND).read(streams[2]) }
            val third = async { reader(THIRD).read(streams[2]) }
            settle()

            gate.held.complete(Unit)
            val refused = first.await().exceptionOrNull()
            refused.assert().isInstanceOf(StorageBusyException::class.java)
            // The subscription retries it as backpressure: it spends no attempt and parks nothing.
            RuntimeFailures.DEFAULT.classify(refused!!, null).assert().isEqualTo(FailureClass.BACKPRESSURE)
            second.await().items().assert().hasSize(3)
            third.await().items().assert().hasSize(3)
            // The refused replay, then the one of the reader next in line; the last read the view it left.
            gate.replays.get().assert().isEqualTo(2)
            log.streamsRead.assert().isEqualTo(3)
            views.rebuilds.assert().isZero()
        }

    @Test
    fun `a reader's own timeout should end its wait alone, and a cancelled rebuild should hand the rebuild on`(): Unit =
        runBlocking {
            val streams = commits(3)
            val leader = launch { reader(FIRST).read(streams[2]) }
            settle()

            withTimeoutOrNull(WAIT) { reader(SECOND).read(streams[2]) }.assert().isNull()
            views.rebuilds.assert().isEqualTo(1)
            leader.isActive.assert().isTrue()

            val third = async { reader(THIRD).read(streams[2]) }
            settle()
            // The leader's attempt is cancelled (its own timeout, or its lane stopping): the reader that waited leads.
            leader.cancelAndJoin()
            gate.held.complete(Unit)
            third.await().items().assert().hasSize(3)
            gate.replays.get().assert().isEqualTo(2)
            log.streamsRead.assert().isEqualTo(3)
            views.rebuilds.assert().isZero()
        }

    @Test
    fun `a node should rebuild its states under the gate it is built with, at once without one`(): Unit = runBlocking {
        val manifest = accountsManifest(BANK)
        val gated = ServiceNode(
            NODE_SERVICE,
            "node-1",
            null,
            listOf(accountModel(BANK)),
            manifest,
            InMemoryBackends(),
            LoopbackClusterChannel(),
            contracts = ContractDirectory(manifest),
            replayGate = gate,
        )
        val open = ServiceNode(
            NODE_SERVICE,
            "node-2",
            null,
            listOf(accountModel(BANK)),
            manifest,
            InMemoryBackends(),
            LoopbackClusterChannel(),
            contracts = ContractDirectory(manifest),
        )
        try {
            gated.contexts.values.map { it.views.gate }.assert().isNotEmpty().containsOnly(gate)
            open.contexts.values.map { it.views.gate }.assert().containsOnly(ReplayGate.OPEN)
        } finally {
            gated.stop()
            open.stop()
        }
    }

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
        const val THIRD = "third"
        val READERS = listOf(FIRST, SECOND, THIRD)
        const val BANK = "bank"
        const val SETTLE = 10
        val WAIT = 50.milliseconds
    }
}
