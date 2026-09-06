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

package me.ahoo.wow.redis.eventsourcing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveSetOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveZSetOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class RedisEventStoreRequestIdTest {
    private val aggregateId = MaterializedNamedAggregate("order-service", "order").aggregateId("order-1", "tenant-1")
    private val template = mockk<ReactiveStringRedisTemplate>()
    private val index = mockk<ReactiveSetOperations<String, String>>()
    private val events = mockk<ReactiveZSetOperations<String, String>>()
    private val store = RedisEventStore(template)

    @Test
    fun `missing candidates use one bulk membership check and no history read`() {
        every { template.opsForSet() } returns index
        every { template.opsForZSet() } returns events
        every { index.isMember(EventStreamKeyLayout.requestIndexKey(aggregateId), "current", "legacy") } returns
            Mono.just(mapOf<Any, Boolean>("current" to false, "legacy" to false))
        every { events.rangeByScore(any<String>(), any(), any()) } returns Flux.error(AssertionError("Unexpected history read"))

        StepVerifier.create(store.loadByRequestIds(aggregateId, linkedSetOf("current", "legacy"))).verifyComplete()
        verify(exactly = 1) { index.isMember(EventStreamKeyLayout.requestIndexKey(aggregateId), "current", "legacy") }
        verify(exactly = 0) { events.rangeByScore(any<String>(), any(), any()) }
    }

    @Test
    fun `matching candidates load history once and return only matching records`() {
        val wanted = generateEventStream(aggregateId, eventCount = 1)
        val other = generateEventStream(aggregateId, aggregateVersion = 1, eventCount = 1)
        every { template.opsForSet() } returns index
        every { template.opsForZSet() } returns events
        every { index.isMember(EventStreamKeyLayout.requestIndexKey(aggregateId), wanted.requestId, "missing") } returns
            Mono.just(mapOf<Any, Boolean>(wanted.requestId to true, "missing" to false))
        every {
            events.rangeByScore(any<String>(), any(), any())
        } returns Flux.just(wanted.toJsonString(), other.toJsonString())

        StepVerifier.create(store.loadByRequestIds(aggregateId, linkedSetOf(wanted.requestId, "missing")))
            .expectNextMatches { it.id == wanted.id }.verifyComplete()
        verify(exactly = 1) { events.rangeByScore(any<String>(), any(), any()) }
    }

    @Test
    fun `empty candidates skip Redis entirely`() {
        StepVerifier.create(store.loadByRequestIds(aggregateId, emptySet())).verifyComplete()
        verify(exactly = 0) {
            template.opsForSet()
            template.opsForZSet()
        }
    }
}
