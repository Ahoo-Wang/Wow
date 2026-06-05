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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier

internal class AggregateSchedulerSupplierBehaviorTest {

    @Test
    fun `supplier should expose configured name and parallelism`() {
        val supplier = DefaultAggregateSchedulerSupplier("worker", parallelism = 2)

        supplier.name.assert().isEqualTo("worker")
        supplier.parallelism.assert().isEqualTo(2)
    }

    @Test
    fun `supplier should use default Reactor parallelism when not configured`() {
        DefaultAggregateSchedulerSupplier("worker").parallelism.assert().isEqualTo(Schedulers.DEFAULT_POOL_SIZE)
    }

    @Test
    fun `supplier should cache by materialized named aggregate`() {
        val supplier = DefaultAggregateSchedulerSupplier("worker", parallelism = 1)
        val first = "sales.Order".toNamedAggregate()
        val same = "sales.Order".toNamedAggregate("ignored-context")
        val other = "sales.Invoice".toNamedAggregate()

        val firstScheduler = supplier.getOrInitialize(first)

        supplier.getOrInitialize(same).assert().isSameAs(firstScheduler)
        supplier.getOrInitialize(other).assert().isNotSameAs(firstScheduler)
        StepVerifier.create(supplier.stopGracefully()).verifyComplete()
    }

    @Test
    fun `supplier should initialize one cached scheduler under concurrent access`() {
        val supplier = DefaultAggregateSchedulerSupplier("worker", parallelism = 1)
        val aggregate = "sales.Order".toNamedAggregate()

        StepVerifier.create(
            Flux.range(0, 64)
                .parallel(8)
                .runOn(Schedulers.parallel())
                .map { System.identityHashCode(supplier.getOrInitialize(aggregate)) }
                .sequential()
                .collectList()
        )
            .assertNext { ids ->
                ids.toSet().assert().hasSize(1)
            }
            .verifyComplete()
        StepVerifier.create(supplier.stopGracefully()).verifyComplete()
    }

    @Test
    fun `supplier should create scheduler with supplier and aggregate name`() {
        val supplier = DefaultAggregateSchedulerSupplier("worker", parallelism = 1)
        val scheduler = supplier.getOrInitialize("sales.Order".toNamedAggregate())

        StepVerifier.create(Mono.fromCallable { Thread.currentThread().name }.subscribeOn(scheduler))
            .expectNextMatches { it.contains("worker-Order") }
            .verifyComplete()
        StepVerifier.create(supplier.stopGracefully()).verifyComplete()
    }

    @Test
    fun `stop gracefully should dispose cached schedulers and allow reinitialization`() {
        val supplier = DefaultAggregateSchedulerSupplier("worker", parallelism = 1)
        val aggregate = "sales.Order".toNamedAggregate()
        val scheduler = supplier.getOrInitialize(aggregate)

        StepVerifier.create(supplier.stopGracefully()).verifyComplete()

        scheduler.isDisposed.assert().isTrue()
        supplier.getOrInitialize(aggregate).assert().isNotSameAs(scheduler)
        StepVerifier.create(supplier.stopGracefully()).verifyComplete()
    }
}
