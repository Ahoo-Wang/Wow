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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.metrics.metered
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import reactor.core.Disposable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The receive side of a metered bus: one domain event stream sent on an in-memory bus and received by one receiver
 * whose subscription covers [aggregates] aggregates of one bounded context, as a dispatcher's per-context receiver
 * does since 9.3.0. One operation is one stream, from `send` until the receiver has it.
 *
 * With [metrics] `on`, one aggregate takes the single-descriptor stream metrics; two take the per-aggregate ones
 * (each message counted under its own aggregate, active and termination series per aggregate), so the pair shows
 * what tagging a multi-aggregate receiver per aggregate costs per message. Messages alternate between the
 * subscribed aggregates.
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class ReceiveStreamMetricsComponentBenchmark {
    @Param("1", "2")
    var aggregates: Int = 1

    @Param("off", "on")
    lateinit var metrics: String

    private val sent = AtomicLong()
    private val received = AtomicLong()
    private lateinit var bus: DomainEventBus
    private lateinit var streams: Array<DomainEventStream>
    private lateinit var receiving: Disposable
    private var meterRegistry: SimpleMeterRegistry? = null
    private var cursor = 0

    @Setup(Level.Trial)
    fun setup() {
        require(aggregates in 1..CONTEXT_AGGREGATES.size) { "aggregates must be 1..${CONTEXT_AGGREGATES.size}." }
        val wowMetrics = when (metrics) {
            "off" -> WowMetrics.NONE
            "on" -> WowMetrics(SimpleMeterRegistry().also { meterRegistry = it })
            else -> error("Unsupported metrics: $metrics")
        }
        bus = InMemoryDomainEventBus().metered(wowMetrics, "domainEventBus")
        val subscribed: Set<NamedAggregate> = CONTEXT_AGGREGATES.take(aggregates).toCollection(LinkedHashSet())
        val receiver = bus.receiver(MessageSubscription(subscribed, "benchmark.ReceiveStream"))
        receiving = receiver.openedMessages().subscribe { received.incrementAndGet() }
        receiver.readiness.block()
        val targets = subscribed.toList()
        streams = Array(STREAM_RING_SIZE) { index ->
            BenchmarkEvents.singleEventStream(
                aggregateId = targets[index % targets.size].aggregateId("receive-$index"),
            )
        }
        receive()
        if (metrics == "on") {
            check(checkNotNull(meterRegistry).meters.isNotEmpty()) { "Metrics on must record meters." }
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        receiving.dispose()
        bus.close()
        meterRegistry?.close()
    }

    @Benchmark
    fun receive() {
        val target = sent.incrementAndGet()
        val stream = streams[cursor]
        cursor = (cursor + 1) % STREAM_RING_SIZE
        bus.send(stream).block()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RECEIVE_TIMEOUT_SECONDS)
        while (received.get() < target) {
            check(System.nanoTime() < deadline) { "The receiver did not get the stream in time." }
            Thread.onSpinWait()
        }
    }

    private companion object {
        val CONTEXT_AGGREGATES: List<NamedAggregate> = listOf(
            MaterializedNamedAggregate("example-service", "cart"),
            MaterializedNamedAggregate("example-service", "order"),
        )
        const val STREAM_RING_SIZE = 1024
        const val RECEIVE_TIMEOUT_SECONDS = 10L
    }
}
