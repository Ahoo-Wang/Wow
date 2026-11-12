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

package me.ahoo.wow.benchmark.transfer.raw

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RawRunTest {
    @Test
    fun `a flush's units are groups of the statement's size`() {
        groupsOf(10, 4).assert().containsExactly(4, 4, 2)
        groupsOf(14, 9).assert().containsExactly(9, 5)
        groupsOf(3, 4).assert().containsExactly(3)
        assertThrows<IllegalArgumentException> { groupsOf(0, 4) }
    }

    @Test
    fun `a percentile is of the sorted values, 0 without any`() {
        percentile(longArrayOf(5, 1, 3, 2, 4), 50.0).assert().isEqualTo(3)
        percentile(LongArray(100) { it.toLong() }, 99.0).assert().isEqualTo(99)
        percentile(LongArray(0), 50.0).assert().isEqualTo(0)
    }

    @Test
    fun `a cgroup's CPU is its cpu stat's usage in microseconds`() {
        cgroupUsageMicros("usage_usec 123456\nuser_usec 100000\nsystem_usec 23456\n").assert().isEqualTo(123456)
        cgroupUsageMicros("user_usec 1\n").assert().isNull()
    }

    @Test
    fun `each worker owns its own aggregates, taken in turn, their versions its own`() {
        val first = Aggregates(10, 0, 3)
        List(4) { first.next().id }.assert().containsExactly("raw-0", "raw-3", "raw-6", "raw-9")
        Aggregates(10, 1, 3).take(2).map { it.id to it.version }.assert().containsExactly("raw-1" to 2, "raw-4" to 2)
        assertThrows<IllegalArgumentException> { Aggregates(2, 5, 6) }
    }

    @Test
    fun `the calibration passes within ten percent of Wow's own, and fails without either side`() {
        RawCalibration("walBytes", wow = 1300.0, raw = 1350.0).apply {
            passed.assert().isTrue()
            ratio!!.assert().isCloseTo(1.038, org.assertj.core.data.Offset.offset(0.001))
        }
        RawCalibration("walBytes", wow = 1300.0, raw = 1500.0).passed.assert().isFalse()
        RawCalibration("journalBytes", wow = null, raw = 900.0).apply {
            passed.assert().isFalse()
            ratio.assert().isNull()
        }
    }

    @Test
    fun `the settings default to each store's L1 shape and refuse too few aggregates`() {
        RawSettings.of(emptyMap(), "postgres").apply {
            logBatch.assert().isEqualTo(10)
            snapshotBatch.assert().isEqualTo(4)
            batches.assert().containsExactly(4, 10, 25)
            connections.assert().containsExactly(16, 64, 256)
            keeps("m1-4-state").assert().isTrue()
        }
        RawSettings.of(mapOf("cases" to "m0,m1"), "mongo").apply {
            logBatch.assert().isEqualTo(14)
            snapshotBatch.assert().isEqualTo(9)
            keeps("m1-25-state-4k").assert().isTrue()
            keeps("m3-4-state").assert().isFalse()
        }
        assertThrows<IllegalArgumentException> {
            RawSettings.of(mapOf("aggregates" to "100", "connections" to "64"), "postgres")
        }
    }

    @Test
    fun `raw json leads with the calibration, then the settings, the store and each step per unit`() {
        val step = RawStep(
            case = RawCase("wow-today", "command", 10, "today: the calibration"),
            connections = 4,
            rate = null,
            seconds = 2.0,
            units = 1000,
            flushes = 100,
            failures = 0,
            p50FlushMs = 1.5,
            p99FlushMs = 9.0,
            storeCpuMsPerUnit = null,
            perUnit = mapOf("walBytes" to 1300.0),
            ratios = mapOf("hotShare" to 0.75),
        )
        step.unitsPerSecond.assert().isEqualTo(500.0)
        val json = rawJson(
            RawCalibration("walBytes", 1290.0, 1300.0),
            mapOf("backend" to "postgres"),
            mapOf("server_version" to "17.6", "synchronous_commit" to "on"),
            listOf(step),
        )
        json.assert().startsWith("{\"schema\":\"wow-e2e-bench/raw/1\",\"calibration\":{\"metric\":\"walBytes\"")
        json.assert().contains(
            "\"passed\":true",
            "\"store\":{\"server_version\":\"17.6\",\"synchronous_commit\":\"on\"}",
            "\"case\":\"wow-today\"",
            "\"unitsPerSecond\":500.0",
            "\"storeCpuMsPerUnit\":null",
            "\"hotShare\":0.75",
        )
    }
}
