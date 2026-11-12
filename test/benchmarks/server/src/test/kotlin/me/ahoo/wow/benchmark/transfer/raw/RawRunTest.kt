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
    fun `a flush's snapshots are one statement merged, else groups of the statement's size`() {
        snapshotGroups(10, 4, merged = false).assert().containsExactly(4, 4, 2)
        snapshotGroups(10, 4, merged = true).assert().containsExactly(10)
        snapshotGroups(3, 4, merged = false).assert().containsExactly(3)
        assertThrows<IllegalArgumentException> { snapshotGroups(0, 4, merged = false) }
    }

    @Test
    fun `the shapes say what each writes and how it commits`() {
        Shape.of("today").apply {
            writesSnapshots.assert().isTrue()
            mergesSnapshots.assert().isFalse()
            oneTransaction.assert().isFalse()
        }
        Shape.of("in-transaction-merged").apply {
            mergesSnapshots.assert().isTrue()
            oneTransaction.assert().isTrue()
        }
        Shape.LOG_ONLY.writesSnapshots.assert().isFalse()
        Shape.POINT_READ.writesSnapshots.assert().isFalse()
        assertThrows<IllegalStateException> { Shape.of("bulk") }
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
        val second = Aggregates(10, 1, 3)
        val ids = List(4) { first.next().id }
        ids.assert().containsExactly("raw-0", "raw-3", "raw-6", "raw-9")
        List(3) { second.next().id }.assert().containsExactly("raw-1", "raw-4", "raw-7")
        first.next().apply { version++ }.version.assert().isEqualTo(2)
        assertThrows<IllegalArgumentException> { Aggregates(2, 5, 6) }
    }

    @Test
    fun `the settings default to the L1 shape, each store's variants, and refuse too few aggregates`() {
        RawSettings.of(emptyMap(), "postgres").apply {
            variants.assert().containsExactly("100", "80")
            logBatch.assert().isEqualTo(10)
            snapshotBatch.assert().isEqualTo(4)
            connections.assert().containsExactly(1, 4, 16, 64, 256)
            variantsOf(Shape.LOG_ONLY).assert().containsExactly("100")
            variantsOf(Shape.TODAY).assert().containsExactly("100", "80")
        }
        RawSettings.of(emptyMap(), "mongo").variants.assert().containsExactly("pipeline", "set")
        assertThrows<IllegalArgumentException> {
            RawSettings.of(mapOf("aggregates" to "100", "connections" to "64"), "postgres")
        }
    }

    @Test
    fun `raw json holds the settings and each step per command`() {
        val step = RawStep(
            shape = "today",
            variant = "fillfactor-100",
            connections = 4,
            seconds = 2.0,
            commands = 1000,
            flushes = 100,
            failures = 0,
            p50FlushMs = 1.5,
            p99FlushMs = 9.0,
            storeCpuMsPerCommand = null,
            store = mapOf("walBytes" to 1300.0),
        )
        step.commandsPerSecond.assert().isEqualTo(500.0)
        val json = rawJson(mapOf("backend" to "postgres"), listOf(step))
        json.assert().startsWith("{\"schema\":\"wow-e2e-bench/raw/1\",\"backend\":\"postgres\",\"steps\":[")
        json.assert().contains("\"commandsPerSecond\":500.0", "\"storeCpuMsPerCommand\":null", "\"walBytes\":1300.0")
    }
}
