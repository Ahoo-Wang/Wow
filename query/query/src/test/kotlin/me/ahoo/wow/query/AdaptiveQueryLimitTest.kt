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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.admission.AdaptiveLimitOptions
import me.ahoo.wow.query.admission.AdaptiveQueryLimit
import me.ahoo.wow.query.admission.AdaptiveQueryLimits
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.error.QueryBusyException
import me.ahoo.wow.spi.store.WritePressure
import me.ahoo.wow.spi.store.WritePressureSample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The adaptive limit of a store's query bulkhead (decision D252): additive increase while the writes are calm (to the
 * start, or to the ceiling while the limit holds queries back), multiplicative decrease after consecutive pressing
 * periods in which the queries contend, down to the floor; evaluated period by period, so each test drives the clock
 * by calling [AdaptiveQueryLimit.evaluate].
 */
class AdaptiveQueryLimitTest {
    /** A write pressure whose next sample is [next]. */
    private class Pressure : WritePressure {
        var next: WritePressureSample = CALM

        override fun sample(): WritePressureSample = next
    }

    private suspend fun CoroutineScope.hold(bulkhead: QueryBulkhead, gate: CompletableDeferred<Unit>): Deferred<Unit> {
        val entered = CompletableDeferred<Unit>()
        return async(Dispatchers.Default) {
            bulkhead.run {
                entered.complete(Unit)
                gate.await()
            }
        }.also { entered.await() }
    }

    /** One query that finds no permit free and is refused at once: the limit held it back. */
    private suspend fun contend(bulkhead: QueryBulkhead) {
        runCatching { bulkhead.run { } }
    }

    /** Queries up to the limit of [bulkhead], whose `maxWait` is zero, and one refused beyond it; then they end. */
    private fun crowd(bulkhead: QueryBulkhead) = runBlocking {
        var taken = 0
        while (runCatching { bulkhead.acquire() }.isSuccess) taken++
        repeat(taken) { bulkhead.release() }
    }

    /** One period of [limit] in which its queries contend. */
    private fun AdaptiveQueryLimit.contended() {
        crowd(bulkhead)
        evaluate()
    }

    @Test
    fun `two pressing periods in a row with contending queries should halve the limit, one should not`() {
        val pressure = Pressure()
        val bulkhead = QueryBulkhead("main", 8, maxWait = Duration.ZERO, ceiling = 16)
        val limit = AdaptiveQueryLimit(bulkhead, pressure, AdaptiveLimitOptions(floor = 2))
        pressure.next = slow()
        limit.contended()
        limit.pressed.assert().isTrue()
        bulkhead.limit.assert().isEqualTo(8)
        limit.contended()
        bulkhead.limit.assert().isEqualTo(4)
        limit.decreases.assert().isEqualTo(1)
        // The count starts over: the next halving needs two more pressing periods.
        limit.contended()
        bulkhead.limit.assert().isEqualTo(4)
        limit.contended()
        bulkhead.limit.assert().isEqualTo(2)
        limit.contended()
        limit.contended()
        bulkhead.limit.assert().isEqualTo(2)
        limit.decreases.assert().isEqualTo(2)
        limit.lastSample.assert().isSameAs(pressure.next)
    }

    @Test
    fun `a calm period between pressing ones should reset the count`() {
        val pressure = Pressure()
        val bulkhead = QueryBulkhead("main", 8, maxWait = Duration.ZERO)
        val limit = AdaptiveQueryLimit(bulkhead, pressure)
        pressure.next = slow()
        limit.contended()
        pressure.next = CALM
        limit.contended()
        limit.pressed.assert().isFalse()
        pressure.next = slow()
        limit.contended()
        bulkhead.limit.assert().isEqualTo(8)
        limit.contended()
        bulkhead.limit.assert().isEqualTo(4)
    }

    @Test
    fun `pressing periods in which no query contends should leave the limit`() {
        // A store that only writes for a while, slower than its baseline: its queries have nothing to give way.
        val pressure = Pressure().apply { next = slow() }
        val bulkhead = QueryBulkhead("main", 25, maxWait = Duration.ZERO, ceiling = 50)
        val limit = AdaptiveQueryLimit(bulkhead, pressure)
        repeat(60) { limit.evaluate() }
        limit.pressed.assert().isTrue()
        bulkhead.limit.assert().isEqualTo(25)
        limit.decreases.assert().isZero()
        // A pressing period without contention starts the count over: one with it alone does not halve.
        limit.contended()
        bulkhead.limit.assert().isEqualTo(25)
        limit.evaluate()
        limit.contended()
        bulkhead.limit.assert().isEqualTo(25)
        limit.contended()
        bulkhead.limit.assert().isEqualTo(12)
    }

