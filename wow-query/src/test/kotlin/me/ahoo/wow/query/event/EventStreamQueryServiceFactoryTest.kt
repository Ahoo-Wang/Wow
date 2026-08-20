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

package me.ahoo.wow.query.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EventStreamQueryServiceFactoryTest {

    @Test
    fun `should cache by materialized named aggregate`() {
        val factory = RecordingEventStreamQueryServiceFactory()

        val first = factory.create(DecoratedNamedAggregate(ORDER))
        val second = factory.create(ORDER)

        first.assert().isSameAs(second)
        first.namedAggregate.assert().isSameAs(ORDER)
        factory.creationCount.get().assert().isOne()
    }

    @Test
    fun `should create one service concurrently`() {
        val factory = RecordingEventStreamQueryServiceFactory()
        val executor = Executors.newFixedThreadPool(WORKER_COUNT)
        val ready = CountDownLatch(WORKER_COUNT)
        val start = CountDownLatch(1)
        try {
            val services = (1..WORKER_COUNT).map {
                executor.submit<EventStreamQueryService> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    factory.create(DecoratedNamedAggregate(ORDER))
                }
            }
            ready.await(5, TimeUnit.SECONDS).assert().isTrue()
            start.countDown()

            services.map { it.get(5, TimeUnit.SECONDS) }.toSet().assert().hasSize(1)
            factory.creationCount.get().assert().isOne()
        } finally {
            start.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    private data class DecoratedNamedAggregate(
        override val namedAggregate: NamedAggregate
    ) : NamedAggregateDecorator

    private class RecordingEventStreamQueryServiceFactory : AbstractEventStreamQueryServiceFactory() {
        val creationCount = AtomicInteger()

        override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService {
            creationCount.incrementAndGet()
            return NoOpEventStreamQueryService(namedAggregate)
        }
    }

    companion object {
        private const val WORKER_COUNT = 32
        private val ORDER = MaterializedNamedAggregate("order-service", "order")
    }
}
