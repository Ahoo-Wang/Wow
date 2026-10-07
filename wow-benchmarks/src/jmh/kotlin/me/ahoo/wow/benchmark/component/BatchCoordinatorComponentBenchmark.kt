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

package me.ahoo.wow.benchmark.component

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.wow.infra.batch.BatchCoordinator
import me.ahoo.wow.infra.batch.BatchItemResult
import me.ahoo.wow.infra.batch.BatchOptions
import me.ahoo.wow.infra.batch.BatchWriter
import me.ahoo.wow.metrics.WowMetrics
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.ThreadParams
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Storage batch admission without storage: every producer submits a burst of [BURST] prepared items to one
 * [BatchCoordinator] (4 lanes, the default size, delay and capacity) whose writer succeeds at once, and waits for
 * all results. The score is items per second, so it measures admission, lane hand-off, windowing and result
 * dispatch. Run with 1 and 4 producer threads: the 4-producer cell is the one the storage-batching acceptance
 * failed, with JFR pointing at the admission lock (`documentation/designs/storage-batching-review.md`).
 *
 * Same cells as `wow-benchmarks/experiments/storage-batching/CoreBoundary.prepared`. Design WP X4.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class BatchCoordinatorComponentBenchmark {
    @Param("off", "on")
    lateinit var metrics: String

    private lateinit var coordinator: BatchCoordinator<Long>
    private var registry: SimpleMeterRegistry? = null

    @Setup(Level.Trial)
    fun setup() {
        val wowMetrics = when (metrics) {
            "off" -> WowMetrics.NONE
            "on" -> WowMetrics(SimpleMeterRegistry().also { registry = it })
            else -> error("Unsupported metrics: $metrics")
        }
        coordinator = BatchCoordinator(
            name = "benchmark",
            options = BatchOptions(laneCount = LANES),
            writer = SucceedingWriter,
            keySelector = { it },
            metrics = wowMetrics,
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        coordinator.close()
        registry?.let {
            check(it.meters.isNotEmpty()) { "Metrics on must record meters." }
            it.close()
        }
    }

    @Benchmark
    @OperationsPerInvocation(BURST)
    fun submitBurst(producer: Producer): Long {
        val written = Flux.fromArray(producer.items)
            .flatMap({ item -> coordinator.submit(item).thenReturn(1) }, BURST, 1)
            .count()
            .block(TIMEOUT)
        check(written == BURST.toLong()) { "Expected $BURST results but got $written." }
        return written
    }

    /** One producer thread's prepared items; distinct across threads, so keys spread over every lane. */
    @State(Scope.Thread)
    open class Producer {
        lateinit var items: Array<Long>

        @Setup(Level.Trial)
        fun setup(threadParams: ThreadParams) {
            val base = threadParams.threadIndex.toLong() shl Int.SIZE_BITS
            items = Array(BURST) { base + it }
        }
    }

    private object SucceedingWriter : BatchWriter<Long> {
        override fun write(items: List<Long>): Mono<List<BatchItemResult>> =
            Mono.just(Collections.nCopies(items.size, BatchItemResult.Success))
    }

    private companion object {
        const val BURST = 128
        const val LANES = 4
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