    @Test
    fun `a lowered limit should climb back to its start once the writes are calm, with no query, and above it on demand`() {
        val pressure = Pressure()
        val bulkhead = QueryBulkhead("main", 8, maxWait = Duration.ZERO, ceiling = 12)
        val limit = AdaptiveQueryLimit(bulkhead, pressure)
        pressure.next = slow()
        repeat(6) { limit.contended() }
        bulkhead.limit.assert().isEqualTo(1)
        // The writes calm down and no query comes: one a period, back to the start and no further.
        pressure.next = CALM
        repeat(6) { limit.evaluate() }
        bulkhead.limit.assert().isEqualTo(7)
        repeat(10) { limit.evaluate() }
        bulkhead.limit.assert().isEqualTo(8)
        // Queries the limit holds back take it above its start, up to the ceiling.
        repeat(10) { limit.contended() }
        bulkhead.limit.assert().isEqualTo(12)
        // A limit above its start that nothing holds back stays: it is lowered only under pressure.
        repeat(5) { limit.evaluate() }
        bulkhead.limit.assert().isEqualTo(12)
    }

    @Test
    fun `contending queries at a steady latency over a quiet baseline should not saw the limit`() {
        // The store's appends take 15 to 30 ms a period over a 6 ms baseline, with queries at the limit throughout:
        // a busy store, but none of these periods is 20 ms slower than its normal latency.
        val pressure = Pressure()
        val bulkhead = QueryBulkhead("main", 4, maxWait = Duration.ZERO, ceiling = 16)
        val limit = AdaptiveQueryLimit(bulkhead, pressure)
        listOf(15L, 22L, 18L, 25L, 17L, 21L).let { millis ->
            repeat(60) {
                pressure.next = WritePressureSample(50, millis[it % millis.size].milliseconds, 6.milliseconds, 0)
                limit.contended()
            }
        }
        limit.decreases.assert().isZero()
        bulkhead.limit.assert().isEqualTo(16)
    }

    @Test
    fun `a calm period that held a query back should raise the limit by one, up to the ceiling`(): Unit = runBlocking {
        val pressure = Pressure()
        val bulkhead = QueryBulkhead("main", 1, maxWait = Duration.ZERO, ceiling = 2)
        val limit = AdaptiveQueryLimit(bulkhead, pressure)
        // Nobody held back: the limit stays.
        limit.evaluate()
        bulkhead.limit.assert().isEqualTo(1)
        val gate = CompletableDeferred<Unit>()
        val first = hold(bulkhead, gate)
        contend(bulkhead)
        limit.evaluate()
        bulkhead.limit.assert().isEqualTo(2)
        // The raised limit admits a second query at once.
        val second = hold(bulkhead, gate)
        bulkhead.running.assert().isEqualTo(2)
        contend(bulkhead)
        limit.evaluate()
        bulkhead.limit.assert().isEqualTo(2)
        gate.complete(Unit)
        first.await()
        second.await()
        bulkhead.running.assert().isZero()
    }

