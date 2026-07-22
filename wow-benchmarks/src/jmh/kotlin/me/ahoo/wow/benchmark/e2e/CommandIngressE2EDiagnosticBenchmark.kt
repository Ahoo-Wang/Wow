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

import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.fixture.BenchmarkIdempotency
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotRepository
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.infra.sink.concurrent
import me.ahoo.wow.modeling.materialize
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Sinks
import java.util.concurrent.atomic.AtomicInteger

@State(Scope.Benchmark)
open class CommandIngressE2EDiagnosticBenchmark {
    @Param(
        "current-production",
        "legacy-lock",
    )
    lateinit var ingressStrategy: String

    private lateinit var scenario: CommandDispatcherScenario
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        scenario = CommandDispatcherScenario.create(
            commandBus = createCommandBus(),
            eventStore = NoopEventStore,
            snapshotRepository = InMemorySnapshotRepository(),
            domainEventBus = InMemoryDomainEventBus(),
            stateEventBus = InMemoryStateEventBus(),
            schedulerSupplier = BenchmarkAggregateSchedulerSupplier(),
            validator = NoOpValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                NoOpIdempotencyChecker
            },
            namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            check(failureCount == 0) {
                "Command ingress E2E diagnostic [$ingressStrategy] recorded $failureCount failure(s)."
            }
        } finally {
            scenario.close()
        }
    }

    @Benchmark
    fun sendAndWaitProcessed(blackhole: Blackhole) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            scenario.commandGateway
                .sendAndWaitForProcessed(createCommandMessage())
                .block()
        }
    }

    private fun createCommandBus(): InMemoryCommandBus =
        when (ingressStrategy) {
            "current-production" -> InMemoryCommandBus()
            "legacy-lock" -> InMemoryCommandBus {
                Sinks.unsafe()
                    .many()
                    .unicast()
                    .onBackpressureBuffer<CommandMessage<*>>()
                    .concurrent()
            }

            else -> error("Unsupported command ingress strategy: $ingressStrategy")
        }

    private fun createCommandMessage(): CommandMessage<AddCartItem> =
        BenchmarkCommands.commandPathAddCartItem()
}
