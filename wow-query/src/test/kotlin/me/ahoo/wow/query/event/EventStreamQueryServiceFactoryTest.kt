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
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EventStreamQueryServiceFactoryTest {
    @Test
    fun `should share service for equivalent aggregate decorators`() {
        val factory = RecordingEventStreamQueryServiceFactory()

        val first = factory.create(TestNamedAggregateDecorator(MOCK_AGGREGATE_METADATA))
        val second = factory.create(TestNamedAggregateDecorator(MOCK_AGGREGATE_METADATA))

        first.assert().isSameAs(second)
        factory.createCount.get().assert().isEqualTo(1)
    }

    @Test
    fun `should create service once under concurrent access`() {
        val factory = RecordingEventStreamQueryServiceFactory()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..32).map {
                executor.submit<EventStreamQueryService> {
                    start.await()
                    factory.create(TestNamedAggregateDecorator(MOCK_AGGREGATE_METADATA))
                }
            }

            start.countDown()
            val services = futures.map { it.get(10, TimeUnit.SECONDS) }

            services.all { it === services.first() }.assert().isTrue()
            factory.createCount.get().assert().isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }
}

private class RecordingEventStreamQueryServiceFactory : AbstractEventStreamQueryServiceFactory() {
    val createCount = AtomicInteger()

    override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService {
        createCount.incrementAndGet()
        return NoOpEventStreamQueryService(namedAggregate)
    }
}

private class TestNamedAggregateDecorator(
    override val namedAggregate: NamedAggregate,
) : NamedAggregateDecorator
