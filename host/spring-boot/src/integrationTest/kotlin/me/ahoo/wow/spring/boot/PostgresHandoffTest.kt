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

package me.ahoo.wow.spring.boot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * The hand-over on a PostgreSQL log appended to without a queue, PostgreSQL's preset (work package OV-2, decision
 * D263): appends running side by side commit in sequence order but return in any order, and an append's return may
 * come long after its commit while the node's dispatcher is starved. A tail waits for those appends, not a timer, so
 * the node's own commits never make it read the store; another node's append leaves a gap read at once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresHandoffTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = POOL_SIZE
        },
    )
    private val contexts = AtomicInteger()
    private val type get() = AggregateType("handoff${contexts.get()}", "account")

    private fun store(): PostgresEventLog =
        PostgresEventLog(dataSource, "handoff${contexts.incrementAndGet()}", PARTITIONS).also { it.createSchema() }

    @AfterAll
    fun close() {
        dataSource.close()
        postgres.stop()
    }

    private fun refsOfPartitionZero(count: Int): List<AggregateRef> = generateSequence(0) { it + 1 }
        .map { type.ref("tenant", "account-$it") }
        .filter { Partitioner.partitionOf(it, PARTITIONS) == 0 }
        .take(count)
        .toList()

    private fun stream(ref: AggregateRef, version: Int) = EventStreamRecord(
        ref = ref,
        version = version,
        commandId = "command-${ref.id}-$version",
        requestId = "${ref.id}-$version",
        commandType = "${ref.type}.Deposit",
        commitTime = 1L,
        events = listOf(EventRecord("${ref.type}.Deposited", 1, """{"amount":$version}""")),
        scope = Scope.NONE,
    )

    /** Collects partition 0's tail of [log] until [count] entries, checking they come in sequence order. */
    private fun CoroutineScope.collect(log: EventLog, count: Long, seen: AtomicLong) = launch(Dispatchers.Default) {
        log.tail(0).takeWhile { entry ->
            entry.position.sequence.assert().isEqualTo(seen.incrementAndGet())
            seen.get() < count
        }.collect()
    }

    @Test
    fun `appends without a queue, some returning long after their commit, never read the store`(): Unit =
        runBlocking {
            val reads = Reads()
            val log = HandoffLog(LateReturns(store(), every = 25), type.context, observer = reads)
            val refs = refsOfPartitionZero(WRITERS)
            val seen = AtomicLong()
            val tail = collect(log, (WRITERS * ROUNDS).toLong(), seen)
            // The tail has read its head: whatever is appended next is handed over to it.
            delay(500)
            refs.map { ref ->
                launch(Dispatchers.Default) { (1..ROUNDS).forEach { log.append(stream(ref, it), null).committed() } }
            }.joinAll()
            withTimeout(30.seconds) { tail.join() }

            seen.get().assert().isEqualTo((WRITERS * ROUNDS).toLong())
            reads.reasons.assert().isEmpty()
            log.storeReads.assert().isEqualTo(0L)
        }

    @Test
    fun `another node's append among the node's own leaves a gap read from the store at once`(): Unit =
        runBlocking {
            val reads = Reads()
            val store = store()
            val log = HandoffLog(store, type.context, observer = reads)
            val (own, other) = refsOfPartitionZero(2)
            val seen = AtomicLong()
            val tail = collect(log, 3, seen)
            delay(500)
            log.append(stream(own, 1), null).committed()
            store.append(stream(other, 1), null).committed()
            log.append(stream(own, 2), null).committed()
            // Read well within the gap wait (1 s): no append of this node could still hand the missing one over.
            withTimeout(800) { tail.join() }

            reads.reasons.assert().containsExactly(HandoffLog.GAP)
            tail.cancelAndJoin()
        }

    private fun AppendOutcome.committed() = assert().isInstanceOf(AppendOutcome.Committed::class.java)

    /** A log one in [every] of whose appends returns 300 ms after its commit, as under a starved dispatcher. */
    private class LateReturns(private val delegate: EventLog, private val every: Int) : EventLog by delegate {
        private val calls = AtomicInteger()

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome =
            delegate.append(stream, transport).also {
                if (calls.incrementAndGet() % every == 0) {
                    delay(LATE_MILLIS)
                }
            }
    }

    private class Reads : Observer {
        val reasons = CopyOnWriteArrayList<String>()

        override fun observed(event: ObservedEvent) {
            when (event) {
                is ObservedEvent.TailRead -> reasons += event.reason
                else -> Unit
            }
        }
    }

    private companion object {
        const val PARTITIONS = 4
        const val POOL_SIZE = 8
        const val WRITERS = 64
        const val ROUNDS = 20
        const val LATE_MILLIS = 300L
    }
}
