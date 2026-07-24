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
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.commandSentSignal
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.NoopEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.infra.sink.concurrent
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.materialize
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.util.concurrent.atomic.AtomicInteger

@State(Scope.Benchmark)
open class CommandIngressE2EDiagnosticBenchmark {
    @Param(
        "current-production",
        "legacy-lock",
    )
    lateinit var ingressStrategy: String

    @Param("cpu")
    lateinit var schedulerPoolSize: String

    @Param("default")
    lateinit var stripeCount: String

    /**
     * `legacy-per-message-atomic` is a benchmark-only proxy that adds the removed
     * dispatcher-level AtomicInteger increment/decrement around every command.
     * It does not reconstruct the former AggregateDispatcher operator topology.
     */
    @Param("group-lifecycle")
    lateinit var taskCounterStrategy: String

    /**
     * Benchmark-only CPU budget executed on the aggregate scheduler worker.
     */
    @Param("0")
    lateinit var handlerCpuTokens: String

    /**
     * `legacy-successful-sent-notify-proxy` restores the successful SENT notification
     * that the former last-result wait path delivered before its actual target signal.
     *
     * The proxy routes through WaitCoordinator because the gateway's registered handle
     * is private. It therefore includes an extra doOnSuccess operator and coordinator
     * lookup that the former direct handle call did not, and must be interpreted together
     * with an exact pre/post run.
     */
    @Param("skip-unobservable-successful-sent")
    lateinit var lastWaitSentSignalStrategy: String

    private lateinit var scenario: CommandDispatcherScenario
    private val failures = AtomicInteger()
    private var legacyActiveTaskCounter: AtomicInteger? = null

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        legacyActiveTaskCounter = null
        val resolvedStripeCount = resolveStripeCount()
        val waitCoordinator = DefaultWaitCoordinator()
        val commandBus = createCommandBus()
            .decorateSuccessfulSentSignal(waitCoordinator)
        scenario = CommandDispatcherScenario.create(
            commandBus = commandBus,
            eventStore = NoopEventStore,
            snapshotRepository = InMemorySnapshotStore(),
            domainEventBus = InMemoryDomainEventBus(),
            stateEventBus = InMemoryStateEventBus(),
            schedulerSupplier = BenchmarkAggregateSchedulerSupplier(resolveSchedulerPoolSize()),
            stripeCount = resolvedStripeCount,
            validator = NoOpValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider {
                NoOpIdempotencyChecker
            },
            namedAggregate = BenchmarkAggregates.cartMetadata.namedAggregate.materialize(),
            commandHandlerDecorator = ::decorateCommandHandler,
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator),
        )
        check(scenario.commandDispatcher.parallelism == resolvedStripeCount) {
            "Command dispatcher stripeCount was not applied."
        }
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
        val activeTaskCount = legacyActiveTaskCounter?.get()
        check(activeTaskCount == null || activeTaskCount == 0) {
            "Legacy active task counter did not settle: $activeTaskCount."
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

    private fun CommandBus.decorateSuccessfulSentSignal(
        waitCoordinator: WaitCoordinator,
    ): CommandBus =
        when (lastWaitSentSignalStrategy) {
            "skip-unobservable-successful-sent" -> this
            "legacy-successful-sent-notify-proxy" ->
                LegacySuccessfulSentSignalCommandBus(this, waitCoordinator)

            else -> error(
                "Unsupported last wait SENT signal strategy: $lastWaitSentSignalStrategy"
            )
        }

    private fun resolveSchedulerPoolSize(): Int =
        when (schedulerPoolSize) {
            "cpu" -> Schedulers.DEFAULT_POOL_SIZE
            else -> schedulerPoolSize.toInt().also {
                require(it > 0) {
                    "schedulerPoolSize must be greater than 0."
                }
            }
        }

    private fun resolveStripeCount(): Int =
        when (stripeCount) {
            "default" -> MessageParallelism.DEFAULT_PARALLELISM
            else -> stripeCount.toInt().also {
                require(it > 0) {
                    "stripeCount must be greater than 0."
                }
            }
        }

    private fun decorateCommandHandler(delegate: CommandHandler): CommandHandler =
        when (taskCounterStrategy) {
            "group-lifecycle" -> decorateWithCpuCost(delegate)
            "legacy-per-message-atomic" -> {
                val activeTaskCounter = AtomicInteger()
                legacyActiveTaskCounter = activeTaskCounter
                LegacyPerMessageTaskCounterCommandHandler(
                    decorateWithCpuCost(delegate),
                    activeTaskCounter,
                )
            }

            else -> error("Unsupported task counter strategy: $taskCounterStrategy")
        }

    private fun decorateWithCpuCost(delegate: CommandHandler): CommandHandler {
        val cpuTokens = handlerCpuTokens.toLong().also {
            require(it >= 0) {
                "handlerCpuTokens must not be negative."
            }
        }
        return if (cpuTokens == 0L) {
            delegate
        } else {
            SimulatedCpuCommandHandler(delegate, cpuTokens)
        }
    }

    private fun createCommandMessage(): CommandMessage<AddCartItem> =
        BenchmarkCommands.commandPathAddCartItem()
}

/**
 * Benchmark-only proxy for AggregateDispatcher's former hot-path task counter.
 *
 * This decorator reproduces the shared per-message atomic operations, but adds its own
 * defer/finally wrapper and therefore is not a complete old/new dispatcher comparison.
 */
private class LegacyPerMessageTaskCounterCommandHandler(
    private val delegate: CommandHandler,
    private val activeTaskCounter: AtomicInteger,
) : CommandHandler {
    override fun handle(context: ServerCommandExchange<*>): Mono<Void> {
        activeTaskCounter.incrementAndGet()
        return Mono.defer {
            delegate.handle(context)
        }.doFinally {
            activeTaskCounter.decrementAndGet()
        }
    }
}

private class LegacySuccessfulSentSignalCommandBus(
    private val delegate: CommandBus,
    private val waitCoordinator: WaitCoordinator,
) : CommandBus by delegate {
    override fun send(message: CommandMessage<*>): Mono<Void> =
        delegate.send(message)
            .doOnSuccess {
                waitCoordinator.signal(message.commandSentSignal(message.commandId))
            }
}

private class SimulatedCpuCommandHandler(
    private val delegate: CommandHandler,
    private val cpuTokens: Long,
) : CommandHandler {
    override fun handle(context: ServerCommandExchange<*>): Mono<Void> {
        Blackhole.consumeCPU(cpuTokens)
        return delegate.handle(context)
    }
}
