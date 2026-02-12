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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class AggregateSchedulerSupplierTest {
    @Test
    fun getOrInitialize_should_return_same_scheduler_for_same_aggregate() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val orderAggregate = "test.Order".toNamedAggregate().materialize()

        val firstScheduler = supplier.getOrInitialize(orderAggregate)
        val secondScheduler = supplier.getOrInitialize(orderAggregate)
        firstScheduler.assert().isSameAs(secondScheduler)
    }

    @Test
    fun getOrInitialize_should_create_different_scheduler_for_different_aggregate() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val orderAggregate = "test.Order".toNamedAggregate().materialize()
        val userAggregate = "test.User".toNamedAggregate().materialize()

        val orderScheduler = supplier.getOrInitialize(orderAggregate)
        val userScheduler = supplier.getOrInitialize(userAggregate)
        orderScheduler.assert().isNotSameAs(userScheduler)
    }

    @Test
    fun getOrInitialize_should_use_default_parallelism() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        supplier.parallelism.assert().isEqualTo(Schedulers.DEFAULT_POOL_SIZE)
    }

    @Test
    fun getOrInitialize_should_use_custom_parallelism() {
        val customParallelism = 4
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier", customParallelism)
        val aggregate = "test.Test".toNamedAggregate().materialize()

        supplier.getOrInitialize(aggregate)

        supplier.parallelism.assert().isEqualTo(customParallelism)
    }

    @Test
    fun getOrInitialize_should_be_thread_safe() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val aggregate = "test.Order".toNamedAggregate().materialize()
        val concurrentCalls = 100
        val latch = CountDownLatch(concurrentCalls)
        val schedulers = ConcurrentHashMap<Int, Scheduler>()

        repeat(concurrentCalls) { i ->
            Thread {
                val scheduler = supplier.getOrInitialize(aggregate)
                schedulers.put(i, scheduler)
                latch.countDown()
            }.start()
        }

        latch.await(5, TimeUnit.SECONDS)

        val firstScheduler = schedulers[0]
        val allSame = schedulers.values.all { scheduler -> scheduler === firstScheduler }
        allSame.assert().isTrue()
    }

    @Test
    fun getOrInitialize_should_handle_concurrent_different_aggregates() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val aggregates = (1..50).map { "test.Aggregate$it".toNamedAggregate().materialize() }
        val latch = CountDownLatch(aggregates.size)
        val schedulers = ConcurrentHashMap<NamedAggregate, Scheduler>()

        aggregates.forEach { aggregate ->
            Thread {
                val scheduler = supplier.getOrInitialize(aggregate)
                schedulers.put(aggregate, scheduler)
                latch.countDown()
            }.start()
        }

        latch.await(10, TimeUnit.SECONDS)

        schedulers.size.assert().isEqualTo(aggregates.size)
    }

    @Test
    fun stopGracefully_should_dispose_all_schedulers() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val orderAggregate = "test.Order".toNamedAggregate().materialize()
        val userAggregate = "test.User".toNamedAggregate().materialize()

        supplier.getOrInitialize(orderAggregate)
        supplier.getOrInitialize(userAggregate)

        supplier.stopGracefully().block()

        Thread.sleep(100)

        val newOrderScheduler = supplier.getOrInitialize(orderAggregate)
        val newUserScheduler = supplier.getOrInitialize(userAggregate)

        Mono
            .fromCallable { 1 }
            .publishOn(newOrderScheduler)
            .block()

        Mono
            .fromCallable { 1 }
            .publishOn(newUserScheduler)
            .block()
    }

    @Test
    fun stopGracefully_should_clear_scheduler_cache() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val aggregate = "test.Order".toNamedAggregate().materialize()

        supplier.getOrInitialize(aggregate)
        supplier.stopGracefully().block()

        val newScheduler = supplier.getOrInitialize(aggregate)

        Mono
            .fromCallable { true }
            .publishOn(newScheduler)
            .block()
    }

    @Test
    fun stopGracefully_should_be_idempotent() {
        val supplier = DefaultAggregateSchedulerSupplier("test-supplier")
        val aggregate = "test.Order".toNamedAggregate().materialize()

        supplier.getOrInitialize(aggregate)
        supplier.stopGracefully().block()
        supplier.stopGracefully().block()

        supplier.getOrInitialize(aggregate)
    }

    @Test
    fun name_should_return_supplier_name() {
        val supplierName = "my-supplier"
        val supplier = DefaultAggregateSchedulerSupplier(supplierName)

        supplier.name.assert().isEqualTo(supplierName)
    }
}
