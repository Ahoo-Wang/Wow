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
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.mock.DelayEventStore
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sweeps the "simulated I/O latency vs cross-thread handoff" trade-off curve for
 * `sendAndWaitProcessed`, without requiring a real database.
 *
 * Uses [DelayEventStore] (in-memory store with an injected per-operation delay) so the
 * event-store I/O cost is a controlled variable. Combined with [schedulerStrategy], this
 * answers: how much I/O latency is needed before the dispatcher's `publishOn` cross-thread
 * handoff becomes negligible relative to the total command-write latency.
 *
 * The sweep fills the gap between the two previously measured extremes:
 * - NoopEventStore (0 I/O): cross-thread ~95% of dispatch-chain cost.
 * - MongoEventStore (~300 us I/O): cross-thread ~5%.
 *
 * @author ahoo wang
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class SimulatedIoCommandWriteBenchmark {
    @Param("0", "20us", "100us", "500us", "2ms")
    private var ioDelay: String = "0"

    @Param("PARALLEL", "IMMEDIATE")
    private var schedulerStrategy: String = SchedulerStrategy.PARALLEL.name

    private lateinit var commandDispatcherScenario: CommandDispatcherScenario
    private val failures = AtomicInteger()

    @Setup(Level.Iteration)
    fun setup() {
        failures.set(0)
        val delay = parseDelay(ioDelay)
        // Bypass DelayEventStore for the zero-delay row: delaySubscription(Duration.ZERO) still
        // schedules a timer task, which would add an unrelated reactor-scheduler handoff and
        // contaminate the "pure framework" baseline point this row is meant to provide.
        val eventStore = if (delay.isZero) {
            InMemoryEventStore()
        } else {
            DelayEventStore(delaySupplier = { delay })
        }
        commandDispatcherScenario = CommandDispatcherScenario.create(
            eventStore = eventStore,
            schedulerSupplier = SchedulerStrategy.valueOf(schedulerStrategy).toSchedulerSupplier(),
        )
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        val failureCount = failures.get()
        try {
            if (failureCount > 0) {
                throw IllegalStateException(
                    "Simulated I/O command write recorded $failureCount failure(s) " +
                        "[ioDelay=$ioDelay, schedulerStrategy=$schedulerStrategy].",
                )
            }
        } finally {
            commandDispatcherScenario.close()
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
        fun parseDelay(value: String): Duration =
            when {
                value == "0" -> Duration.ZERO
                value.endsWith("us") -> Duration.ofNanos(value.removeSuffix("us").toLong() * 1_000L)
                value.endsWith("ms") -> Duration.ofMillis(value.removeSuffix("ms").toLong())
                else -> error("Unsupported ioDelay value: $value (expected '0', '<n>us', or '<n>ms')")
            }
    }
}
