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

package me.ahoo.wow.benchmark.scenario

import me.ahoo.wow.BenchmarkAggregateSchedulerSupplier
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

/**
 * Scheduler strategy for the dispatch chain's `publishOn`.
 *
 * - [PARALLEL]: production default — a dedicated `newParallel` pool. Each dispatch
 *   crosses from the emitting thread to a scheduler thread and back, modeling the real
 *   cross-thread round trip.
 * - [IMMEDIATE]: uses [Schedulers.immediate], so `publishOn` does not switch threads.
 *   Comparing [IMMEDIATE] against [PARALLEL] isolates the cross-thread handoff cost from
 *   the groupBy/concatMap dispatch-structure cost.
 */
enum class SchedulerStrategy {
    PARALLEL,
    IMMEDIATE,
}

/**
 * Maps a [SchedulerStrategy] to the [AggregateSchedulerSupplier] it implies, so every
 * end-to-end command-write benchmark can expose the scheduler's strategy and pool-size
 * impact without duplicating the wiring.
 *
 * - [SchedulerStrategy.PARALLEL] -> [BenchmarkAggregateSchedulerSupplier] (production default).
 * - [SchedulerStrategy.IMMEDIATE] -> [ImmediateAggregateSchedulerSupplier] (no thread switch).
 */
fun SchedulerStrategy.toSchedulerSupplier(
    schedulerPoolSize: Int = Schedulers.DEFAULT_POOL_SIZE,
): AggregateSchedulerSupplier =
    when (this) {
        SchedulerStrategy.PARALLEL -> BenchmarkAggregateSchedulerSupplier(schedulerPoolSize)
        SchedulerStrategy.IMMEDIATE -> ImmediateAggregateSchedulerSupplier
    }

/**
 * [AggregateSchedulerSupplier] backed by [Schedulers.immediate], so the dispatcher's
 * `publishOn` becomes a no-op and the full command-write round trip stays on the calling
 * thread. Comparing this against [BenchmarkAggregateSchedulerSupplier] isolates the
 * cross-thread handoff cost from the dispatch-structure cost.
 */
object ImmediateAggregateSchedulerSupplier : AggregateSchedulerSupplier {
    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler = Schedulers.immediate()

    @Suppress("ForbiddenVoid")
    override fun stopGracefully(): Mono<Void> = Mono.empty()
}
