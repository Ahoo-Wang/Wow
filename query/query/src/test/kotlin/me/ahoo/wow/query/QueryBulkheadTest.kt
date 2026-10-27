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

package me.ahoo.wow.query

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.error.QueryBusyException
import me.ahoo.wow.query.observe.started
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A store's query bulkhead (review R3.4-2, decision D247): a query beyond its bound waits briefly in a bounded FIFO
 * queue, and fails `wow.query.busy` when the queue is full or the wait runs out.
 */
class QueryBulkheadTest {
    /** A call holding one of [bulkhead]'s permits until [gate] completes, started once it holds it. */
    private suspend fun CoroutineScope.hold(
        bulkhead: QueryBulkhead,
        gate: CompletableDeferred<Unit>
    ): Deferred<String> {
        val entered = CompletableDeferred<Unit>()
        return async(Dispatchers.Default) {
            bulkhead.run {
                entered.complete(Unit)
                gate.await()
                "held"
            }
        }.also { entered.await() }
    }

    private suspend fun busy(block: suspend () -> Unit): QueryBusyException =
        runCatching { block() }.exceptionOrNull() as QueryBusyException

    @Test
    fun `a call beyond the bound should fail busy once its wait runs out, and pass once a permit is back`(): Unit =
        runBlocking {
            val bulkhead = QueryBulkhead("main", 1, maxWait = 50.milliseconds)
            val gate = CompletableDeferred<Unit>()
            val held = hold(bulkhead, gate)
            bulkhead.running.assert().isEqualTo(1)
            val started = TimeSource.Monotonic.markNow()
            val refused = busy { bulkhead.run { "second" } }
            started.elapsedNow().assert().isGreaterThanOrEqualTo(50.milliseconds)
            refused.errorCode.assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
            refused.store.assert().isEqualTo("main")
            refused.maxConcurrent.assert().isEqualTo(1)
            refused.errorMsg.assert().doesNotContain("main")
            bulkhead.queued.assert().isZero()
            gate.complete(Unit)
            held.await().assert().isEqualTo("held")
            bulkhead.running.assert().isZero()
            bulkhead.run { "again" }.assert().isEqualTo("again")
        }

    @Test
    fun `a burst within the bound and the queue should all pass when each query ends within the wait`(): Unit =
        runBlocking {
            val bulkhead = QueryBulkhead("main", 2, maxWait = 5.seconds)
            bulkhead.maxQueued.assert().isEqualTo(8)
            val answers = (1..10).map { index ->
                async(Dispatchers.Default) {
                    bulkhead.run {
                        delay(20)
                        index
                    }
                }
            }.awaitAll()
            answers.assert().containsExactlyElementsOf((1..10).toList())
            bulkhead.running.assert().isZero()
            bulkhead.queued.assert().isZero()
        }

    @Test
    fun `a call should be refused at once when the queue is full`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1, maxWait = 10.seconds, maxQueued = 1)
        val gate = CompletableDeferred<Unit>()
        val held = hold(bulkhead, gate)
        val queued = async(Dispatchers.Default) { bulkhead.run { "queued" } }
        while (bulkhead.queued == 0) yield()
        val started = TimeSource.Monotonic.markNow()
        busy { bulkhead.run { "third" } }.errorCode.assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
        started.elapsedNow().assert().isLessThan(1.seconds)
        gate.complete(Unit)
        held.await()
        queued.await().assert().isEqualTo("queued")
        bulkhead.queued.assert().isZero()
    }

    @Test
    fun `no wait or no queue should refuse at once`(): Unit = runBlocking {
        listOf(
            QueryBulkhead("main", 1, maxWait = Duration.ZERO),
            QueryBulkhead("main", 1, maxWait = 10.seconds, maxQueued = 0),
        ).forEach { bulkhead ->
            val gate = CompletableDeferred<Unit>()
            val held = hold(bulkhead, gate)
            val started = TimeSource.Monotonic.markNow()
            busy { bulkhead.run { "second" } }.errorCode.assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
            started.elapsedNow().assert().isLessThan(1.seconds)
            gate.complete(Unit)
            held.await()
        }
    }

    @Test
    fun `a caller cancelled while it waits should leave no queue slot and take no permit`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1, maxWait = 10.seconds, maxQueued = 1)
        val gate = CompletableDeferred<Unit>()
        val held = hold(bulkhead, gate)
        val waiting = async(Dispatchers.Default) { bulkhead.run { "never" } }
        while (bulkhead.queued == 0) yield()
        waiting.cancel()
        runCatching { waiting.await() }
        bulkhead.queued.assert().isZero()
        gate.complete(Unit)
        held.await()
        bulkhead.running.assert().isZero()
        // The permit and the queue slot are both back: one runs, one more may wait.
        val gate2 = CompletableDeferred<Unit>()
        val again = hold(bulkhead, gate2)
        bulkhead.running.assert().isEqualTo(1)
        val next = async(Dispatchers.Default) { bulkhead.run { "next" } }
        while (bulkhead.queued == 0) yield()
        gate2.complete(Unit)
        again.await()
        next.await().assert().isEqualTo("next")
        bulkhead.running.assert().isZero()
    }

    @Test
    fun `a failing call should give its permit back`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1)
        runCatching { bulkhead.run<Unit> { error("storage failed") } }.isFailure.assert().isTrue()
        bulkhead.running.assert().isZero()
    }

    @Test
    fun `a stream should hold its permit until it ends, its collector cancelled or not`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1, maxWait = 20.milliseconds)
        val gate = CompletableDeferred<Unit>()
        val rows = bulkhead.run(
            flow {
                emit(1)
                gate.await()
                emit(2)
            },
        )
        val collecting = async(Dispatchers.Default) { rows.toList() }
        while (bulkhead.running == 0) yield()
        runCatching { bulkhead.run(flow { emit(3) }).toList() }.exceptionOrNull()
            .assert().isInstanceOf(QueryBusyException::class.java)
        gate.complete(Unit)
        collecting.await().assert().containsExactly(1, 2)
        bulkhead.running.assert().isZero()
        // A collector that stops after the first row gives the permit back too.
        bulkhead.run(flow { repeat(3) { emit(it) } }).first().assert().isZero()
        bulkhead.running.assert().isZero()
        bulkhead.toString().assert()
            .isEqualTo("QueryBulkhead(store=main, maxConcurrent=1, maxWait=20ms, maxQueued=4)")
    }

    @Test
    fun `a bound below one, a negative wait or a negative queue should be refused`() {
        assertThrows<IllegalArgumentException> { QueryBulkhead("main", 0) }
        assertThrows<IllegalArgumentException> { QueryBulkhead("main", 1, maxWait = (-1).milliseconds) }
        assertThrows<IllegalArgumentException> { QueryBulkhead("main", 1, maxQueued = -1) }
        QueryBulkhead("main", 3).maxWait.assert().isEqualTo(QueryBulkhead.DEFAULT_MAX_WAIT)
    }
}