    @Test
    fun `one period should raise the limit by one however many queries it held back`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1, maxWait = Duration.ZERO, ceiling = 8)
        val limit = AdaptiveQueryLimit(bulkhead, Pressure())
        val gate = CompletableDeferred<Unit>()
        val held = hold(bulkhead, gate)
        repeat(10) { contend(bulkhead) }
        limit.evaluate()
        bulkhead.limit.assert().isEqualTo(2)
        // Its contention was taken with it: the next period holds nobody back, and the limit stays.
        limit.evaluate()
        bulkhead.limit.assert().isEqualTo(2)
        gate.complete(Unit)
        held.await()
    }

    @Test
    fun `a pressing period should not raise the limit however many queries wait`(): Unit = runBlocking {
        val pressure = Pressure()
        val bulkhead = QueryBulkhead("main", 1, maxWait = Duration.ZERO, ceiling = 4)
        val limit = AdaptiveQueryLimit(bulkhead, pressure, AdaptiveLimitOptions(periods = 3))
        val gate = CompletableDeferred<Unit>()
        val held = hold(bulkhead, gate)
        pressure.next = WritePressureSample(10, 1.milliseconds, 1.milliseconds, waitingForConnection = 1)
        contend(bulkhead)
        limit.evaluate()
        limit.pressed.assert().isTrue()
        bulkhead.limit.assert().isEqualTo(1)
        gate.complete(Unit)
        held.await()
    }

    @Test
    fun `latency should press only above the factor of its baseline and the least rise, with both known`() {
        val pressure = Pressure()
        val limit = AdaptiveQueryLimit(QueryBulkhead("main", 4), pressure)
        fun presses(sample: WritePressureSample): Boolean {
            pressure.next = sample
            limit.evaluate()
            return limit.pressed
        }
        presses(WritePressureSample(10, 41.milliseconds, 20.milliseconds, 0)).assert().isTrue()
        // Above the factor, but only 11 ms above the baseline.
        presses(WritePressureSample(10, 31.milliseconds, 20.milliseconds, 0)).assert().isFalse()
        // 20 ms above the baseline, but not above the factor.
        presses(WritePressureSample(10, 60.milliseconds, 40.milliseconds, 0)).assert().isFalse()
        presses(WritePressureSample(10, 28.milliseconds, 8.milliseconds, 0)).assert().isTrue()
        presses(WritePressureSample(10, 27.milliseconds, 8.milliseconds, 0)).assert().isFalse()
        presses(WritePressureSample(10, null, 10.milliseconds, 0)).assert().isFalse()
        presses(WritePressureSample(10, 50.milliseconds, null, 0)).assert().isFalse()
        presses(WritePressureSample.NONE).assert().isFalse()
        presses(WritePressureSample(0, null, null, 2)).assert().isTrue()
    }

    @Test
    fun `a store of millisecond appends should not press on a millisecond of noise`() {
        // PostgreSQL on a near network: a 1 ms baseline, a 2 ms period; twice the baseline, far below the least rise.
        val pressure = Pressure().apply { next = WritePressureSample(200, 2.milliseconds, 1.milliseconds, 0) }
        val bulkhead = QueryBulkhead("main", 4, maxWait = Duration.ZERO)
        val limit = AdaptiveQueryLimit(bulkhead, pressure)
        repeat(10) { limit.contended() }
        limit.pressed.assert().isFalse()
        limit.decreases.assert().isZero()
        bulkhead.limit.assert().isEqualTo(4)
    }

    @Test
    fun `lowering the limit should cancel no running query and keep their permits back as they end`(): Unit =
        runBlocking {
            val bulkhead = QueryBulkhead("main", 3, maxWait = Duration.ZERO, ceiling = 3)
            val gates = List(3) { CompletableDeferred<Unit>() }
            val held = gates.map { hold(bulkhead, it) }
            bulkhead.limit(1)
            bulkhead.limit.assert().isEqualTo(1)
            bulkhead.running.assert().isEqualTo(3)
            gates[0].complete(Unit)
            held[0].await()
            gates[1].complete(Unit)
            held[1].await()
            bulkhead.running.assert().isEqualTo(1)
            // Two of the three permits were kept back: the one left runs, nothing else does.
            runCatching { bulkhead.run { } }.exceptionOrNull().assert().isInstanceOf(QueryBusyException::class.java)
            (runCatching { bulkhead.run { } }.exceptionOrNull() as QueryBusyException).maxConcurrent
                .assert().isEqualTo(1)
            bulkhead.takeContended().assert().isEqualTo(2)
            bulkhead.takeContended().assert().isZero()
            gates[2].complete(Unit)
            held[2].await()
            bulkhead.running.assert().isZero()
            bulkhead.run { "one" }.assert().isEqualTo("one")
        }

    @Test
    fun `raising the limit while permits are owed should forgive them before releasing any`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 4, maxWait = Duration.ZERO, ceiling = 4)
        val gates = List(4) { CompletableDeferred<Unit>() }
        val held = gates.map { hold(bulkhead, it) }
        bulkhead.limit(1)
        bulkhead.limit(3)
        gates.take(3).forEachIndexed { index, gate ->
            gate.complete(Unit)
            held[index].await()
        }
        // Of the three that ended, one permit was kept back: three run under the limit 3 again.
        val more = List(3) { CompletableDeferred<Unit>() }
        val again = more.take(2).map { hold(bulkhead, it) }
        bulkhead.running.assert().isEqualTo(3)
        runCatching { bulkhead.run { } }.exceptionOrNull().assert().isInstanceOf(QueryBusyException::class.java)
        bulkhead.limit(4)
        val last = hold(bulkhead, more[2])
        bulkhead.running.assert().isEqualTo(4)
        gates[3].complete(Unit)
        more.forEach { it.complete(Unit) }
        (again + held[3] + last).forEach { it.await() }
        bulkhead.running.assert().isZero()
    }

    @Test
    fun `a caller reading in chunks should take its permit again under a lowered limit`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 2, maxWait = Duration.ZERO)
        bulkhead.acquire()
        bulkhead.acquire()
        bulkhead.limit(1)
        bulkhead.release()
        bulkhead.release()
        bulkhead.acquire()
        runCatching { bulkhead.acquire() }.exceptionOrNull().assert().isInstanceOf(QueryBusyException::class.java)
        bulkhead.release()
        bulkhead.running.assert().isZero()
    }

    @Test
    fun `a limit outside one to the ceiling, or a ceiling below the start, should be refused`() {
        val bulkhead = QueryBulkhead("main", 2, ceiling = 4)
        assertThrows<IllegalArgumentException> { bulkhead.limit(0) }
        assertThrows<IllegalArgumentException> { bulkhead.limit(5) }
        assertThrows<IllegalArgumentException> { QueryBulkhead("main", 3, ceiling = 2) }
        bulkhead.toString().assert().isEqualTo(
            "QueryBulkhead(store=main, maxConcurrent=2, ceiling=4, maxWait=200ms, maxQueued=8)"
        )
        val limit = AdaptiveQueryLimit(QueryBulkhead("small", 1), Pressure(), AdaptiveLimitOptions(floor = 3))
        limit.floor.assert().isEqualTo(1)
        limit.toString().assert().isEqualTo("AdaptiveQueryLimit(store=small, limit=1, floor=1, ceiling=1)")
    }

    @Test
    fun `options out of range should be refused`() {
        assertThrows<IllegalArgumentException> { AdaptiveLimitOptions(floor = 0) }
        assertThrows<IllegalArgumentException> { AdaptiveLimitOptions(interval = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { AdaptiveLimitOptions(latencyFactor = 1.0) }
        assertThrows<IllegalArgumentException> { AdaptiveLimitOptions(minRise = (-1).milliseconds) }
        assertThrows<IllegalArgumentException> { AdaptiveLimitOptions(connectionWaits = 0) }
        assertThrows<IllegalArgumentException> { AdaptiveLimitOptions(periods = 0) }
        AdaptiveLimitOptions.DEFAULT.toString().assert().isEqualTo(
            "AdaptiveLimitOptions(floor=1, interval=1s, latencyFactor=1.5, minRise=20ms, connectionWaits=1, periods=2)"
        )
        assertThrows<IllegalArgumentException> { AdaptiveQueryLimits(emptyList(), Duration.ZERO) }
    }

    @Test
    fun `the limits should be evaluated every interval from start to close, a failing one logged`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val bulkhead = QueryBulkhead("main", 8, maxWait = Duration.ZERO)
        // Each period's queries contend with the writes: the pressure crowds the bulkhead as it is sampled.
        val pressure = WritePressure {
            crowd(bulkhead)
            slow()
        }
        val failing = AdaptiveQueryLimit(QueryBulkhead("broken", 2), WritePressure { error("no sample") })
        val limits = AdaptiveQueryLimits(listOf(failing, AdaptiveQueryLimit(bulkhead, pressure)), 1.seconds, dispatcher)
        limits.start()
        limits.start()
        scope.runCurrent()
        bulkhead.limit.assert().isEqualTo(8)
        scope.advanceTimeBy(2.seconds + 1.milliseconds)
        bulkhead.limit.assert().isEqualTo(4)
        limits.close()
        scope.advanceTimeBy(10.seconds)
        bulkhead.limit.assert().isEqualTo(4)
        // Without limits there is nothing to evaluate.
        AdaptiveQueryLimits(emptyList(), 1.seconds, dispatcher).apply {
            start()
            close()
        }
    }

    @Test
    fun `the pressure without a signal should be calm`() {
        WritePressure.NONE.sample().assert().isSameAs(WritePressureSample.NONE)
        WritePressureSample.NONE.waiting(3).waitingForConnection.assert().isEqualTo(3)
        WritePressureSample(1, 2.milliseconds, null, 0).toString().assert().isEqualTo(
            "WritePressureSample(appends=1, appendP90=2ms, baseline=null, waitingForConnection=0)"
        )
    }

    private companion object {
        val CALM = WritePressureSample(10, 1.milliseconds, 1.milliseconds, 0)

        fun slow() = WritePressureSample(10, 40.milliseconds, 10.milliseconds, 0)
    }
}
