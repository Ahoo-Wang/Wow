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
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `a step's growth is each counter's, the WAL's position left out, and a position reads as PostgreSQL's`() {
        val before = mapOf("walRecords" to 10.0, PostgresCounters.WAL_POSITION to 100.0)
        val after = mapOf("walRecords" to 25.0, "commits" to 3.0, PostgresCounters.WAL_POSITION to 900.0)
        growthOf(before, after).assert().isEqualTo(mapOf("walRecords" to 15.0, "commits" to 3.0))
        PostgresCounters.lsnOf(0x1_0000_00A0L).assert().isEqualTo("1/A0")
        PostgresCounters.lsnOf(0x2F0L).assert().isEqualTo("0/2F0")
    }

    @Test
    fun `the WAL on the cloned tables sums their records of every type and their bytes, and nothing else`() {
        val records = mapOf(
            "wal Heap/INSERT raw_log records" to 1.0,
            "wal Btree/INSERT_LEAF raw_log records" to 3.0,
            "wal Heap/HOT_UPDATE raw_snapshot records" to 1.0,
            "wal Heap/LOCK wow_checkpoint records" to 0.5,
            "wal Transaction/COMMIT - records" to 0.2,
            "wal raw_log bytes" to 400.0,
            "wal raw_snapshot bytes" to 100.0,
            "wal wow_checkpoint bytes" to 60.0,
            "walRecords" to 9.0,
        )
        PostgresCounters.onTables(records, RawPostgres.RAW_TABLES, "path").assert().isEqualTo(
            mapOf("pathWalRecords" to 5.0, "pathWalBytes" to 500.0)
        )
        PostgresCounters.onTables(mapOf("walRecords" to 9.0), RawPostgres.RAW_TABLES, "path").assert().isEmpty()
    }

    @Test
    fun `today's snapshots are saved behind the appends, a save of its own size across them`() {
        val owned = Aggregates(opened(40), 0, 1)
        val appends = mutableListOf<Int>()
        val saves = mutableListOf<List<Int>>()
        fun flush() = savedBehind(owned, 4, 6, {
            appends += it.size
            Flushed(it.size)
        }) { batch ->
            saves += batch.map { it.number }
            Flushed(batch.size)
        }
        flush().assert().isEqualTo(Flushed(0))
        flush().assert().isEqualTo(Flushed(6))
        flush().assert().isEqualTo(Flushed(6))
        appends.assert().containsExactly(4, 4, 4)
        saves.assert().containsExactly(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 10, 11))
        // A command whose append was refused is never saved.
        savedBehind(owned, 4, 1, { Flushed(2, refused = 2) }) { Flushed(it.size) }.assert().isEqualTo(Flushed(2, 2))
    }

    @Test
    fun `a step that outlives its limit halts the program with the hung exit, one cancelled in time does not`() {
        Watchdog.limitOf(40).assert().isEqualTo(4 * 40 + 60L)
        val halted = java.util.concurrent.CompletableFuture<Int>()
        Watchdog("calibration at 64", 0, halted::complete)
        halted.get(5, java.util.concurrent.TimeUnit.SECONDS).assert().isEqualTo(Watchdog.EXIT_HUNG)
        val calm = java.util.concurrent.CompletableFuture<Int>()
        Watchdog("calibration at 64", 1, calm::complete).cancel()
        Thread.sleep(1_500)
        calm.isDone.assert().isFalse()
    }

    @Test
    fun `a bench-pipeline line's field is read by its section, null when the line has none`() {
        val line = "{\"t\":3,\"appendDepth\":{\"queued\":0,\"handedGroups\":2},\"appendCalls\":{\"calls\":40,\"streams\":600}," +
            "\"pool\":{\"active\":4,\"waiting\":1,\"max\":10}}"
        WowWindow.field(line, "appendCalls", "calls").assert().isEqualTo(40.0)
        WowWindow.field(line, "appendCalls", "streams").assert().isEqualTo(600.0)
        WowWindow.field(line, "pool", "waiting").assert().isEqualTo(1.0)
        WowWindow.field(line, "appendDepth", "calls").assert().isNull()
        WowWindow.field(line, "window", "x").assert().isNull()
    }

    @Test
    fun `a call's mean time is its nanoseconds over its calls in a window, none without a call`() {
        val times = CallTimes()
        times.add("append", 3_000_000)
        times.time("append") { Thread.sleep(0) }
        times.counters()["append calls"].assert().isEqualTo(2.0)
        val growth = mapOf("append nanos" to 6e6, "append calls" to 3.0, "save nanos" to 0.0, "save calls" to 0.0)
        CallTimes.means(growth).assert().isEqualTo(mapOf("appendMs" to 2.0))
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
        Aggregates.idOf(42).assert().isEqualTo("acc-0000042")
        assertThrows<IllegalArgumentException> { Aggregates(IntArray(2), 5, 6) }
    }

    @Test
    fun `each step of a case runs on freshly prepared tables, its aggregates opened at version 1`() {
        val events = ArrayList<String>()
        val started = ArrayList<List<Int>>()
        val ended = ArrayList<List<Int>>()
        val steps = stepsOf(listOf(2, 4), aggregates = 40, prepare = { events += "prepare" }) { connections, versions ->
            events += "step at $connections"
            started += versions.toList()
            runStep(CASE, connections, Pace(0 to 1), ContainerCpu(null), { emptyMap() }) { index ->
                val owned = Aggregates(versions, index, connections)
                fake { owned.take(2) }
            }.also { ended += versions.toList() }
        }
        events.assert().containsExactly("prepare", "step at 2", "prepare", "step at 4")
        steps.map { it.connections }.assert().containsExactly(2, 4)
        steps.all { it.read }.assert().isTrue()
        // The first step wrote versions past 1; the second started again from 1, on its freshly prepared tables.
        ended[0].max().assert().isGreaterThan(1)
        started.forEach { versions -> versions.distinct().assert().containsExactly(1) }
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
    fun `wow's partitions an append are its partition table's updates over its appends, its WAL's when read`() {
        val appends = "statements: statement with wow_log__transfer__account"
        val wow = mapOf(
            appends to 0.1,
            "wal Heap/HOT_UPDATE wow_log__transfer__partition records" to 0.45,
            "wal Heap/UPDATE wow_log__transfer__partition records" to 0.05,
            "wow_log__transfer__partition updates" to 0.6,
        )
        spreadOf(wow).assert().isEqualTo(5.0, within(1e-9))
        spreadOf(wow.filterKeys { !it.startsWith("wal ") }).assert().isEqualTo(6.0, within(1e-9))
        spreadOf(wow - appends).assert().isNull()
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

    @Test
    fun `a ceiling comes from a calibrated run's read, unpaced steps of wow's shape, a committed one's saves kept up`() {
        fun step(
            ceiling: String?,
            perSecond: Long,
            ratios: Map<String, Double> = emptyMap(),
            failures: Long = 0,
            saveFailed: Double = 0.0
        ) =
            RawStep(
                case = RawCase("c-$ceiling-$perSecond", "command", 1, "", ceiling),
                connections = 64, rate = null, seconds = 1.0, units = perSecond, flushes = perSecond,
                failures = failures, refused = 0, late = 0, p50FlushMs = 1.0, p99FlushMs = 2.0, storeCpuMsPerUnit = null,
                perUnit = mapOf(WowShape.SHAPE_SAVE_FAILED to saveFailed), ratios = ratios,
            )
        val kept = mapOf(WowShape.SAVES_PER_APPEND to 0.99)
        val steps = listOf(
            step(null, 90_000),
            step(RawCase.COMMITTED, 50_000, kept),
            step(RawCase.COMMITTED, 70_000, mapOf(WowShape.SAVES_PER_APPEND to 0.8)),
            step(RawCase.COMMITTED, 60_000, kept, failures = 1),
            step(RawCase.COMMITTED, 55_000, kept, saveFailed = 0.01),
            step(RawCase.SNAPSHOT, 30_000),
            step(RawCase.SNAPSHOT, 40_000),
        )
        ceilingsOf(steps, calibrated = true).mapValues { it.value.units }.assert()
            .isEqualTo(mapOf(RawCase.COMMITTED to 50_000L, RawCase.SNAPSHOT to 40_000L))
        ceilingsOf(steps, calibrated = false).assert().isEmpty()
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
