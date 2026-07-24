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

import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregateIdPlan
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.scenario.DISPATCH_CHAIN_COMPLETION_KEY
import me.ahoo.wow.benchmark.scenario.DispatchedExchange
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.infra.sink.concurrent
import me.ahoo.wow.messaging.dispatcher.MessageParallelism
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.command.dispatcher.AggregateCommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Measures the steady-state dispatch cost of the current per-named-aggregate Scheduler topology
 * against a benchmark-only role-shared fixed pool.
 *
 * Every logical aggregate type owns the same real [AggregateCommandDispatcher] chain used by the
 * command runtime. To keep the work identical across logical types, all chains use the Cart
 * metadata and noop completion handler; distinct [MaterializedNamedAggregate] keys are used only
 * when asking the Scheduler supplier for an executor. This isolates Scheduler topology from
 * command-body, reflection, event-store, and handler differences.
 *
 * [MultiAggregateSchedulerTopology.DEDICATED_PER_AGGREGATE] mirrors production: `A` logical types
 * can create `A × P` worker threads. [MultiAggregateSchedulerTopology.SHARED_ROLE] is a diagnostic
 * counterfactual with one `P`-worker pool. This benchmark intentionally keeps the configured
 * per-Scheduler pool size equal, so its multi-type cells reproduce the resource amplification of
 * the current configuration rather than isolate topology at an equal worker budget. The companion
 * [MultiAggregateFixedWorkerBudgetComponentBenchmark] supplies that causal control.
 *
 * Neither benchmark is a production recommendation: shared-pool fairness and noisy-neighbor
 * behavior require separate heterogeneous-workload evidence.
 */
@State(Scope.Benchmark)
open class MultiAggregateSchedulerComponentBenchmark {
    @Param("1", "4", "16")
    var aggregateTypeCount: Int = 1

    @Param("DEDICATED_PER_AGGREGATE", "SHARED_ROLE")
    lateinit var schedulerTopology: String

    @Param("4")
    var schedulerPoolSize: Int = 4

    @Param("256")
    var aggregateIdCardinality: Int = 256

    @Param("default")
    lateinit var stripeCount: String

    private lateinit var scenario: MultiAggregateSchedulerScenario

