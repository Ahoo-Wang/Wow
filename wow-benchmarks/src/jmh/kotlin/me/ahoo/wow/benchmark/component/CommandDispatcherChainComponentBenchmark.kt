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

import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkIds
import me.ahoo.wow.benchmark.scenario.CommandDispatcherChainScenario
import me.ahoo.wow.benchmark.scenario.HandlerCost
import me.ahoo.wow.benchmark.scenario.SchedulerStrategy
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole

/**
 * Isolates the [me.ahoo.wow.modeling.command.dispatcher.AggregateCommandDispatcher] dispatch
 * chain (`groupBy` → `publishOn` → `concatMap`) from the surrounding command gateway, bus,
 * aggregate processor, and event-store paths.
 *
 * Each measured operation feeds one [me.ahoo.wow.command.ServerCommandExchange] into the
 * dispatcher's message flux and blocks until the dispatch chain has finished handling it.
 * The handler is a noop (or a bounded simulated-work variant), so the measured cost is the
 * dispatch-chain overhead itself — turning what was previously only inferable via an
 * end-to-end delta into a directly attributable measurement.
 *
 * The workload uses one aggregate ID, so each invocation measures a single hot aggregate
 * without claiming multi-group concurrency. Shared-chain contention requires a separate
 * benchmark with multiple outstanding messages on one dispatcher.
 *
 * Parameters:
 * - [handlerCost]: [HandlerCost.NOOP] measures pure dispatch overhead;
 *   [HandlerCost.SIMULATED] adds a small fixed CPU budget to reveal the dispatch share of
 *   end-to-end latency.
 * - [schedulerStrategy]: [SchedulerStrategy.PARALLEL] is the production default (dedicated
 *   `newParallel` pool, each dispatch crosses threads); [SchedulerStrategy.IMMEDIATE] uses
 *   `Schedulers.immediate()` so `publishOn` does not switch threads. Comparing the two
 *   isolates the cross-thread handoff cost from the groupBy/concatMap structure cost.
 *
 * Threading semantics: this benchmark uses [Scope.Thread], matching the other component
 * benchmarks. Each JMH worker thread gets its own `CommandDispatcherChainScenario` (its own
 * `AggregateCommandDispatcher` and scheduler pool). The single-thread (`-t 1`) rows are the
 * primary signal — they measure one dispatch chain's round-trip cost in isolation. Multi-
 * thread rows measure aggregate throughput of N independent dispatch chains, NOT contention
 * on a single shared chain; for shared-chain contention under realistic I/O, use the E2E
 * benchmarks (`CommandWriteE2EBenchmark` etc., which use [Scope.Benchmark]).
 *
 * @author ahoo wang
 */
@State(Scope.Thread)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class CommandDispatcherChainComponentBenchmark {
    @Param("NOOP", "SIMULATED")
    private var handlerCost: String = HandlerCost.NOOP.name

    @Param("PARALLEL", "IMMEDIATE")
    private var schedulerStrategy: String = SchedulerStrategy.PARALLEL.name

    private lateinit var scenario: CommandDispatcherChainScenario

    @Setup
    fun setup() {
        BenchmarkIds.installDeterministicGlobalIdGenerator()
        scenario = CommandDispatcherChainScenario.create(
            aggregateMetadata = BenchmarkAggregates.cartMetadata,
            aggregateIdCardinality = 1,
            handlerCost = HandlerCost.valueOf(handlerCost),
            schedulerStrategy = SchedulerStrategy.valueOf(schedulerStrategy),
        )
    }

    @TearDown
    fun tearDown() {
        scenario.close()
    }

    @Benchmark
    fun dispatchSingleHotAggregateThroughChain(blackhole: Blackhole) {
        val dispatched = scenario.nextExchange()
        scenario.messageSink.tryEmitNext(dispatched.exchange).orThrow()
        blackhole.consume(dispatched.await().block())
    }
}
