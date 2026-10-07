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
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

/**
 * One metered operation as the hot path records it, metrics on ([SimpleMeterRegistry]):
 *
 * - [monoWithSharedDescriptor]: a dispatcher's `handle` timer (descriptor built once per dispatcher);
 * - [monoWithPerCallDescriptor]: a bus or store decorator (descriptor built per message, as `MetricCommandBus.send`);
 * - [fluxWithPerCallDescriptor]: a store load (timer plus item summary);
 *
 * The sources complete synchronously, so the score is the metrics overhead plus one subscription.
 * Design WP X4 (meter cache).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class WowMetricsComponentBenchmark {
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: WowMetrics
    private lateinit var sharedDescriptor: MetricDescriptor
    private val source: Mono<String> = Mono.just("value")
    private val items: Flux<String> = Flux.just("one", "two")

    @Setup(Level.Trial)
    fun setup() {
        registry = SimpleMeterRegistry()
        metrics = WowMetrics(registry)
        sharedDescriptor = descriptor("dispatcher", "handle")
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        check(registry.meters.isNotEmpty()) { "Metrics must record meters." }
        registry.close()
    }

    @Benchmark
    fun monoWithSharedDescriptor(blackhole: Blackhole) {
        blackhole.consume(metrics.operation(source, sharedDescriptor).subscribe(blackhole::consume))
    }

    @Benchmark
    fun monoWithPerCallDescriptor(blackhole: Blackhole) {
        blackhole.consume(
            metrics.operation(source, descriptor("command_bus", "send")).subscribe(blackhole::consume),
        )
    }

    @Benchmark
    fun fluxWithPerCallDescriptor(blackhole: Blackhole) {
        blackhole.consume(
            metrics.operation(items, descriptor("event_store", "load_by_version")).subscribe(blackhole::consume),
        )
    }

    // A/B base for 9.3.0: `processingOutcome` is omitted; 9.2 has no `wow.processing.outcomes` counter.

    private fun descriptor(component: String, operation: String): MetricDescriptor = MetricDescriptor(
        component = component,
        operation = operation,
        context = CONTEXT,
        aggregate = AGGREGATE,
        message = MESSAGE,
        source = SOURCE,
    )

    private companion object {
        const val CONTEXT = "benchmark"
        const val AGGREGATE = "cart"
        const val MESSAGE = "cart_item_added"
        const val SOURCE = "benchmark"
    }
}
