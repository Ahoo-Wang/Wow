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
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AbstractEventStreamQueryServiceFactoryTest {
    @Test
    fun `concurrent first access creates one cached service`() {
        val creations = AtomicInteger()
        val factory = object : AbstractEventStreamQueryServiceFactory() {
            override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService {
                creations.incrementAndGet()
                repeat(1_000) { Thread.yield() }
                return NoOpEventStreamQueryService(namedAggregate)
            }
        }
        val target = "test.concurrent_factory".toNamedAggregate()
        val threadCount = 16
        val start = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val services = (1..threadCount).map {
                executor.submit<EventStreamQueryService> {
                    start.await()
                    factory.create(target)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            services.map(System::identityHashCode).toSet().assert().hasSize(1)
            creations.get().assert().isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }
}
