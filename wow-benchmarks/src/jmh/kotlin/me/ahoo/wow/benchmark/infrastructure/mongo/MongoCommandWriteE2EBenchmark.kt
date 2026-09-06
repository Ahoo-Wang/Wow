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

package me.ahoo.wow.benchmark.infrastructure.mongo

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.SchedulerStrategy
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.benchmark.scenario.toSchedulerSupplier
import me.ahoo.wow.benchmark.workload.ConcurrentBatchWorkload
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.MongoEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.kotlin.core.publisher.toMono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class MongoCommandWriteE2EBenchmark {
    @Param("PARALLEL", "IMMEDIATE")
    private var schedulerStrategy: String = SchedulerStrategy.PARALLEL.name

    @Param("4")
    private var concurrency: Int = 4

    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private lateinit var concurrentBatch: ConcurrentBatchWorkload
    private lateinit var largeConcurrentBatch: ConcurrentBatchWorkload
    private val failures = AtomicInteger()
    private val expectedWrites = AtomicLong()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        expectedWrites.set(0)
        fixture = MongoBenchmarkFixture()
        val eventStore = MongoEventStore(fixture.database)
        commandDispatcherScenario = CommandDispatcherScenario.create(
            eventStore = eventStore,
            schedulerSupplier = SchedulerStrategy.valueOf(schedulerStrategy).toSchedulerSupplier(),
        )
        concurrentBatch = ConcurrentBatchWorkload(COMMANDS_PER_BATCH, concurrency)
        largeConcurrentBatch = ConcurrentBatchWorkload(LARGE_BATCH_COMMANDS, concurrency)
        println("Mongo E2E database=${fixture.database.name} writeConcern=${fixture.database.writeConcern} batchEnabled=${eventStore.batchOptions.enabled}")
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            commandDispatcherScenario.close()
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Mongo command write E2E recorded $failureCount failure(s).",
                )
            }
            val actualWrites = checkNotNull(
                fixture.database.getCollection(BenchmarkAggregates.namedAggregate.toEventStreamCollectionName())
                    .countDocuments().toMono().block(VERIFY_TIMEOUT)
            )
            check(actualWrites == expectedWrites.get()) {
                "Mongo command write count mismatch: expected=${expectedWrites.get()}, actual=$actualWrites."
            }
            println("Mongo E2E verified database=${fixture.database.name} completed=${expectedWrites.get()} stored=$actualWrites")
        } finally {
            fixture.close()
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackHole: Blackhole) {
        blackHole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            val result = commandDispatcherScenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.newAggregateAddCartItem())
                .block()
            checkNotNull(result)
            expectedWrites.incrementAndGet()
            result
        }
    }

    @Benchmark
    @OperationsPerInvocation(COMMANDS_PER_BATCH)
    fun sendBatchConcurrentAndWaitProcessed(blackhole: Blackhole) {
        consumeBatch(concurrentBatch, blackhole)
    }

    @Benchmark
    @OperationsPerInvocation(LARGE_BATCH_COMMANDS)
    fun sendLargeBatchConcurrentAndWaitProcessed(blackhole: Blackhole) {
        consumeBatch(largeConcurrentBatch, blackhole)
    }

    private fun consumeBatch(workload: ConcurrentBatchWorkload, blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            val completed = workload.execute {
                commandDispatcherScenario.commandGateway
                    .sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem())
            }.block()
            check(completed == workload.size.toLong())
            expectedWrites.addAndGet(checkNotNull(completed))
            completed
        }
    }

    private companion object {
        const val COMMANDS_PER_BATCH = 32
        const val LARGE_BATCH_COMMANDS = 256
        val VERIFY_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
