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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.SchedulerStrategy
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.infrastructure.mongo.MongoBenchmarkFixture
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.atomic.AtomicInteger

/**
 * Validates whether the cross-thread handoff cost attributed by the isolated
 * [me.ahoo.wow.benchmark.component.CommandDispatcherChainComponentBenchmark] remains
 * material in a realistic end-to-end command write against a real MongoDB event store.
 *
 * Mirrors [MongoCommandWriteE2EBenchmark] but adds a [schedulerStrategy] parameter:
 * - [SchedulerStrategy.PARALLEL]: production default (dedicated `newParallel` pool).
 * - [SchedulerStrategy.IMMEDIATE]: `Schedulers.immediate()`, so the dispatcher's
 *   `publishOn` does not switch threads and the wait notification round trip stays
 *   on the calling thread.
 *
 * Comparing the two against real Mongo I/O answers whether the ~95% cross-thread
 * attribution from the isolated benchmark survives when event-store latency dominates,
 * or whether Mongo I/O dwarfs the handoff cost.
 *
 * @author ahoo wang
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class MongoCommandWriteCrossThreadBenchmark {
    @Param("PARALLEL", "IMMEDIATE")
    private var schedulerStrategy: String = SchedulerStrategy.PARALLEL.name

    private lateinit var fixture: MongoBenchmarkFixture
    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        fixture = MongoBenchmarkFixture()
        commandDispatcherScenario = CommandDispatcherScenario.create(
            eventStore = MongoEventStore(fixture.database),
            schedulerSupplier = schedulerSupplierFor(SchedulerStrategy.valueOf(schedulerStrategy)),
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Mongo cross-thread benchmark recorded $failureCount failure(s).",
                )
            }
        } finally {
            try {
                commandDispatcherScenario.close()
            } finally {
                fixture.close()
            }
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackHole: Blackhole) {
        blackHole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            commandDispatcherScenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.newAggregateAddCartItem())
                .block()
        }
    }

    private companion object {
        fun schedulerSupplierFor(strategy: SchedulerStrategy): AggregateSchedulerSupplier =
            when (strategy) {
                SchedulerStrategy.PARALLEL -> me.ahoo.wow.BenchmarkAggregateSchedulerSupplier()
                SchedulerStrategy.IMMEDIATE -> ImmediateAggregateSchedulerSupplier
            }

        /**
         * Returns [Schedulers.immediate] for every aggregate so the dispatcher's `publishOn`
         * becomes a no-op, keeping the full command-write round trip on the calling thread.
         */
        object ImmediateAggregateSchedulerSupplier : AggregateSchedulerSupplier {
            override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler = Schedulers.immediate()

            override fun stopGracefully(): Mono<Void> = Mono.empty()
        }
    }
}
