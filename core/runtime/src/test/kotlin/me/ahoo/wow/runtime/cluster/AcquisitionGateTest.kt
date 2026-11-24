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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.cluster.AcquisitionGate.Admission
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The acquisitions of one node on one lease store while the store fails (#6675): windows, probes and the way out. */
class AcquisitionGateTest {
    private val time = TestTimeSource()

    /** Draws the top of every window, so a window lasts exactly its bound. */
    private val top = object : Random() {
        override fun nextBits(bitCount: Int): Int = -1

        override fun nextDouble(): Double = 1.0
    }

    private val gate = AcquisitionGate("node-1", "shop-service", 1.seconds, 10.seconds, time, top)

    private fun fail() = gate.failed("an acquisition", IllegalStateException("down"))

    @Test
    fun `while the store answers every acquisition is let through`() {
        repeat(3) { gate.admit().assert().isEqualTo(Admission.FREE) }
        gate.open.assert().isFalse()
    }

    @Test
    fun `a failure holds acquisitions back for its window, then lets one through at a time`() {
        fail()
        gate.open.assert().isTrue()
        gate.admit().assert().isNull()
        time += 999.milliseconds
        gate.admit().assert().isNull()
        time += 1.milliseconds
        gate.admit().assert().isEqualTo(Admission.PROBE)
        // The probe is out: no second one until it is recorded.
        gate.admit().assert().isNull()
    }

    @Test
    fun `each failure doubles the window up to the cap`() {
        fail()
        listOf(1, 2, 4, 8, 10, 10).forEach { seconds ->
            time += (seconds * 1_000 - 1).milliseconds
            gate.admit().assert().describedAs("$seconds s").isNull()
            time += 1.milliseconds
            gate.admit().assert().describedAs("$seconds s").isEqualTo(Admission.PROBE)
            // The probe fails too, which widens the window again.
            runBlocking {
                assertThrows<IllegalStateException> {
                    gate.recorded("a probe", Admission.PROBE) { error("down") }
                }
            }
        }
    }

    @Test
    fun `a call the store answers closes it, an acquisition held by another node among them`(): Unit = runBlocking {
        fail()
        time += 1.seconds
        val probe = gate.admit()
        probe.assert().isEqualTo(Admission.PROBE)
        // Another node holds the partition: the store answered, null.
        val held: String? = gate.recorded("a probe", probe!!) { null }
        held.assert().isNull()
        gate.open.assert().isFalse()
        gate.admit().assert().isEqualTo(Admission.FREE)
    }

    @Test
    fun `a heartbeat or a renewal the store answers closes it as an acquisition does`(): Unit = runBlocking {
        fail()
        gate.recorded("a heartbeat") { Unit }
        gate.open.assert().isFalse()
    }

    @Test
    fun `a probe cancelled is freed for the next, the window unchanged`(): Unit = runBlocking {
        fail()
        time += 1.seconds
        val probe = gate.admit()!!
        assertThrows<CancellationException> {
            gate.recorded("a probe", probe) { throw CancellationException("stopped") }
        }
        gate.open.assert().isTrue()
        gate.admit().assert().isEqualTo(Admission.PROBE)
    }

    @Test
    fun `leases left unrenewed open it without a call having failed`() {
        gate.failed("the renewals of 3 leases")
        gate.open.assert().isTrue()
        gate.admit().assert().isNull()
    }

    @Test
    fun `the jitter draws the window below its bound`() {
        val half = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0

            override fun nextDouble(): Double = 0.5
        }
        val jittered = AcquisitionGate("node-1", "shop-service", 4.seconds, 10.seconds, time, half)
        jittered.failed("an acquisition")
        time += 1_999.milliseconds
        jittered.admit().assert().isNull()
        time += 1.milliseconds
        jittered.admit().assert().isEqualTo(Admission.PROBE)
    }
}
