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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.ReactiveHashOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveZSetOperations
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class RedisEventStoreScanTest {

    @Test
    fun `append should pass aggregate id index fields to lua script`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val keysSlot = io.mockk.slot<List<String>>()
        val argumentsSlot = io.mockk.slot<List<*>>()
        val aggregateId = namedAggregate.aggregateId("order-1", tenantId = "tenant-1")
        val eventStream = generateEventStream(aggregateId, eventCount = 1)
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STEAM_APPEND,
                capture(keysSlot),
                capture(argumentsSlot),
            )
        } returns Flux.just("1")
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .verifyComplete()

        keysSlot.captured.assert().containsExactly("{order-1@tenant-1}")
        argumentsSlot.captured.assert().hasSize(7)
        argumentsSlot.captured.takeLast(2).assert().containsExactly("order-1", "tenant-1")
    }

    @Test
    fun `scan aggregate id should read from aggregate id index`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val zSetOperations = mockk<ReactiveZSetOperations<String, String>>()
        val hashOperations = mockk<ReactiveHashOperations<String, String, String>>()
        val rangeSlot = io.mockk.slot<Range<String>>()
        val limitSlot = io.mockk.slot<Limit>()
        val aggregateIds = listOf("002", "003")
        every { redisTemplate.opsForZSet() } returns zSetOperations
        every { redisTemplate.opsForHash<String, String>() } returns hashOperations
        every {
            zSetOperations.rangeByLex(
                "order-service.order:es:ids",
                capture(rangeSlot),
                capture(limitSlot),
            )
        } returns Flux.fromIterable(aggregateIds)
        every {
            hashOperations.multiGet("order-service.order:es:tenants", aggregateIds)
        } returns Mono.just(listOf(TenantId.DEFAULT_TENANT_ID, "tenant-2"))
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.scanAggregateId(namedAggregate, afterId = "001", limit = 2)
            .collectList()
            .test()
            .consumeNextWith {
                it.map { aggregateId -> aggregateId.id }.assert().containsExactly("002", "003")
                it.map { aggregateId -> aggregateId.tenantId }.assert()
                    .containsExactly(TenantId.DEFAULT_TENANT_ID, "tenant-2")
            }
            .verifyComplete()

        rangeSlot.captured.contains("001").assert().isFalse()
        rangeSlot.captured.contains("002").assert().isTrue()
        rangeSlot.captured.contains(AggregateIdScanner.LAST_ID).assert().isFalse()
        limitSlot.captured.count.assert().isEqualTo(2)
    }
}
