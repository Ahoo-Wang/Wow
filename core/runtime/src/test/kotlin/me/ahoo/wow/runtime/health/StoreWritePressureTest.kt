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
        // A slower period moves the baseline up by a fiftieth of the gap: 9 + 50 * 0.02.
        pressure.period(*LongArray(10) { 9 })
        val calm = pressure.sample()
        calm.baseline.assert().isEqualTo(10.milliseconds)
        // A faster one moves it down by a fifth: 10 - 1 * 0.2.
        pressure.sample().baseline.assert().isEqualTo(9800.milliseconds / 1000)
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