    @Setup(Level.Trial)
    fun setup() {
        scenario = MultiAggregateSchedulerScenario.create(
            aggregateTypeCount = aggregateTypeCount,
            schedulerTopology =
                MultiAggregateSchedulerTopology.valueOf(schedulerTopology),
            schedulerPoolSize = schedulerPoolSize,
            aggregateIdCardinality = aggregateIdCardinality,
            stripeCount = resolveStripeCount(),
        )
        println(
            "# MultiAggregateScheduler topology=$schedulerTopology, " +
                "aggregateTypes=$aggregateTypeCount, poolSize=$schedulerPoolSize, " +
                "distinctSchedulers=${scenario.distinctSchedulerCount}, " +
                "observedWorkerThreads=${scenario.observedWorkerThreadCount}"
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        scenario.close()
    }

    @Benchmark
    fun dispatchAcrossAggregateTypes(
        route: MultiAggregateRoute,
        blackhole: Blackhole,
    ) {
        val aggregateType = route.nextAggregateType(aggregateTypeCount)
        val dispatched = scenario.nextExchange(
            aggregateType = aggregateType,
            messageIndex = route.nextMessageIndex(aggregateIdCardinality),
        )
        scenario.emit(aggregateType, dispatched.exchange)
        blackhole.consume(dispatched.await().block())
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
}

/**
 * Controls for total Scheduler worker capacity while changing only ownership topology.
 *
 * The role budget defaults to the host's observed 14 logical processors. Aggregate counts are
 * divisors of that budget, so the dedicated topology receives `B / A` workers per Scheduler while
 * the shared topology receives one `B`-worker Scheduler. Both topologies therefore expose exactly
 * `B` workers in every non-negative-control cell.
 */
@State(Scope.Benchmark)
open class MultiAggregateFixedWorkerBudgetComponentBenchmark {
    @Param("1", "2", "7", "14")
    var aggregateTypeCount: Int = 1

    @Param("DEDICATED_PER_AGGREGATE", "SHARED_ROLE")
    lateinit var schedulerTopology: String

    @Param("14")
    var roleWorkerBudget: Int = 14

    @Param("256")
    var aggregateIdCardinality: Int = 256

    @Param("default")
    lateinit var stripeCount: String

    private lateinit var scenario: MultiAggregateSchedulerScenario

    @Setup(Level.Trial)
    fun setup() {
        require(roleWorkerBudget % aggregateTypeCount == 0) {
            "roleWorkerBudget[$roleWorkerBudget] must be divisible by " +
                "aggregateTypeCount[$aggregateTypeCount]."
        }
        val resolvedTopology =
            MultiAggregateSchedulerTopology.valueOf(schedulerTopology)
        val perSchedulerPoolSize =
            when (resolvedTopology) {
                MultiAggregateSchedulerTopology.DEDICATED_PER_AGGREGATE ->
                    roleWorkerBudget / aggregateTypeCount

                MultiAggregateSchedulerTopology.SHARED_ROLE -> roleWorkerBudget
            }
        scenario = MultiAggregateSchedulerScenario.create(
            aggregateTypeCount = aggregateTypeCount,
            schedulerTopology = resolvedTopology,
            schedulerPoolSize = perSchedulerPoolSize,
            aggregateIdCardinality = aggregateIdCardinality,
            stripeCount = resolveStripeCount(),
        )
        check(scenario.observedWorkerThreadCount == roleWorkerBudget) {
            "Fixed-budget topology[$schedulerTopology] exposed " +
                "${scenario.observedWorkerThreadCount} worker(s), " +
                "expected roleWorkerBudget[$roleWorkerBudget]."
        }
        println(
            "# MultiAggregateFixedWorkerBudget topology=$schedulerTopology, " +
                "aggregateTypes=$aggregateTypeCount, roleWorkerBudget=$roleWorkerBudget, " +
                "perSchedulerPoolSize=$perSchedulerPoolSize, " +
                "distinctSchedulers=${scenario.distinctSchedulerCount}, " +
                "observedWorkerThreads=${scenario.observedWorkerThreadCount}"
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        scenario.close()
    }

    @Benchmark
    fun dispatchAcrossAggregateTypes(
        route: MultiAggregateRoute,
        blackhole: Blackhole,
    ) {
        val aggregateType = route.nextAggregateType(aggregateTypeCount)
        val dispatched = scenario.nextExchange(
            aggregateType = aggregateType,
            messageIndex = route.nextMessageIndex(aggregateIdCardinality),
        )
        scenario.emit(aggregateType, dispatched.exchange)
        blackhole.consume(dispatched.await().block())
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
}

/**
 * Two-type, equal-total-worker diagnostic for the work-conservation/isolation trade-off.
 *
 * Both topologies own two real dispatcher chains and expose two Scheduler workers in total:
 * dedicated uses `1 + 1`, shared uses one pool of `2`. The balanced group has four producers per
 * type, while the skewed group has one type-A and seven type-B producers. Optional CPU work is
 * applied only to type A, so type-B secondary throughput/latency exposes noisy-neighbor effects.
 *
 * This is deliberately closed loop. It can rank topology candidates under equal resources, but it
 * cannot establish production capacity or queueing latency without a bounded open-loop follow-up.
 */
@State(Scope.Benchmark)
open class MultiAggregateSchedulerIsolationBenchmark {
    @Param("DEDICATED_PER_AGGREGATE", "SHARED_ROLE")
    lateinit var schedulerTopology: String

    @Param("0", "100000")
    var typeACpuTokens: Long = 0

    @Param("2")
    var roleWorkerBudget: Int = 2

    private lateinit var scenario: MultiAggregateSchedulerScenario

    @Setup(Level.Trial)
    fun setup() {
        val aggregateTypeCount = 2
        require(roleWorkerBudget % aggregateTypeCount == 0) {
            "roleWorkerBudget[$roleWorkerBudget] must be divisible by $aggregateTypeCount."
        }
        val resolvedTopology =
            MultiAggregateSchedulerTopology.valueOf(schedulerTopology)
        val perSchedulerPoolSize =
            when (resolvedTopology) {
                MultiAggregateSchedulerTopology.DEDICATED_PER_AGGREGATE ->
                    roleWorkerBudget / aggregateTypeCount

                MultiAggregateSchedulerTopology.SHARED_ROLE -> roleWorkerBudget
            }
        scenario = MultiAggregateSchedulerScenario.create(
            aggregateTypeCount = aggregateTypeCount,
            schedulerTopology = resolvedTopology,
            schedulerPoolSize = perSchedulerPoolSize,
            aggregateIdCardinality = ISOLATION_AGGREGATE_ID_CARDINALITY,
            stripeCount = ISOLATION_STRIPE_COUNT,
            slowAggregateType = 0,
            slowHandlerCpuTokens = typeACpuTokens,
            prewarmAllGroups = true,
        )
        check(scenario.observedWorkerThreadCount == roleWorkerBudget) {
            "Isolation topology[$schedulerTopology] exposed " +
                "${scenario.observedWorkerThreadCount} worker(s), " +
                "expected roleWorkerBudget[$roleWorkerBudget]."
        }
        println(
            "# MultiAggregateSchedulerIsolation topology=$schedulerTopology, " +
                "typeACpuTokens=$typeACpuTokens, roleWorkerBudget=$roleWorkerBudget, " +
                "perSchedulerPoolSize=$perSchedulerPoolSize, " +
                "distinctSchedulers=${scenario.distinctSchedulerCount}, " +
                "observedWorkerThreads=${scenario.observedWorkerThreadCount}"
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        scenario.close()
    }

    @Benchmark
    @Group("balanced")
    @GroupThreads(4)
    fun balancedTypeA(
        route: MultiAggregateGroupRoute,
        blackhole: Blackhole,
    ) {
        dispatch(aggregateType = 0, route, blackhole)
    }

    @Benchmark
    @Group("balanced")
    @GroupThreads(4)
    fun balancedTypeB(
        route: MultiAggregateGroupRoute,
        blackhole: Blackhole,
    ) {
        dispatch(aggregateType = 1, route, blackhole)
    }

    @Benchmark
    @Group("skewed")
    @GroupThreads(1)
    fun skewedTypeA(
        route: MultiAggregateGroupRoute,
        blackhole: Blackhole,
    ) {
        dispatch(aggregateType = 0, route, blackhole)
    }

    @Benchmark
    @Group("skewed")
    @GroupThreads(7)
    fun skewedTypeB(
        route: MultiAggregateGroupRoute,
        blackhole: Blackhole,
    ) {
        dispatch(aggregateType = 1, route, blackhole)
    }

    private fun dispatch(
        aggregateType: Int,
        route: MultiAggregateGroupRoute,
        blackhole: Blackhole,
    ) {
        val dispatched = scenario.nextExchange(
            aggregateType = aggregateType,
            messageIndex =
                route.nextMessageIndex(ISOLATION_AGGREGATE_ID_CARDINALITY),
        )
        scenario.emit(aggregateType, dispatched.exchange)
        blackhole.consume(dispatched.await().block())
    }
}

private const val ISOLATION_AGGREGATE_ID_CARDINALITY = 4
private const val ISOLATION_STRIPE_COUNT = 16

@State(Scope.Thread)
open class MultiAggregateRoute {
    private var sequence: Long = 0

    @Setup(Level.Trial)
    fun setup(threadParams: ThreadParams) {
        sequence = threadParams.threadIndex.toLong()
    }

    fun nextAggregateType(aggregateTypeCount: Int): Int {
        val selected = (sequence % aggregateTypeCount).toInt()
        sequence++
        return selected
    }

    fun nextMessageIndex(aggregateIdCardinality: Int): Int =
        (sequence % aggregateIdCardinality).toInt()
}

@State(Scope.Thread)
open class MultiAggregateGroupRoute {
    private var sequence: Int = 0

    @Setup(Level.Trial)
    fun setup(threadParams: ThreadParams) {
        sequence = threadParams.subgroupThreadIndex
    }

    fun nextMessageIndex(aggregateIdCardinality: Int): Int =
        Math.floorMod(sequence++, aggregateIdCardinality)
}

private enum class MultiAggregateSchedulerTopology {
    DEDICATED_PER_AGGREGATE,
    SHARED_ROLE,
}

private class MultiAggregateSchedulerScenario(
    private val slots: List<MultiAggregateDispatcherSlot>,
    private val schedulerSupplier: AggregateSchedulerSupplier,
    val distinctSchedulerCount: Int,
    val observedWorkerThreadCount: Int,
) : AutoCloseable {
    fun nextExchange(
        aggregateType: Int,
        messageIndex: Int,
    ): DispatchedExchange {
        val commandMessage = slots[aggregateType].commandMessages[messageIndex]
        val completionSink = Sinks.empty<Void>()
        val exchange = SimpleServerCommandExchange(commandMessage)
        exchange.setAggregateMetadata(BenchmarkAggregates.cartMetadata)
        exchange.setAttribute(DISPATCH_CHAIN_COMPLETION_KEY, completionSink)
        return DispatchedExchange(exchange, completionSink)
    }

    fun emit(
        aggregateType: Int,
        exchange: ServerCommandExchange<*>,
    ) {
        slots[aggregateType].messageSink.tryEmitNext(exchange).orThrow()
    }

    override fun close() {
        try {
            Flux.fromIterable(slots)
                .flatMap { slot -> slot.dispatcher.stopGracefully() }
                .then()
                .block()
        } finally {
            schedulerSupplier.stopGracefully().block()
        }
    }

    companion object {
        fun create(
            aggregateTypeCount: Int,
            schedulerTopology: MultiAggregateSchedulerTopology,
            schedulerPoolSize: Int,
            aggregateIdCardinality: Int,
            stripeCount: Int,
            slowAggregateType: Int? = null,
            slowHandlerCpuTokens: Long = 0,
            prewarmAllGroups: Boolean = false,
        ): MultiAggregateSchedulerScenario {
            require(aggregateTypeCount > 0) {
                "aggregateTypeCount must be greater than 0."
            }
            require(schedulerPoolSize > 0) {
                "schedulerPoolSize must be greater than 0."
            }
            require(aggregateIdCardinality > 0) {
                "aggregateIdCardinality must be greater than 0."
            }
            require(stripeCount > 0) {
                "stripeCount must be greater than 0."
            }
            require(slowAggregateType == null || slowAggregateType in 0 until aggregateTypeCount) {
                "slowAggregateType[$slowAggregateType] must identify a configured aggregate type."
            }
            require(slowHandlerCpuTokens >= 0) {
                "slowHandlerCpuTokens must not be negative."
            }
            val schedulerSupplier =
                when (schedulerTopology) {
                    MultiAggregateSchedulerTopology.DEDICATED_PER_AGGREGATE ->
                        BenchmarkAggregateSchedulerSupplier(schedulerPoolSize)

                    MultiAggregateSchedulerTopology.SHARED_ROLE ->
                        SharedBenchmarkAggregateSchedulerSupplier(schedulerPoolSize)
                }
            val logicalAggregates = List(aggregateTypeCount) { aggregateType ->
                MaterializedNamedAggregate(
                    contextName = "benchmark-multi-aggregate",
                    aggregateName = "type-$aggregateType",
                )
            }
            val schedulers = logicalAggregates.map(schedulerSupplier::getOrInitialize)
            val uniqueSchedulers =
                Collections.newSetFromMap(IdentityHashMap<Scheduler, Boolean>())
                    .apply { addAll(schedulers) }
            val expectedSchedulerCount =
                when (schedulerTopology) {
                    MultiAggregateSchedulerTopology.DEDICATED_PER_AGGREGATE ->
                        aggregateTypeCount

                    MultiAggregateSchedulerTopology.SHARED_ROLE -> 1
                }
            check(uniqueSchedulers.size == expectedSchedulerCount) {
                "Scheduler topology[$schedulerTopology] created " +
                    "${uniqueSchedulers.size} scheduler(s), expected $expectedSchedulerCount."
            }
            val observedWorkerThreadCount =
                observeAllWorkerThreads(uniqueSchedulers, schedulerPoolSize)
            val expectedWorkerThreadCount = expectedSchedulerCount * schedulerPoolSize
            check(observedWorkerThreadCount == expectedWorkerThreadCount) {
                "Scheduler topology[$schedulerTopology] exposed " +
                    "$observedWorkerThreadCount worker thread(s), " +
                    "expected $expectedWorkerThreadCount."
            }
            val aggregateIdPlan = BenchmarkAggregateIdPlan.create(
                cardinality = aggregateIdCardinality.toString(),
                stripeCount = stripeCount,
            )
            check(
                aggregateIdPlan.activeStripes(stripeCount).size ==
                    minOf(aggregateIdCardinality, stripeCount)
            ) {
                "Aggregate ID plan does not activate the expected number of stripes."
            }
            val slots = schedulers.mapIndexed { aggregateType, scheduler ->
                val messageSink = Sinks.many()
                    .unicast()
                    .onBackpressureBuffer<ServerCommandExchange<*>>()
                    .concurrent()
                val commandMessages = List(aggregateIdCardinality) { aggregateIdIndex ->
                    val commandId =
                        "multi-aggregate-$aggregateType-$aggregateIdIndex"
                    AddCartItem(productId = "productId").toCommandMessage(
                        id = commandId,
                        requestId = commandId,
                        aggregateId = aggregateIdPlan.aggregateIds[aggregateIdIndex],
                        namedAggregate = BenchmarkAggregates.namedAggregate,
                    )
                }
                val handler = MultiAggregateDispatchHandler(
                    cpuTokens =
                        if (aggregateType == slowAggregateType) {
                            slowHandlerCpuTokens
                        } else {
                            0
                        },
                )
                @Suppress("UNCHECKED_CAST")
                val dispatcher = AggregateCommandDispatcher<Any, Any>(
                    name = "type-$aggregateType-AggregateCommandDispatcher",
                    aggregateMetadata =
                        BenchmarkAggregates.cartMetadata as
                            me.ahoo.wow.modeling.metadata.AggregateMetadata<Any, Any>,
                    messageFlux = messageSink.asFlux(),
                    parallelism = stripeCount,
                    commandHandler = handler,
                    scheduler = scheduler,
                )
                dispatcher.start()
                MultiAggregateDispatcherSlot(
                    dispatcher = dispatcher,
                    messageSink = messageSink,
                    commandMessages = commandMessages,
                )
            }
            val scenario = MultiAggregateSchedulerScenario(
                slots = slots,
                schedulerSupplier = schedulerSupplier,
                distinctSchedulerCount = uniqueSchedulers.size,
                observedWorkerThreadCount = observedWorkerThreadCount,
            )
            if (prewarmAllGroups) {
                slots.indices.forEach { aggregateType ->
                    repeat(aggregateIdCardinality) { messageIndex ->
                        val dispatched = scenario.nextExchange(
                            aggregateType = aggregateType,
                            messageIndex = messageIndex,
                        )
                        scenario.emit(aggregateType, dispatched.exchange)
                        dispatched.await().block()
                    }
                }
            }
            return scenario
        }

        private fun observeAllWorkerThreads(
            schedulers: Set<Scheduler>,
            schedulerPoolSize: Int,
        ): Int {
            val workers = schedulers.flatMap { scheduler ->
                List(schedulerPoolSize) { scheduler.createWorker() }
            }
            val completed = CountDownLatch(workers.size)
            val threadNames = ConcurrentHashMap.newKeySet<String>()
            try {
                workers.forEach { worker ->
                    worker.schedule {
                        threadNames.add(Thread.currentThread().name)
                        completed.countDown()
                    }
                }
                check(completed.await(10, TimeUnit.SECONDS)) {
                    "Timed out while activating ${workers.size} Scheduler workers."
                }
            } finally {
                workers.forEach(Scheduler.Worker::dispose)
            }
            return threadNames.size
        }
    }
}

private data class MultiAggregateDispatcherSlot(
    val dispatcher: AggregateCommandDispatcher<*, *>,
    val messageSink: Sinks.Many<ServerCommandExchange<*>>,
    val commandMessages: List<CommandMessage<*>>,
)

private class MultiAggregateDispatchHandler(
    private val cpuTokens: Long = 0,
) : CommandHandler {
    @Suppress("UNCHECKED_CAST")
    override fun handle(context: ServerCommandExchange<*>): Mono<Void> {
        if (cpuTokens > 0) {
            Blackhole.consumeCPU(cpuTokens)
        }
        val completionSink = checkNotNull(
            context.getAttribute(DISPATCH_CHAIN_COMPLETION_KEY) as? Sinks.Empty<Void>
        ) {
            "Dispatch-chain completion sink is missing."
        }
        return Mono.empty<Void>()
            .doFinally { completionSink.tryEmitEmpty().orThrow() }
    }
}

private class SharedBenchmarkAggregateSchedulerSupplier(
    schedulerPoolSize: Int,
) : AggregateSchedulerSupplier {
    private val scheduler =
        Schedulers.newParallel("BenchmarkSharedAggregate", schedulerPoolSize)

    override fun getOrInitialize(
        namedAggregate: me.ahoo.wow.api.modeling.NamedAggregate,
    ): Scheduler = scheduler

    @Suppress("ForbiddenVoid")
    override fun stopGracefully(): Mono<Void> =
        Mono.fromRunnable(scheduler::dispose)
}
