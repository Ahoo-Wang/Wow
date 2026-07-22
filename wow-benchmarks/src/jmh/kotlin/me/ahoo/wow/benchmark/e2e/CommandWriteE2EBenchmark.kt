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

import jakarta.validation.Validator
import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.SchedulerStrategy
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.benchmark.scenario.toSchedulerSupplier
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.test.validation.TestValidator
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
open class CommandWriteE2EBenchmark {
    @Param(
        "ceiling",
        "noop-store",
        "in-memory-new-aggregate",
    )
    lateinit var scenario: String

    @Param("PARALLEL", "IMMEDIATE")
    private var schedulerStrategy: String = SchedulerStrategy.PARALLEL.name

    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        val schedulerSupplier = SchedulerStrategy.valueOf(schedulerStrategy).toSchedulerSupplier()
        commandDispatcherScenario = when (scenario) {
            "ceiling" -> createScenario(
                eventStore = NoopEventStore,
                idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                    NoOpIdempotencyChecker
                },
                validator = NoOpValidator,
                schedulerSupplier = schedulerSupplier,
            )

            "noop-store" -> createScenario(eventStore = NoopEventStore, schedulerSupplier = schedulerSupplier)
            "in-memory-new-aggregate" ->
                createScenario(eventStore = InMemoryEventStore(), schedulerSupplier = schedulerSupplier)
            else -> error("Unsupported command write E2E scenario: $scenario")
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Command write E2E scenario [$scenario] recorded $failureCount failure(s).",
                )
            }
        } finally {
            commandDispatcherScenario.close()
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

    private fun createScenario(
        commandBus: CommandBus = InMemoryCommandBus(),
        eventStore: EventStore,
        snapshotRepository: SnapshotRepository = InMemorySnapshotRepository(),
        domainEventBus: DomainEventBus = InMemoryDomainEventBus(),
        stateEventBus: StateEventBus = InMemoryStateEventBus(),
        schedulerSupplier: AggregateSchedulerSupplier = BenchmarkAggregateSchedulerSupplier(),
        idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider =
            DefaultAggregateIdempotencyCheckerProvider {
                BenchmarkIdempotency.bloomFilterChecker()
            },
        validator: Validator = TestValidator,
    ): CommandDispatcherScenario {
        return CommandDispatcherScenario.create(
            commandBus = commandBus,
            eventStore = eventStore,
            snapshotRepository = snapshotRepository,
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            schedulerSupplier = schedulerSupplier,
            validator = validator,
            idempotencyCheckerProvider = idempotencyCheckerProvider,
            namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
        )
    }

    private fun createCommandMessage(): CommandMessage<AddCartItem> {
        return when (scenario) {
            "ceiling" -> BenchmarkCommands.commandPathAddCartItem()
            "noop-store" -> BenchmarkCommands.commandPathAddCartItem()
            "in-memory-new-aggregate" -> BenchmarkCommands.newAggregateAddCartItem()
            else -> error("Unsupported command write E2E scenario: $scenario")
        }
    }
}
