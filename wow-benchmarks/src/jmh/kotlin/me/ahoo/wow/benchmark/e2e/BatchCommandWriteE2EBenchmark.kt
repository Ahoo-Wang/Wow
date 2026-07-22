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

package me.ahoo.wow.benchmark.e2e

import me.ahoo.wow.benchmark.scenario.CommandWriteE2EFixture
import me.ahoo.wow.benchmark.scenario.SchedulerStrategy
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.benchmark.workload.ConcurrentBatchWorkload
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compares complete command writes using either one blocking boundary per command or one per 32 commands.
 *
 * [OperationsPerInvocation] normalizes JMH throughput and allocation to a single command,
 * so results can be compared with [CommandWriteE2EBenchmark] without reporting batches as operations.
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class BatchCommandWriteE2EBenchmark {
    @Param(
        CommandWriteE2EFixture.CEILING_SCENARIO,
        CommandWriteE2EFixture.NOOP_STORE_SCENARIO,
        CommandWriteE2EFixture.IN_MEMORY_NEW_AGGREGATE_SCENARIO,
    )
    lateinit var scenario: String

    private lateinit var fixture: CommandWriteE2EFixture
    private lateinit var sequentialBatch: ConcurrentBatchWorkload
    private lateinit var concurrentBatch: ConcurrentBatchWorkload
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        fixture = CommandWriteE2EFixture.create(
            scenarioId = scenario,
            schedulerStrategy = SchedulerStrategy.PARALLEL,
        )
        sequentialBatch = ConcurrentBatchWorkload(
            size = COMMANDS_PER_INVOCATION,
            concurrency = SEQUENTIAL_CONCURRENCY,
        )
        concurrentBatch = ConcurrentBatchWorkload(
            size = COMMANDS_PER_INVOCATION,
            concurrency = CONCURRENT_CONCURRENCY,
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Batch command write E2E scenario [$scenario] recorded $failureCount failure(s).",
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS_PER_INVOCATION)
    fun sendIndividuallyAndWaitProcessed(blackhole: Blackhole) {
        repeat(COMMANDS_PER_INVOCATION) {
            blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
                fixture.commandGateway
                    .sendAndWaitForProcessed(fixture.nextCommand())
                    .block()
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS_PER_INVOCATION)
    fun sendBatchSequentialAndWaitProcessed(blackhole: Blackhole) {
        consumeBatch(sequentialBatch, blackhole)
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS_PER_INVOCATION)
    fun sendBatchConcurrentAndWaitProcessed(blackhole: Blackhole) {
        consumeBatch(concurrentBatch, blackhole)
    }

    private fun consumeBatch(workload: ConcurrentBatchWorkload, blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            workload.execute {
                fixture.commandGateway.sendAndWaitForProcessed(fixture.nextCommand())
            }.block()
        }
    }

    companion object {
        const val COMMANDS_PER_INVOCATION = 32
        private const val SEQUENTIAL_CONCURRENCY = 1
        private const val CONCURRENT_CONCURRENCY = 4
    }
}
