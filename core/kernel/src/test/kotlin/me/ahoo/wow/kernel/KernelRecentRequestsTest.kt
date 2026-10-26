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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.spi.log.AggregateTail
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.CommittedRequest
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.Transport
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The aggregate's recent requests (decision D261, revising D46): an exact set derived from the log replaces the
 * request-id lookup for most commands, and every answer stays the one the lookup gave (conditions C1–C6, and the
 * failure scenarios of the adversarial check).
 */
class KernelRecentRequestsTest {
    private val ref = cartModel.type.ref("tenant", "cart-1")
    private val owned = Scope("alice", "space")
    private var sequence = 0
    private val log = CountingLog()
    private val states = MapCache()

    private fun kernel(recentRequests: Int = 256, snapshots: InMemorySnapshotStore? = null) =
        Kernel(log, states = states, recentRequests = recentRequests, snapshots = snapshots)

    @Suppress("LongParameterList")
    private fun command(
        body: Any,
        requestId: String = "request-${++sequence}",
        target: AggregateRef = ref,
        expectedVersion: Int? = null,
        scope: Scope = Scope.NONE,
        causation: Causation? = null,
    ) = Command(
        "command-${++sequence}",
        requestId,
        target,
        body,
        expectedVersion = expectedVersion,
        scope = scope,
        causation = causation,
    )

    private suspend fun Kernel.commit(command: Command<*>): EventStreamRecord =
        (execute(cartModel, command) as CommandOutcome.Committed).stream

    /** Counts what the kernel asks of the log; can hide committed requests from the lookup, or lose an outcome. */
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var lookups = 0
        val reads = mutableListOf<IntRange>()
        val tails = mutableListOf<Pair<Int, Int>>()
        var appends = 0

        /** The lookup finds nothing, and an append to a taken version is a conflict (a backend checking it first). */
        var hideRequests = false

        /** The next append commits, then its outcome is unknown and its version slot cannot be read. */
        var loseNext = false

        fun reset() {
            lookups = 0
            reads.clear()
            tails.clear()
            appends = 0
        }

