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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    fun `each worker owns its own aggregates, taken in turn, their versions the case's`() {
        val versions = opened(10)
        val first = Aggregates(versions, 0, 3)
        List(4) { first.next().number }.assert().containsExactly(0, 3, 6, 9)
        Aggregates(versions, 1, 3).take(2).map { it.number to it.version }.assert().containsExactly(1 to 2, 4 to 2)
        Aggregates(versions, 1, 2).take(1).single().assert().isEqualTo(Aggregates.Command(1, 3))
        Aggregates.idOf(42).apply {
            length.assert().isEqualTo(36)
            assert().isEqualTo("raw-00000000000000000000000000000042")
        }
        assertThrows<IllegalArgumentException> { Aggregates(IntArray(2), 5, 6) }
    }

    @Test
    fun `a case's second step goes on from the versions its first wrote`() {
        val written = ConcurrentHashMap<Int, MutableList<Int>>()
        val versions = opened(40)
        fun step(connections: Int): Map<Int, List<Int>> {
            written.clear()
            runStep(CASE, connections, Pace(0 to 1), ContainerCpu(null), { emptyMap() }) { index ->
                val owned = Aggregates(versions, index, connections)
                fake { owned.take(2).forEach { written.computeIfAbsent(it.number) { ArrayList() }.add(it.version) } }
            }.read.assert().isTrue()
            return written.mapValues { (_, list) -> list.toList() }
        }
        val first = step(2)
        val second = step(4)
        second.keys.assert().isNotEmpty()
        for ((number, later) in second) {
            val highest = first[number]?.max() ?: continue
            later.min().assert().describedAs("aggregate $number").isGreaterThan(highest)
        }
    }

    @Test
    fun `a step with a refused or failed write is not read`() {
        val refused = runStep(CASE, 2, Pace(0 to 1), ContainerCpu(null), { emptyMap() }) {
            fake(Flushed(1, refused = 1)) {}
        }
        refused.refused.assert().isGreaterThan(0)
        refused.read.assert().isFalse()
        val calls = AtomicInteger()
        val failed = runStep(CASE, 2, Pace(0 to 1), ContainerCpu(null), { emptyMap() }) {
            fake(Flushed(1)) { check(calls.incrementAndGet() % 2 == 0) { "the store failed" } }
        }
        failed.units.assert().isGreaterThan(0)
        failed.failures.assert().isGreaterThan(0)
        failed.read.assert().isFalse()
    }

    @Test
    fun `the calibration passes when every check is within its bound and its step was read`() {
        val share = RawCheck.Bound(share = 0.1)
        RawCheck("walBytes", wow = 1300.0, raw = 1350.0, bound = share).passed.assert().isTrue()
        RawCheck("walBytes", wow = 1300.0, raw = 1500.0, bound = share).passed.assert().isFalse()
        RawCheck("journalBytes", wow = null, raw = 900.0, bound = share).passed.assert().isFalse()
        RawCheck("hotShare", wow = 0.9, raw = 0.85, bound = RawCheck.Bound(absolute = 0.1)).passed.assert().isTrue()
        val checks = listOf(RawCheck("commits", 0.35, 0.36, share))
        RawCalibration(checks).passed.assert().isTrue()
        RawCalibration(checks, read = false).passed.assert().isFalse()
        RawCalibration(emptyList()).passed.assert().isFalse()
        RawCalibration(checks + RawCheck("walRecords", 10.0, 13.0, share)).passed.assert().isFalse()
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
            case = RawCase("wow-today", "command", 10, "today"),
            connections = 4,
            rate = null,
            seconds = 2.0,
            units = 1000,
            flushes = 100,
            failures = 0,
            refused = 0,
            late = 0,
            p50FlushMs = 1.5,
            p99FlushMs = 9.0,
            storeCpuMsPerUnit = null,
            perUnit = mapOf("walBytes" to 1300.0),
            ratios = mapOf("hotShare" to 0.75),
            pool = 4,
        )
        step.unitsPerSecond.assert().isEqualTo(500.0)
        val calibration = RawCalibration(
            checks = listOf(RawCheck("walBytes", 1290.0, 1300.0, RawCheck.Bound(share = 0.1))),
            apart = listOf(RawCheck("walFpi", 0.1, 0.2, RawCheck.Bound())),
        )
        val json = rawJson(
            calibration,
            mapOf("backend" to "postgres"),
            mapOf("server_version" to "17.6", "synchronous_commit" to "on"),
            listOf(step),
        )
        json.assert().startsWith("{\"schema\":\"wow-e2e-bench/raw/1\",\"calibration\":{\"passed\":true")
        json.assert().contains(
            "\"checks\":[{\"metric\":\"walBytes\"",
            "\"apart\":[{\"metric\":\"walFpi\"",
            "\"store\":{\"server_version\":\"17.6\",\"synchronous_commit\":\"on\"}",
            "\"case\":\"wow-today\"",
            "\"pool\":4",
            "\"read\":true",
            "\"unitsPerSecond\":500.0",
            "\"storeCpuMsPerUnit\":null",
            "\"hotShare\":0.75",
        )
    }

    private companion object {
        val CASE = RawCase("fake", "command", 2, "a fake store")

        /** A worker of a fake store: [write] for each flush, which returns [flushed]. */
        fun fake(flushed: Flushed = Flushed(2), write: () -> Unit) = object : RawWorker {
            override fun flush(): Flushed {
                write()
                Thread.sleep(1)
                return flushed
            }

            override fun close() = Unit
        }
    }
}
