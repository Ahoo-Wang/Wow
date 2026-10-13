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
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import org.junit.jupiter.api.Test

/** A state stays cached only through attempts that leave it valid ([StateCache]). */
class KernelStateCacheTest {
    data class Add(val amount: Int)
    data class Refuse(val reason: String)

    /** Rejected below a total of [atLeast], changes nothing from it on. */
    data class Check(val atLeast: Int)
    data class Added(val amount: Int)

    /** Mutable on purpose: evolving changes the cached instance itself. */
    class Counter(val id: String) {
        var total = 0
    }

    private val model = aggregate<Counter>("test", "counter") {
        initial { Counter(it.id) }
        decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
        decide<Refuse> { _, body -> Decision.reject("refused", body.reason) }
        decide<Check> { state, body ->
            if (state.total < body.atLeast) {
                Decision.reject(
                    "short",
                    "${state.total} < ${body.atLeast}"
                )
            } else {
                Decision.NoOp
            }
        }
        evolve<Added> { total += it.amount }
    }
    private val ref = model.type.ref("t", "c-1")

    /** Counts full reads, and can make the next append conflict as if another node had written. */
    private class CountingLog(private val delegate: EventLog = InMemoryEventLog()) : EventLog by delegate {
        var reads = 0
        var conflictNext = false

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            reads++
            return delegate.read(ref, fromVersion, toVersion)
        }

        override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome {
            if (conflictNext) {
                conflictNext = false
                return AppendOutcome.Conflict(stream.version)
            }
            return delegate.append(stream, transport)
        }
    }

    private class MapCache : StateCache {
        val states = HashMap<AggregateRef, CachedState>()

        override fun take(ref: AggregateRef): CachedState? = states.remove(ref)

        override fun put(ref: AggregateRef, state: CachedState) {
            states[ref] = state
        }
    }

    private val log = CountingLog()
    private val cache = MapCache()
    private val kernel = Kernel(log, states = cache)
    private var sequence = 0

    private suspend fun send(body: Any) = kernel.execute(model, Command("c-${++sequence}", "r-$sequence", ref, body))

    @Test
    fun `a committed state serves the next command without reading the log`() = runTest {
        send(Add(1))
        val reads = log.reads
        (send(Add(2)) as CommandOutcome.Committed).stream.version.assert().isEqualTo(2)
        log.reads.assert().isEqualTo(reads)
        (cache.states.getValue(ref).state as Counter).total.assert().isEqualTo(3)
        cache.states.getValue(ref).version.assert().isEqualTo(2)
    }

    @Test
    fun `a rejected command keeps the state cached`() = runTest {
        send(Add(1))
        send(Refuse("no"))
        cache.states.getValue(ref).version.assert().isEqualTo(1)
    }

    @Test
    fun `a conflict drops the evolved state and the retry reloads`() = runTest {
        send(Add(1))
        log.conflictNext = true
        val outcome = send(Add(2)) as CommandOutcome.Committed
        (outcome.state as Counter).total.assert().isEqualTo(3)
        outcome.stream.version.assert().isEqualTo(2)
    }

    @Test
    fun `a failing command leaves nothing cached`() = runTest {
        val failing = aggregate<Counter>("test", "counter") {
            initial { Counter(it.id) }
            decide<Add>(Creation.ALLOWED) { state, _ ->
                state.total = -1
                error("boom")
            }
        }
        runCatching { kernel.execute(failing, Command("c-x", "r-x", ref, Add(1))) }
        cache.states.containsKey(ref).assert().isFalse()
    }

    @Test
    fun `a rejection decided on a cached state behind the log is decided again on the log's state`() = runTest {
        send(Add(1))
        // Another node of the service commits to the aggregate (decision D71): this kernel's cache does not see it.
        Kernel(log).execute(model, Command("other-1", "other-r-1", ref, Add(4)))
        cache.states.getValue(ref).version.assert().isEqualTo(1)

        // Rejected on the cached state (total 1), the command is decided again on the log's (total 5): G32.
        send(Check(5)).assert().isEqualTo(CommandOutcome.Unchanged(2))
        cache.states.getValue(ref).version.assert().isEqualTo(2)
        (cache.states.getValue(ref).state as Counter).total.assert().isEqualTo(5)
    }

    @Test
    fun `an unchanged outcome decided on a cached state behind the log is decided again on the log's state`() =
        runTest {
            send(Add(5))
            Kernel(log).execute(model, Command("other-1", "other-r-1", ref, Add(-4)))

            val outcome = send(Check(5)) as CommandOutcome.Rejected
            outcome.rejection.message.assert().isEqualTo("1 < 5")
            cache.states.getValue(ref).version.assert().isEqualTo(2)
        }

    @Test
    fun `a kernel rejection on a cached state behind the log is checked too`() = runTest {
        send(Add(1))
        Kernel(log).execute(model, Command("other-1", "other-r-1", ref, Add(1)))
        val expected = Command("c-x", "r-x", ref, Add(1), expectedVersion = 2)
        (kernel.execute(model, expected) as CommandOutcome.Committed).stream.version.assert().isEqualTo(3)
    }

    @Test
    fun `a rejection on a cached state the log agrees with reads only the next version slot`() = runTest {
        send(Add(1))
        val reads = log.reads
        (send(Check(5)) is CommandOutcome.Rejected).assert().isTrue()
        log.reads.assert().isEqualTo(reads + 1)
        cache.states.getValue(ref).version.assert().isEqualTo(1)
    }
}
