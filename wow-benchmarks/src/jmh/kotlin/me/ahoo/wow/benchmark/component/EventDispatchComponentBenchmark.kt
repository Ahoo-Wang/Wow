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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.benchmark.fixture.BenchmarkKeyedExecutors
import me.ahoo.wow.benchmark.scenario.DiscardingDistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.LocalFirstDomainEventBus
import me.ahoo.wow.event.dispatcher.DefaultDomainEventHandler
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.event.dispatcher.DomainEventFunctionFilter
import me.ahoo.wow.event.dispatcher.DomainEventFunctionRegistrar
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.execution.KeyedExecutor
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.messaging.shouldLocalFirst
import me.ahoo.wow.metrics.MetricDecoratorFactory
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.metrics.metered
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.runtime.WowRuntime
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event consume path: one domain event stream sent through an in-process bus, received by a started
 * [DomainEventDispatcher], and handled by every one of [processors] event functions (each its own processor, as
 * separate projections or sagas would be). One operation is one stream, from `send` until the last function ran.
 *
 * Producers are JMH threads (the quick profile runs 1 and 4). [metrics] `on` wires the same decorators the starter's
 * `MetricsBeanPostProcessor` applies (bus and handler) and gives the dispatcher a [WowMetrics] on a
 * [SimpleMeterRegistry]. [bus] `local-first` puts a [LocalFirstDomainEventBus] (with a discarding distributed bus) in
 * front of the same in-memory bus, so the difference is the local-first send path (copies, delivery receipt,
 * admission wait).
 *
 * Audit 9.3.0 B §F5 / §F6 (design WP G2; X4 and X7 compare against this).
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class EventDispatchComponentBenchmark {
    @Param("1", "8")
    var processors: Int = 1

    @Param("off", "on")
    lateinit var metrics: String

    @Param("in-memory", "local-first")
    lateinit var bus: String

    private val namedAggregate: NamedAggregate = BenchmarkAggregates.namedAggregate
    private val pending = ConcurrentHashMap<String, PendingDispatch>()
    private val threadSequence = AtomicInteger()
    private lateinit var domainEventBus: DomainEventBus
    private lateinit var stateEventBus: InMemoryStateEventBus
    private lateinit var runtime: WowRuntime
    private var meterRegistry: SimpleMeterRegistry? = null
    private var discardingBus: DiscardingDistributedDomainEventBus? = null

    @Setup(Level.Trial)
    fun setup() {
        require(processors > 0) { "processors must be greater than zero." }
        val wowMetrics = when (metrics) {
            "off" -> WowMetrics.NONE
            "on" -> WowMetrics(SimpleMeterRegistry().also { meterRegistry = it })
            else -> error("Unsupported metrics: $metrics")
        }
        val rawBus: DomainEventBus = when (bus) {
            "in-memory" -> InMemoryDomainEventBus()
            "local-first" -> LocalFirstDomainEventBus(
                distributedBus = DiscardingDistributedDomainEventBus().also { discardingBus = it },
                localBus = InMemoryDomainEventBus(),
            )

            else -> error("Unsupported bus: $bus")
        }
        domainEventBus = rawBus.metered(wowMetrics, "domainEventBus")
        stateEventBus = InMemoryStateEventBus()
        val registrar = DomainEventFunctionRegistrar()
        repeat(processors) { index ->
            registrar.register(CompletingEventFunction("processor$index", namedAggregate, pending))
        }
        val chain = FilterChainBuilder<DomainEventExchange<*>>()
            .addFilter(RetryableFilter())
            .addFilter(DomainEventFunctionFilter(SimpleServiceProvider()))
            .build()
        val eventHandler = MetricDecoratorFactory(wowMetrics)
            .decorate(DefaultDomainEventHandler(chain), "eventDispatcherHandler") as DomainEventHandler
        val dispatcher = DomainEventDispatcher(
            name = "benchmark.DomainEventDispatcher",
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            functionRegistrar = registrar,
            eventHandler = eventHandler,
            metrics = wowMetrics,
        )
        // The runtime owns its KeyedExecutor (one mailbox per aggregate ID on CPU-sized workers) and closes it on stop.
        runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(30),
            shutdownQuietPeriod = Duration.ZERO,
            keyedExecutor = BenchmarkKeyedExecutors.create(),
        )
        runtime.start().block()
        val probe = ProducerState().also { it.setup(this) }
        check(dispatchAndAwait(probe)) { "Probe event was not handled by $processors processor(s)." }
        if (bus == "local-first") {
            check(probe.peek().shouldLocalFirst()) { "Benchmark aggregate must be local for local-first routing." }
            // The distributed copy is sent asynchronously after the local hand-off (X5), so wait for it.
            val sent = checkNotNull(discardingBus).sent
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISPATCH_TIMEOUT_SECONDS)
            while (sent.sum() < 1L && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            check(sent.sum() == 1L) { "Local-first must still send the distributed copy." }
        }
        if (metrics == "on") {
            check(checkNotNull(meterRegistry).meters.isNotEmpty()) { "Metrics on must record meters." }
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        try {
            runtime.stopGracefully().block(Duration.ofSeconds(30))
        } finally {
            domainEventBus.close()
            stateEventBus.close()
            meterRegistry?.close()
        }
    }

    @Benchmark
    fun dispatchToProcessors(producer: ProducerState, blackhole: Blackhole) {
        if (!dispatchAndAwait(producer)) {
            throw IllegalStateException("Event dispatch timed out for $processors processor(s).")
        }
        blackhole.consume(producer)
    }

    private fun dispatchAndAwait(producer: ProducerState): Boolean {
        val eventStream = producer.next()
        val key = eventStream.aggregateId.id
        val dispatch = PendingDispatch(processors)
        pending[key] = dispatch
        try {
            domainEventBus.send(eventStream).block()
            dispatch.done.get(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return true
        } catch (timeout: TimeoutException) {
            return false
        } finally {
            pending.remove(key)
        }
    }

    /**
     * One producer thread's ring of pre-built event streams, each with its own aggregate id, so building messages
     * stays out of the measured path and the dispatcher sees many aggregates.
     */
    @State(Scope.Thread)
    open class ProducerState {
        private lateinit var streams: Array<DomainEventStream>
        private var cursor = 0

        @Setup(Level.Trial)
        fun setup(benchmark: EventDispatchComponentBenchmark) {
            val producer = benchmark.threadSequence.getAndIncrement()
            streams = Array(STREAM_RING_SIZE) { index ->
                BenchmarkEvents.singleEventStream(
                    aggregateId = BenchmarkAggregates.cartMetadata.aggregateId("dispatch-$producer-$index"),
                )
            }
        }

        fun peek(): DomainEventStream = streams[(cursor - 1 + STREAM_RING_SIZE) % STREAM_RING_SIZE]

        fun next(): DomainEventStream {
            val stream = streams[cursor]
            cursor = (cursor + 1) % STREAM_RING_SIZE
            return stream
        }
    }

    private class PendingDispatch(processors: Int) {
        val remaining = AtomicInteger(processors)
        val done = CompletableFuture<Unit>()
    }

    private class CompletingEventFunction(
        override val name: String,
        private val topic: NamedAggregate,
        private val pending: ConcurrentHashMap<String, PendingDispatch>,
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val supportedType: Class<*> = CartItemAdded::class.java
        override val supportedTopics: Set<NamedAggregate> = setOf(topic)
        override val processor: Any = this
        override val functionKind: FunctionKind = FunctionKind.EVENT
        override val contextName: String = topic.contextName

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.fromRunnable<Unit> {
            val dispatch = pending[exchange.message.aggregateId.id] ?: return@fromRunnable
            if (dispatch.remaining.decrementAndGet() == 0) {
                dispatch.done.complete(Unit)
            }
        }
    }

    private companion object {
        const val STREAM_RING_SIZE = 1024
        const val DISPATCH_TIMEOUT_SECONDS = 10L
    }
}
