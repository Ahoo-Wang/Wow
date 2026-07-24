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
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregateIdPlacement
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregateIdPlan
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkCommands
import me.ahoo.wow.benchmark.scenario.CommandDispatcherScenario
import me.ahoo.wow.benchmark.scenario.consumeWowResult
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.materialize
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Group
import org.openjdk.jmh.annotations.GroupThreads
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.ThreadParams
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

private const val HEAD_OF_LINE_STRIPE_COUNT = 16
private const val HEAD_OF_LINE_AGGREGATE_CARDINALITY = 4
private const val SLOW_HANDLER_CPU_TOKENS = 100_000L

private enum class HeadOfLineScenario(
    val placement: BenchmarkAggregateIdPlacement,
    val schedulerPoolSize: Int,
    val slowHandlerCpuTokens: Long,
) {
    DISTINCT_UNIFORM_POOL4(
        placement = BenchmarkAggregateIdPlacement.BALANCED,
        schedulerPoolSize = 4,
        slowHandlerCpuTokens = 0,
    ),
    DISTINCT_ONE_SLOW_POOL4(
        placement = BenchmarkAggregateIdPlacement.BALANCED,
        schedulerPoolSize = 4,
        slowHandlerCpuTokens = SLOW_HANDLER_CPU_TOKENS,
    ),
    COLLIDING_UNIFORM_POOL4(
        placement = BenchmarkAggregateIdPlacement.SAME_STRIPE,
        schedulerPoolSize = 4,
        slowHandlerCpuTokens = 0,
    ),
    COLLIDING_ONE_SLOW_POOL4(
        placement = BenchmarkAggregateIdPlacement.SAME_STRIPE,
        schedulerPoolSize = 4,
        slowHandlerCpuTokens = SLOW_HANDLER_CPU_TOKENS,
    ),
    DISTINCT_UNIFORM_POOL1(
        placement = BenchmarkAggregateIdPlacement.BALANCED,
        schedulerPoolSize = 1,
        slowHandlerCpuTokens = 0,
    ),
    DISTINCT_ONE_SLOW_POOL1(
        placement = BenchmarkAggregateIdPlacement.BALANCED,
        schedulerPoolSize = 1,
        slowHandlerCpuTokens = SLOW_HANDLER_CPU_TOKENS,
    ),
}

/**
 * Closed-loop diagnostic that separates stripe head-of-line blocking from
 * Scheduler worker-rail contention.
 *
 * One JMH group contains one control-aggregate producer and three observed-fast
 * producers. The observed producers always use the other three aggregate IDs,
 * which lets SampleTime report their latency independently from the deliberately
 * slow control aggregate.
 */
@State(Scope.Benchmark)
open class CommandIngressHeadOfLineBenchmark {
    @Param(
        "DISTINCT_UNIFORM_POOL4",
        "DISTINCT_ONE_SLOW_POOL4",
        "COLLIDING_UNIFORM_POOL4",
        "COLLIDING_ONE_SLOW_POOL4",
        "DISTINCT_UNIFORM_POOL1",
        "DISTINCT_ONE_SLOW_POOL1",
    )
    lateinit var contentionScenario: String

    private lateinit var scenario: CommandDispatcherScenario
    private lateinit var controlAggregateId: String
    private lateinit var observedAggregateIds: List<String>
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        val resolvedScenario = HeadOfLineScenario.valueOf(contentionScenario)
        val aggregateIdPlan = BenchmarkAggregateIdPlan.create(
            cardinality = HEAD_OF_LINE_AGGREGATE_CARDINALITY.toString(),
            stripeCount = HEAD_OF_LINE_STRIPE_COUNT,
            placement = resolvedScenario.placement,
        )
        val activeStripes = aggregateIdPlan.activeStripes(HEAD_OF_LINE_STRIPE_COUNT)
        val expectedActiveStripeCount = when (resolvedScenario.placement) {
            BenchmarkAggregateIdPlacement.BALANCED ->
                minOf(HEAD_OF_LINE_AGGREGATE_CARDINALITY, HEAD_OF_LINE_STRIPE_COUNT)
            BenchmarkAggregateIdPlacement.SAME_STRIPE -> 1
        }
        check(activeStripes.size == expectedActiveStripeCount) {
            "Expected $expectedActiveStripeCount active stripe(s), but resolved $activeStripes."
        }

        controlAggregateId = aggregateIdPlan.aggregateIds.first()
        observedAggregateIds = aggregateIdPlan.aggregateIds.drop(1)
        scenario = CommandDispatcherScenario.create(
            commandBus = InMemoryCommandBus(),
            eventStore = NoopEventStore,
            snapshotRepository = InMemorySnapshotStore(),
            domainEventBus = InMemoryDomainEventBus(),
            stateEventBus = InMemoryStateEventBus(),
            schedulerSupplier = BenchmarkAggregateSchedulerSupplier(resolvedScenario.schedulerPoolSize),
            stripeCount = HEAD_OF_LINE_STRIPE_COUNT,
            validator = NoOpValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                NoOpIdempotencyChecker
            },
            namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
            commandHandlerDecorator = { delegate ->
                OneSlowAggregateCpuCommandHandler(
                    delegate = delegate,
                    slowAggregateId = controlAggregateId,
                    slowCpuTokens = resolvedScenario.slowHandlerCpuTokens,
                )
            },
        )
        check(scenario.commandDispatcher.parallelism == HEAD_OF_LINE_STRIPE_COUNT) {
            "Command dispatcher stripeCount was not applied."
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            check(failureCount == 0) {
                "Command ingress HOL scenario [$contentionScenario] recorded $failureCount failure(s)."
            }
        } finally {
            scenario.close()
        }
    }

    @Benchmark
    @Group("contention")
    @GroupThreads(1)
    fun controlAggregate(blackhole: Blackhole) {
        sendAndWaitProcessed(controlAggregateId, blackhole)
    }

    @Benchmark
    @Group("contention")
    @GroupThreads(3)
    fun observedFastAggregates(
        cursor: ObservedAggregateCursor,
        blackhole: Blackhole,
    ) {
        sendAndWaitProcessed(
            aggregateId = observedAggregateIds[cursor.next(observedAggregateIds.size)],
            blackhole = blackhole,
        )
    }

    private fun sendAndWaitProcessed(
        aggregateId: String,
        blackhole: Blackhole,
    ) {
        blackhole.consumeWowResult(onError = { failures.incrementAndGet() }) {
            scenario.commandGateway
                .sendAndWaitForProcessed(BenchmarkCommands.commandPathAddCartItem(aggregateId))
                .block()
        }
    }
}

@State(Scope.Thread)
open class ObservedAggregateCursor {
    private var sequence: Int = 0

    @Setup(Level.Iteration)
    fun setup(threadParams: ThreadParams) {
        sequence = threadParams.subgroupThreadIndex
    }

    fun next(cardinality: Int): Int = Math.floorMod(sequence++, cardinality)
}

private class OneSlowAggregateCpuCommandHandler(
    private val delegate: CommandHandler,
    private val slowAggregateId: String,
    private val slowCpuTokens: Long,
) : CommandHandler {
    override fun handle(context: ServerCommandExchange<*>): Mono<Void> {
        if (context.message.aggregateId.id == slowAggregateId && slowCpuTokens > 0) {
            Blackhole.consumeCPU(slowCpuTokens)
        }
        return delegate.handle(context)
    }
}
