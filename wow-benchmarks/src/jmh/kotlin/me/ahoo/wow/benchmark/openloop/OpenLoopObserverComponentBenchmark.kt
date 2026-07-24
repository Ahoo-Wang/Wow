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

package me.ahoo.wow.benchmark.openloop

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Isolates the write-side cost of the open-loop latency recorders.
 *
 * This benchmark intentionally excludes clocks, request state, deadline tracking, summary merging,
 * and the command runtime. It therefore explains only the recorder component of an observation-mode
 * delta and must not be interpreted as application throughput.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class OpenLoopObserverComponentBenchmark {
    private lateinit var disabled: ConcurrentLatencyRecorder
    private lateinit var generatorLag: ConcurrentLatencyRecorder
    private lateinit var scheduledToSent: ConcurrentLatencyRecorder
    private lateinit var scheduledToProcessed: ConcurrentLatencyRecorder
    private lateinit var admittedToProcessed: ConcurrentLatencyRecorder
    private lateinit var sentToProcessed: ConcurrentLatencyRecorder

    @Setup(Level.Iteration)
    fun resetRecorders() {
        disabled = ConcurrentLatencyRecorder(enabled = false)
        generatorLag = ConcurrentLatencyRecorder(enabled = true)
        scheduledToSent = ConcurrentLatencyRecorder(enabled = true)
        scheduledToProcessed = ConcurrentLatencyRecorder(enabled = true)
        admittedToProcessed = ConcurrentLatencyRecorder(enabled = true)
        sentToProcessed = ConcurrentLatencyRecorder(enabled = true)
    }

    @TearDown(Level.Iteration)
    fun verifyRecorderCounts() {
        listOf(
            generatorLag,
            scheduledToSent,
            scheduledToProcessed,
            admittedToProcessed,
            sentToProcessed,
        ).forEach { recorder ->
            check(recorder.summary().count >= 0) {
                "SampleBuffer count overflowed within one iteration."
            }
        }
    }

    @Benchmark
    fun noObservation(
        input: OpenLoopLatencyInput,
        blackhole: Blackhole,
    ) {
        blackhole.consume(input.nextOffset())
    }

    @Benchmark
    fun noLatencyObserverPath(input: OpenLoopLatencyInput) {
        val offset = input.nextOffset()
        disabled.record(GENERATOR_LAG_NANOS + offset)
        disabled.record(SCHEDULED_TO_SENT_NANOS + offset)
        disabled.record(SCHEDULED_TO_PROCESSED_NANOS + offset)
        disabled.record(ADMITTED_TO_PROCESSED_NANOS + offset)
    }

    @Benchmark
    fun generatorOnlyLatencyObserverPath(input: OpenLoopLatencyInput) {
        val offset = input.nextOffset()
        generatorLag.record(GENERATOR_LAG_NANOS + offset)
        disabled.record(SCHEDULED_TO_SENT_NANOS + offset)
        disabled.record(SCHEDULED_TO_PROCESSED_NANOS + offset)
        disabled.record(ADMITTED_TO_PROCESSED_NANOS + offset)
    }

    @Benchmark
    fun fullLatencyObserverPath(input: OpenLoopLatencyInput) {
        val offset = input.nextOffset()
        generatorLag.record(GENERATOR_LAG_NANOS + offset)
        scheduledToSent.record(SCHEDULED_TO_SENT_NANOS + offset)
        scheduledToProcessed.record(SCHEDULED_TO_PROCESSED_NANOS + offset)
        admittedToProcessed.record(ADMITTED_TO_PROCESSED_NANOS + offset)
        sentToProcessed.record(SENT_TO_PROCESSED_NANOS + offset)
    }

    private companion object {
        const val GENERATOR_LAG_NANOS = 500_000L
        const val SCHEDULED_TO_SENT_NANOS = 5_000_000L
        const val SCHEDULED_TO_PROCESSED_NANOS = 50_000_000L
        const val ADMITTED_TO_PROCESSED_NANOS = 49_500_000L
        const val SENT_TO_PROCESSED_NANOS = 45_000_000L
    }
}

@State(Scope.Thread)
open class OpenLoopLatencyInput {
    private var offset: Long = 0

    @Setup(Level.Iteration)
    fun reset() {
        offset = 0
    }

    fun nextOffset(): Long {
        offset = (offset + OFFSET_STEP) and OFFSET_MASK
        return offset
    }

    private companion object {
        const val OFFSET_STEP: Long = 7_919
        const val OFFSET_MASK: Long = 0xFFFF
    }
}
