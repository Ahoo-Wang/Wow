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

import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.SchedulerStrategy
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.benchmark.scenario.toSchedulerSupplier
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
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
import java.util.concurrent.atomic.AtomicInteger

@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class CommandWriteFrameworkBreakdownBenchmark {
    @Param(
        "ceiling-noop",
        "noop-store",
        "in-memory-new-aggregate",
        "mongo",
    )
    lateinit var scenario: String

    @Param("PARALLEL", "IMMEDIATE")
    private var schedulerStrategy: String = SchedulerStrategy.PARALLEL.name

    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private var fixture: MongoBenchmarkFixture? = null
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        fixture = null
        val schedulerSupplier = SchedulerStrategy.valueOf(schedulerStrategy).toSchedulerSupplier()
        commandDispatcherScenario = when (scenario) {
            "ceiling-noop" -> CommandDispatcherScenario.create(
                eventStore = NoopEventStore,
                validator = NoOpValidator,
                idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                    NoOpIdempotencyChecker
                },
                schedulerSupplier = schedulerSupplier,
            )

            "noop-store" ->
                CommandDispatcherScenario.create(eventStore = NoopEventStore, schedulerSupplier = schedulerSupplier)
            "in-memory-new-aggregate" ->
                CommandDispatcherScenario.create(eventStore = InMemoryEventStore(), schedulerSupplier = schedulerSupplier)
            "mongo" -> createMongoScenario(schedulerSupplier)
            else -> error("Unsupported command write framework breakdown scenario: $scenario")
        }
    }

    private fun createMongoScenario(schedulerSupplier: AggregateSchedulerSupplier): CommandDispatcherScenario {
        val mongoFixture = MongoBenchmarkFixture()
        fixture = mongoFixture
        return CommandDispatcherScenario.create(
            eventStore = MongoEventStore(mongoFixture.database),
            schedulerSupplier = schedulerSupplier,
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Command write framework scenario [$scenario] recorded $failureCount failure(s).",
                )
            }
        } finally {
            try {
                commandDispatcherScenario.close()
            } finally {
                fixture?.close()
                fixture = null
            }
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            commandDispatcherScenario.commandGateway
                .sendAndWaitForProcessed(createCommandMessage())
                .block()
        }
    }

    private fun createCommandMessage() =
        when (scenario) {
            "ceiling-noop",
            "noop-store" -> BenchmarkCommands.commandPathAddCartItem()

            "in-memory-new-aggregate",
            "mongo" -> BenchmarkCommands.newAggregateAddCartItem()

            else -> error("Unsupported command write framework breakdown scenario: $scenario")
        }
}
