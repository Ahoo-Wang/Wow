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

package me.ahoo.wow.scheduler

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import me.ahoo.wow.messaging.dispatcher.ParallelismCapable
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap

/**
 * Functional interface for supplying Reactor schedulers for aggregate operations.
 *
 * This interface provides a way to obtain or create schedulers that are specifically
 * dedicated to handling operations for particular aggregates. Schedulers can be shared
 * across operations for the same aggregate or created per aggregate as needed.
 *
 * The supplier pattern allows for lazy initialization and caching of schedulers,
 * improving performance by reusing schedulers for the same aggregate operations.
 *
 * @see Scheduler
 * @see NamedAggregate
 */
interface AggregateSchedulerSupplier : GracefullyStoppable {
    /**
     * Gets an existing scheduler for the named aggregate or creates a new one if none exists.
     *
     * This method should return a scheduler that is appropriate for handling operations
     * related to the specified aggregate. Implementations may choose to cache schedulers
     * to improve performance and resource utilization.
     *
     * Example usage:
     * ```kotlin
     * val supplier = DefaultAggregateSchedulerSupplier("my-app")
     * val scheduler = supplier.getOrInitialize(namedAggregate)
     *
     * Mono.just("data")
     *     .publishOn(scheduler)
     *     .map { process(it) }
     *     .subscribe()
     * ```
     *
     * @param namedAggregate the aggregate for which to get or create a scheduler
     * @return a scheduler dedicated to operations for this aggregate
     * @see DefaultAggregateSchedulerSupplier
     */
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
}

/**
 * Default implementation of AggregateSchedulerSupplier that creates dedicated schedulers for each aggregate.
 *
 * This implementation maintains a cache of schedulers, creating a new parallel scheduler
 * for each unique aggregate on first access. Subsequent requests for the same aggregate
 * return the cached scheduler, ensuring consistent thread allocation and resource reuse.
 *
 * The scheduler names follow the pattern "{supplier-name}-{aggregate-name}" for easy
 * identification in thread dumps and monitoring tools.
 *
 * @property name the name of this scheduler supplier, used as a prefix for scheduler names
 *
 * Example usage:
 * ```kotlin
 * val supplier = DefaultAggregateSchedulerSupplier("order-service")
 *
 * // First call creates a new scheduler named "order-service-Order"
 * val orderScheduler = supplier.getOrInitialize(orderAggregate)
 *
 * // Second call for the same aggregate returns the cached scheduler
 * val sameScheduler = supplier.getOrInitialize(orderAggregate)
 * assert(orderScheduler === sameScheduler) // true
 *
 * // Different aggregate gets its own scheduler
 * val userScheduler = supplier.getOrInitialize(userAggregate)
 * // Named "order-service-User"
 * ```
 *
 * @see AggregateSchedulerSupplier
 * @see Schedulers.newParallel
 */
class DefaultAggregateSchedulerSupplier(
    override val name: String,
    override val parallelism: Int = Schedulers.DEFAULT_POOL_SIZE
) : AggregateSchedulerSupplier,
    ParallelismCapable,
    Named {
    /**
     * Thread-safe cache of schedulers keyed by materialized aggregate.
     *
     * Uses ConcurrentHashMap to ensure safe concurrent access when multiple threads
     * request schedulers for the same or different aggregates simultaneously.
     */
    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    /**
     * Gets the cached scheduler for the aggregate or creates a new parallel scheduler.
     *
     * This method implements lazy initialization with caching. If a scheduler already
     * exists for the given aggregate, it returns the cached instance. Otherwise, it
     * creates a new parallel scheduler with a descriptive name.
     *
     * @param namedAggregate the aggregate for which to get or create a scheduler
     * @return the dedicated scheduler for this aggregate
     */
    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.newSingle("$name-${namedAggregate.aggregateName}")
//            Schedulers.newParallel("$name-${namedAggregate.aggregateName}", parallelism)
        }

    /**
     * Stops all schedulers gracefully.
     */
    override fun stopGracefully(): Mono<Void> {
        return Flux.fromIterable(schedulers.values).flatMap {
            it.disposeGracefully()
        }.then().doFinally {
            schedulers.clear()
        }
    }
}
