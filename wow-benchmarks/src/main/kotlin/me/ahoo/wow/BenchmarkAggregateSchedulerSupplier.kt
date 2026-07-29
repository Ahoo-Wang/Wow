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

package me.ahoo.wow

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

class BenchmarkAggregateSchedulerSupplier(
    private val schedulerPoolSize: Int = Schedulers.DEFAULT_POOL_SIZE,
) : AggregateSchedulerSupplier {
    init {
        require(schedulerPoolSize > 0) {
            "schedulerPoolSize must be greater than 0."
        }
    }

    private val lifecycleMonitor = Any()
    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = mutableMapOf()
    private var terminalSchedulers: List<Scheduler>? = null
    private var stopped = false
    private val gracefulTermination: Mono<Void> =
        Flux.defer {
            Flux.fromIterable(closeAndSnapshot())
        }.flatMap(Scheduler::disposeGracefully)
            .then()
            .cache()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        synchronized(lifecycleMonitor) {
            check(!stopped) {
                "Benchmark aggregate scheduler supplier has stopped."
            }
            schedulers.getOrPut(namedAggregate.materialize()) {
                Schedulers.newParallel("BenchmarkAggregate-${namedAggregate.aggregateName}", schedulerPoolSize)
            }
        }

    @Suppress("ForbiddenVoid")
    override fun stopGracefully(): Mono<Void> = gracefulTermination

    override fun forceStop() {
        closeAndSnapshot().forEach(Scheduler::dispose)
    }

    private fun closeAndSnapshot(): List<Scheduler> =
        synchronized(lifecycleMonitor) {
            stopped = true
            terminalSchedulers ?: schedulers.values.toList().also { cachedSchedulers ->
                schedulers.clear()
                terminalSchedulers = cachedSchedulers
            }
        }
}
