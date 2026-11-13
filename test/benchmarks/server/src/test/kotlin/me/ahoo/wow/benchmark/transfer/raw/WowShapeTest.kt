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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class WowShapeTest {
    /** A store that records its calls: each append's partitions and size, each save, and when each was appended. */
    private class Store : AutoCloseable {
        override fun close() = Unit
    }

    private class Calls {
        val appends = Collections.synchronizedList(ArrayList<List<Int>>())
        val saves = Collections.synchronizedList(ArrayList<Int>())
        val appended = ConcurrentHashMap.newKeySet<Int>()
        val savedBeforeAppend = AtomicInteger()
        val inFlight = ConcurrentHashMap<Int, AtomicInteger>()
        val overlapping = AtomicInteger()
    }

    private val partitions = 8
    private fun partitionOf(command: Aggregates.Command) = command.number % partitions

    private fun shape(calls: Calls, options: WowShape.Options, refuse: (Aggregates.Command) -> Boolean = { false }) =
        WowShape(
            partitions,
            ::partitionOf,
            options,
            ::Store,
            { _, commands ->
                val parts = commands.map(::partitionOf)
                parts.distinct().forEach { part ->
                    if (calls.inFlight.computeIfAbsent(part) { AtomicInteger() }.incrementAndGet() > 1) {
                        calls.overlapping.incrementAndGet()
                    }
                }
                calls.appends += parts
                Thread.sleep(2)
                parts.distinct().forEach { calls.inFlight.getValue(it).decrementAndGet() }
                BooleanArray(commands.size) { !refuse(commands[it]) }.also { written ->
                    commands.forEachIndexed { at, command -> if (written[at]) calls.appended += command.number }
                }
            },
            { _, commands ->
                calls.saves += commands.size
                commands.forEach { if (it.number !in calls.appended) calls.savedBeforeAppend.incrementAndGet() }
                BooleanArray(commands.size) { true }
            },
        )

    @Test
    fun `calls merge what the lanes hand, a lane's batch at most, the call's maximum at most, one batch a lane in flight`() {
        val calls = Calls()
        val options = WowShape.Options(mergeMax = 12, maxBatch = 4, writerMax = 5)
        val pool = Executors.newFixedThreadPool(64)
        val results = shape(calls, options).use { shape ->
            (0 until 400).map { number -> pool.submit<Boolean> { shape.submit(Aggregates.Command(number, 2)) } }
                .map { it.get() }
        }
        pool.shutdown()
        results.all { it }.assert().isTrue()
        calls.appends.sumOf { it.size }.assert().isEqualTo(400)
        calls.appends.all { it.size <= options.mergeMax }.assert().isTrue()
        calls.appends.all { parts -> parts.groupingBy { it }.eachCount().values.all { it <= options.maxBatch } }
            .assert().isTrue()
        calls.appends.any { it.size > options.maxBatch }.assert().isTrue()
        calls.overlapping.get().assert().isZero()
        calls.saves.sum().assert().isEqualTo(400)
        calls.saves.all { it <= options.writerMax }.assert().isTrue()
        calls.savedBeforeAppend.get().assert().isZero()
    }

    @Test
    fun `a command the store refused is not saved, and one a failed call carried fails its caller`() {
        val calls = Calls()
        shape(calls, WowShape.Options(), refuse = { it.number == 3 }).use { shape ->
            shape.submit(Aggregates.Command(3, 2)).assert().isFalse()
            shape.submit(Aggregates.Command(4, 2)).assert().isTrue()
        }
        calls.saves.sum().assert().isEqualTo(1)
        val failing =
            WowShape(partitions, ::partitionOf, WowShape.Options(), ::Store, { _, _ -> error("down") }) { _, c ->
                BooleanArray(c.size) { true }
            }
        failing.use { shape ->
            assertThrows<IllegalStateException> {
                shape.submit(
                    Aggregates.Command(1, 2)
                )
            }.message.assert().isEqualTo("down")
        }
    }

    @Test
    fun `wow's defaults are two appenders of up to 512 streams and two writers of up to 256 rows`() {
        WowShape.Options().apply {
            appenders.assert().isEqualTo(2)
            mergeMax.assert().isEqualTo(512)
            maxBatch.assert().isEqualTo(64)
            writers.assert().isEqualTo(2)
            writerMax.assert().isEqualTo(256)
            connections.assert().isEqualTo(4)
        }
        WowShape.MERGES.assert().containsExactly(64, 256, 512)
    }
}
