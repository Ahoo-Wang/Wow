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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.LogGroupBackends
import me.ahoo.wow.runtime.subscription.Appender
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** A store's append latency as the node measures it (decision D252). */
class StoreWritePressureTest {
    private fun StoreWritePressure.period(vararg millis: Long) {
        millis.forEach { record(it.milliseconds.inWholeNanoseconds) }
    }

    @Test
    fun `a period should report its p90 against the baseline of the periods before it`() {
        val pressure = StoreWritePressure("main")
        pressure.period(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val first = pressure.sample()
        first.appends.assert().isEqualTo(10)
        first.appendP90.assert().isEqualTo(9.milliseconds)
        first.baseline.assert().isNull()
        first.waitingForConnection.assert().isZero()

        pressure.period(*LongArray(10) { 59 })
        val slow = pressure.sample()
        slow.appendP90.assert().isEqualTo(59.milliseconds)
        slow.baseline.assert().isEqualTo(9.milliseconds)
        // The median of 9 and 59: the lower middle one.
        pressure.period(*LongArray(10) { 30 })
        pressure.sample().baseline.assert().isEqualTo(9.milliseconds)
        // Of 9, 30 and 59.
        pressure.sample().baseline.assert().isEqualTo(30.milliseconds)
    }

    @Test
    fun `the baseline should be the store's normal latency, not its quietest seconds nor a burst`() {
        val pressure = StoreWritePressure("main")
        // A minute at 20 ms with a quiet second (6 ms) and a slow one (100 ms) in every six.
        repeat(StoreWritePressure.BASELINE_PERIODS) {
            val millis = when (it % 6) {
                0 -> 6L
                3 -> 100L
                else -> 20L
            }
            pressure.period(*LongArray(10) { millis })
            pressure.sample()
        }
        pressure.sample().baseline.assert().isEqualTo(20.milliseconds)
    }

    @Test
    fun `a prime's slow seconds should leave the baseline once the steady periods outnumber them`() {
        val pressure = StoreWritePressure("main")
        fun periods(count: Int, millis: Long) = repeat(count) {
            pressure.period(*LongArray(10) { millis })
            pressure.sample()
        }
        periods(20, 150)
        pressure.sample().baseline.assert().isEqualTo(150.milliseconds)
        periods(21, 20)
        pressure.sample().baseline.assert().isEqualTo(20.milliseconds)
        // The window keeps the latest periods only: a minute later the prime is gone from it.
        periods(StoreWritePressure.BASELINE_PERIODS, 12)
        pressure.sample().baseline.assert().isEqualTo(12.milliseconds)
    }

    @Test
    fun `a period of too few appends should say nothing of the latency and leave the baseline`() {
        val pressure = StoreWritePressure("main")
        pressure.period(*LongArray(10) { 4 })
        pressure.sample()
        pressure.period(100, 100, 100, 100)
        val few = pressure.sample()
        few.appends.assert().isEqualTo(4)
        few.appendP90.assert().isNull()
        few.baseline.assert().isEqualTo(4.milliseconds)
        pressure.sample().appends.assert().isZero()
        pressure.toString().assert().isEqualTo("StoreWritePressure(main)")
    }

    @Test
    fun `a period should keep its latest latencies only`() {
        val pressure = StoreWritePressure("main")
        repeat(StoreWritePressure.MAX_SAMPLES) { pressure.record(1000.milliseconds.inWholeNanoseconds) }
        repeat(StoreWritePressure.MAX_SAMPLES) { pressure.record(1.milliseconds.inWholeNanoseconds) }
        val sample = pressure.sample()
        sample.appends.assert().isEqualTo(StoreWritePressure.MAX_SAMPLES)
        sample.appendP90.assert().isEqualTo(1.milliseconds)
    }

    @Test
    fun `the logs of a store should measure their appends into its pressure`(): Unit = runBlocking {
        val pressures = WritePressures()
        val backends = NodeStores.track(
            "bank",
            ContextBackends(
                InMemoryEventLog(2),
                InMemoryCheckpointStore(),
                InMemoryLeaseStore(),
                InMemoryFailureLedger(),
                groups = listOf(
                    LogGroupBackends(
                        "archive",
                        setOf(AggregateType("bank", "archive")),
                        InMemoryEventLog(2),
                        InMemoryCheckpointStore(),
                        InMemoryFailureLedger(),
                    ),
                ),
            ),
            pressures,
        )
        val appender = Appender(backends.log)
        repeat(StoreWritePressure.MIN_APPENDS) { appender.append("c-$it") }
        backends.log.appendAll(emptyList())
        pressures.find(ContextBackends.DEFAULT_STORE)!!.sample().appends
            .assert().isEqualTo(StoreWritePressure.MIN_APPENDS + 1)
        pressures.find("archive")!!.sample().appends.assert().isZero()
        pressures.find("other").assert().isNull()
        pressures.of("archive").assert().isSameAs(pressures.find("archive"))
    }
}