        override suspend fun findByRequestId(ref: AggregateRef, requestId: String): EventStreamRecord? {
            lookups++
            return if (hideRequests) null else delegate.findByRequestId(ref, requestId)
        }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            reads += fromVersion..toVersion
            return delegate.read(ref, fromVersion, toVersion)
        }

        override suspend fun readTail(ref: AggregateRef, fromVersion: Int, earlier: Int): AggregateTail {
            tails += fromVersion to earlier
            return delegate.readTail(ref, fromVersion, earlier)
        }

        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
            if (loseNext) {
                throw IOException(
                    "The slot cannot be read."
                )
            } else {
                delegate.readEntries(ref, fromVersion, toVersion)
            }

        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
            appends++
            if (hideRequests) {
                val current = delegate.read(stream.ref).toList().size
                if (stream.version <= current) return AppendOutcome.Conflict(current)
            }
            val outcome = delegate.append(stream, transport)
            if (loseNext) {
                return AppendOutcome.Unknown(IOException("The outcome is lost."))
            }
            return outcome
        }
    }

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    @Test
    fun `commands on a warm aggregate append without looking their request ids up`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo")))
        kernel.commit(command(AddItem("apple", 1)))
        kernel.commit(command(AddItem("apple", 2)))
        log.lookups.assert().isZero()
        // Only the creation loads the aggregate: a replay, which gives every request it ever committed.
        log.reads.assert().containsExactly(1..Int.MAX_VALUE)
        log.appends.assert().isEqualTo(3)
        states.states.getValue(ref).requests!!.let {
            it.size.assert().isEqualTo(3)
            it.complete.assert().isTrue()
        }
    }

    @Test
    fun `a retry the recent requests hold is answered from its stream, without a lookup`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo")))
        val added = kernel.commit(command(AddItem("apple", 1), requestId = "same"))
        log.reset()
        kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "same"))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(added))
        log.lookups.assert().isZero()
        log.reads.assert().containsExactly(2..2)
        log.appends.assert().isZero()
        // Another command type reusing the request id is refused as before (D55), and the state stays cached.
        val reused = kernel.execute(cartModel, command(ClearCart("x"), requestId = "same")) as CommandOutcome.Rejected
        reused.rejection.code.assert().isEqualTo(KernelRejections.REQUEST_ID_REUSED)
        log.lookups.assert().isZero()
        log.reads.assert().containsExactly(2..2, 2..2)
        states.states.getValue(ref).version.assert().isEqualTo(2)
    }

    @Test
    fun `a retry from outside the aggregate's scope learns nothing of it`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo"), scope = owned))
        kernel.commit(command(AddItem("apple", 1), requestId = "same", scope = owned))
        val outsider = kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "same", scope = Scope("bob")))
        (outsider as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.FORBIDDEN)
    }

    @Test
    fun `a creation retried beyond the recent requests is a duplicate, by a lookup before refusing`(): Unit = runTest {
        val kernel = kernel(recentRequests = 2)
        val created = kernel.commit(command(CreateCart("ahoo"), requestId = "create"))
        repeat(3) { kernel.commit(command(AddItem("apple", 1))) }
        states.states.getValue(ref).requests!!.complete.assert().isFalse()
        log.reset()
        kernel.execute(cartModel, command(CreateCart("ahoo"), requestId = "create"))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(created))
        log.lookups.assert().isEqualTo(1)
        log.appends.assert().isZero()
        // Cold too: the replay keeps the latest two, so it is no longer complete.
        states.states.clear()
        kernel.execute(cartModel, command(CreateCart("ahoo"), requestId = "create"))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(created))
        log.lookups.assert().isEqualTo(2)
    }

    @Test
    fun `a command at an expected version retried beyond the recent requests is a duplicate`(): Unit = runTest {
        val kernel = kernel(recentRequests = 2)
        kernel.commit(command(CreateCart("ahoo")))
        val expected = kernel.commit(command(AddItem("apple", 1), requestId = "expected", expectedVersion = 1))
        repeat(3) { kernel.commit(command(AddItem("apple", 1))) }
        log.reset()
        kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "expected", expectedVersion = 1))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(expected))
        log.lookups.assert().isEqualTo(1)
    }

    @Test
    fun `a saga's command sent again after its aggregate resumes is a duplicate, not a rejection`(): Unit = runTest {
        // A rejection would reach the saga's @OnRejected, which may compensate (a refund) for what committed.
        val kernel = kernel(recentRequests = 2)
        val saga = Causation("correlation", "event")
        kernel.commit(command(CreateCart("ahoo")))
        val derived = kernel.commit(command(AddItem("pear", 1), requestId = "saga-1", causation = saga))
        repeat(3) { kernel.commit(command(AddItem("apple", 1))) }
        log.reset()
        // Sent again days later, the saga's handler builds it from what it reads now: the cart is full.
        kernel.execute(cartModel, command(AddItem("banana", 1), requestId = "saga-1", causation = saga))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(derived))
        log.lookups.assert().isEqualTo(1)
    }

    @Test
    fun `a request id reused on another aggregate of the type, or a retry routed to one, is answered as before`(): Unit =
        runTest {
            val kernel = kernel()
            val other = cartModel.type.ref("tenant", "cart-2")
            val missing = cartModel.type.ref("tenant", "cart-3")
            kernel.commit(command(CreateCart("ahoo")))
            val first = kernel.commit(command(AddItem("apple", 1), requestId = "reused"))
            kernel.commit(command(CreateCart("bob"), target = other))
            log.reset()
            // A pure decide misses the other aggregate's recent requests and appends: the request key answers it.
            kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "reused", target = other))
                .assert().isEqualTo(CommandOutcome.AlreadyCommitted(first))
            log.read(other).toList().size.assert().isEqualTo(1)
            // A refusal looks it up before answering: the same answer, not NOT_FOUND.
            kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "reused", target = missing))
                .assert().isEqualTo(CommandOutcome.AlreadyCommitted(first))
            Kernel(log, recentRequests = 0)
                .execute(cartModel, command(AddItem("apple", 1), requestId = "reused", target = other))
                .assert().isEqualTo(CommandOutcome.AlreadyCommitted(first))
        }

    @Test
    fun `a request id reused on another aggregate in another scope is refused at the request key as by the lookup`(): Unit =
        runTest {
            val kernel = kernel()
            val other = cartModel.type.ref("tenant", "cart-2")
            kernel.commit(command(CreateCart("ahoo"), scope = owned))
            kernel.commit(command(AddItem("apple", 1), requestId = "reused", scope = owned))
            kernel.commit(command(CreateCart("bob"), target = other, scope = Scope("bob")))
            val reused = kernel.execute(
                cartModel,
                command(AddItem("apple", 1), requestId = "reused", target = other, scope = Scope("bob")),
            )
            (reused as CommandOutcome.Rejected).rejection.code.assert().isEqualTo(KernelRejections.FORBIDDEN)
        }

    @Test
    fun `a decide that does I-O looks its request id up unless the owner holds a complete set`(): Unit = runTest {
        val kernel = kernel(recentRequests = 2)
        kernel.commit(command(CreateCart("ahoo")))
        log.reset()
        // No lease known (a Local router): looked up.
        kernel.commit(command(CheckStock("apple")))
        log.lookups.assert().isEqualTo(1)
        // The owner, its set holding the whole history: not looked up.
        withContext(ExecutionLease.HELD) { kernel.commit(command(CheckStock("apple"))) }
        log.lookups.assert().isEqualTo(1)
        // The set left the creation out: looked up again.
        withContext(ExecutionLease.HELD) { kernel.commit(command(CheckStock("apple"))) }
        log.lookups.assert().isEqualTo(2)
        // A pure decide never is.
        withContext(ExecutionLease.HELD) { kernel.commit(command(AddItem("apple", 1))) }
        log.lookups.assert().isEqualTo(2)
    }

    @Test
    fun `a command run without the partition's lease drops the cached state and caches none`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo")))
        log.reset()
        withContext(ExecutionLease.NOT_HELD) {
            kernel.commit(command(CheckStock("apple")))
            kernel.commit(command(AddItem("apple", 1)))
            kernel.execute(cartModel, command(ClearCart("x")))
        }
        states.states.assert().isEmpty()
        // Each loaded the aggregate afresh, and the decide doing I/O looked its request id up.
        log.reads.filter { it.last == Int.MAX_VALUE }.size.assert().isEqualTo(3)
        log.lookups.assert().isEqualTo(2)
    }

    @Test
    fun `a commit whose outcome was unknown is found by the next load's recent requests`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo")))
        log.loseNext = true
        kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "lost"))
            .assert().isInstanceOf(CommandOutcome.Unknown::class.java)
        log.loseNext = false
        states.states.assert().isEmpty()
        log.reset()
        val retry = kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "lost"))
        (retry as CommandOutcome.AlreadyCommitted).stream.version.assert().isEqualTo(2)
        log.lookups.assert().isZero()
    }

    @Test
    fun `a hash that collides is confirmed against the stream, then taken for a miss`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo"), requestId = "create"))
        // As if "collides" hashed like the creation's request id, committed at version 1.
        fun collide(vararg ids: String) {
            val keys = ids.map { CommittedRequest.of(1, it, "example.cart.CreateCart") }
            states.states.getValue(ref).requests = RecentRequests.of(256, keys, emptyList(), reachedStart = true)
        }
        collide("collides")
        log.reset()
        // A pure decide goes on to decide.
        kernel.commit(command(AddItem("apple", 1), requestId = "collides")).version.assert().isEqualTo(2)
        log.reads.assert().containsExactly(1..1)
        log.lookups.assert().isZero()
        // A decide that does I/O no longer trusts the set, complete as it is: looked up.
        collide("checks")
        withContext(ExecutionLease.HELD) {
            kernel.commit(command(CheckStock("apple"), requestId = "checks")).version.assert().isEqualTo(3)
        }
        log.lookups.assert().isEqualTo(1)
    }

    @Test
    fun `the recent requests are checked again after every load`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo")))
        // Another node commits the request while this one keeps version 1 cached.
        val elsewhere = Kernel(log).commit(command(AddItem("apple", 1), requestId = "elsewhere"))
        log.hideRequests = true
        log.reset()
        // The stale set misses, the append conflicts and the lookup does not see it yet: the reload's set does.
        kernel.execute(cartModel, command(AddItem("apple", 1), requestId = "elsewhere"))
            .assert().isEqualTo(CommandOutcome.AlreadyCommitted(elsewhere))
        log.appends.assert().isEqualTo(1)
    }

    @Test
    fun `a refusal or a no-op on a warm aggregate keeps the cost of the lookup and the slot read`(): Unit = runTest {
        val kernel = kernel()
        kernel.commit(command(CreateCart("ahoo")))
        log.reset()
        kernel.execute(cartModel, command(ClearCart("x"))).assert().isEqualTo(CommandOutcome.Unchanged(1))
        log.lookups.assert().isEqualTo(1)
        log.reads.assert().containsExactly(2..2)
        kernel.execute(
            cartModel,
            command(CreateCart("ahoo"))
        ).assert().isInstanceOf(CommandOutcome.Rejected::class.java)
        log.lookups.assert().isEqualTo(2)
    }

    @Test
    fun `a snapshot's tail is read with one more earlier request than are kept`(): Unit = runTest {
        val snapshots = InMemorySnapshotStore()
        val codec = EventCodec()
        val writer = Kernel(log)
        writer.commit(command(CreateCart("ahoo"), requestId = "create"))
        val second = writer.execute(cartModel, command(AddItem("apple", 1))) as CommandOutcome.Committed
        writer.commit(command(AddItem("apple", 1), requestId = "third"))
        snapshots.save(codec.snapshotOf(cartModel, ref, second.state as CartState, 2, second.stream.scope, 0, 0))
        log.reset()
        // Versions 2 and 3 are the tail, version 1 is the earlier request: all three fit two-plus-one.
        val kernel = kernel(recentRequests = 3, snapshots = snapshots)
        kernel.execute(cartModel, command(CreateCart("ahoo"), requestId = "create"))
            .assert().isInstanceOf(CommandOutcome.AlreadyCommitted::class.java)
        log.tails.assert().containsExactly(2 to 4)
        log.lookups.assert().isZero()
        states.states.getValue(ref).requests!!.complete.assert().isTrue()
        // With room for two, the window is full: truncated, so the creation's retry is looked up.
        states.states.clear()
        log.reset()
        val small = kernel(recentRequests = 2, snapshots = snapshots)
        small.execute(cartModel, command(CreateCart("ahoo"), requestId = "create"))
            .assert().isInstanceOf(CommandOutcome.AlreadyCommitted::class.java)
        log.tails.assert().containsExactly(2 to 3)
        log.lookups.assert().isEqualTo(1)
        states.states.getValue(ref).requests!!.complete.assert().isFalse()
    }

    @Test
    fun `the lease a command runs under is told apart, and a cache drops nothing unless it keeps partitions`() {
        ExecutionLease.HELD.held.assert().isTrue()
        ExecutionLease.NOT_HELD.held.assert().isFalse()
        ExecutionLease.HELD.toString().assert().isEqualTo("ExecutionLease(held)")
        ExecutionLease.NOT_HELD.toString().assert().isEqualTo("ExecutionLease(not held)")
        StateCache.None.invalidate(0, 1)
        states.put(ref, CachedState(CartState(ref.id), 1))
        states.invalidate(0, 1)
        states.states.assert().containsKey(ref)
    }

    @Test
    fun `an append the log refuses by throwing is compensated, and its failure propagates`(): Unit = runTest {
        // A backend throws only what surely wrote nothing: the store unreachable before the append was sent.
        val compensated = mutableListOf<String>()
        val model = aggregate<CartState>("example", "cart") {
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command ->
                onAppendFailure { compensated += command.owner }
                Decision.events(CartCreated(command.owner))
            }
            evolve<CartCreated> { owner = it.owner }
        }
        val failing = object : EventLog by log {
            override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
                throw IOException("The store is unreachable.")
        }
        val outcome = runCatching { Kernel(failing).execute(model, command(CreateCart("ahoo"))) }
        outcome.exceptionOrNull().assert().isInstanceOf(IOException::class.java)
        compensated.assert().containsExactly("ahoo")
    }

    @Test
    fun `without recent requests every command looks its request id up first, as D46 did`(): Unit = runTest {
        val snapshots = InMemorySnapshotStore()
        val kernel = kernel(recentRequests = 0, snapshots = snapshots)
        val created = kernel.execute(cartModel, command(CreateCart("ahoo"))) as CommandOutcome.Committed
        snapshots.save(
            EventCodec().snapshotOf(cartModel, ref, created.state as CartState, 1, created.stream.scope, 0, 0)
        )
        states.states.clear()
        kernel.commit(command(AddItem("apple", 1)))
        log.lookups.assert().isEqualTo(2)
        log.tails.assert().containsExactly(1 to 0)
        states.states.getValue(ref).requests.assert().isNull()
    }
}
