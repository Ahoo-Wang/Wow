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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.redis.eventsourcing.EventStreamKeyLayout.toAggregateIdIndexMember
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.Limit
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveZSetOperations
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import reactor.test.StepVerifier

private const val EXPECTED_BUCKET_QUERY_CONCURRENCY = 16

class RedisEventStoreScanTest {
    private fun aggregateIdIndexKey(id: String): String {
        val bucket = id.hashCode().mod(128)
        return "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:$bucket}:ids"
    }

    @Test
    fun `append should pass bucket aligned event stream and index keys to lua script`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val keysSlot = io.mockk.slot<List<String>>()
        val argumentsSlot = io.mockk.slot<List<*>>()
        val aggregateId = namedAggregate.aggregateId("order-1", tenantId = "tenant-1")
        val eventStream = generateEventStream(aggregateId, eventCount = 1)
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STREAM_APPEND,
                capture(keysSlot),
                capture(argumentsSlot),
            )
        } returns Flux.just(ErrorCodes.SUCCEEDED)
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .verifyComplete()

        val bucket = "order-1".hashCode().mod(128)
        keysSlot.captured.assert().containsExactly(
            "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:$bucket}:b3JkZXItMQ.dGVuYW50LTE",
            "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:$bucket}:ids",
            "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:$bucket}:b3JkZXItMQ.dGVuYW50LTE:req_idx",
        )
        val hashTags = keysSlot.captured.map { key ->
            key.substringAfter("{").substringBefore("}")
        }
        hashTags.distinct().assert().containsExactly("v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:$bucket")
        argumentsSlot.captured.assert().hasSize(6)
        argumentsSlot.captured[3].assert()
            .isEqualTo("006f0072006400650072002d0031.dGVuYW50LTE")
        argumentsSlot.captured[4].assert()
            .isEqualTo("006f0072006400650072002d0031.")
        argumentsSlot.captured[5].assert()
            .isEqualTo("006f0072006400650072002d0031/")
    }

    @Test
    fun `append should map initial version conflict script result to duplicate aggregate id`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val aggregateId = namedAggregate.aggregateId("order-1", tenantId = "tenant-1")
        val eventStream = generateEventStream(aggregateId, eventCount = 1)
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STREAM_APPEND,
                any<List<String>>(),
                any<List<*>>(),
            )
        } returns Flux.just(ErrorCodes.EVENT_VERSION_CONFLICT)
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(DuplicateAggregateIdException::class.java)
            }
            .verify()
    }

    @Test
    fun `append should map duplicate request id script result`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val aggregateId = namedAggregate.aggregateId("order-1", tenantId = "tenant-1")
        val eventStream = generateEventStream(aggregateId, eventCount = 1)
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STREAM_APPEND,
                any<List<String>>(),
                any<List<*>>(),
            )
        } returns Flux.just(ErrorCodes.DUPLICATE_REQUEST_ID)
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(DuplicateRequestIdException::class.java)
            }
            .verify()
    }

    @Test
    fun `append should map duplicate aggregate id script result`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val eventStream = generateEventStream(
            namedAggregate.aggregateId("order-1", tenantId = "tenant-1"),
            eventCount = 1,
        )
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STREAM_APPEND,
                any<List<String>>(),
                any<List<*>>(),
            )
        } returns Flux.just(ErrorCodes.DUPLICATE_AGGREGATE_ID)
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(DuplicateAggregateIdException::class.java)
            }
            .verify()
    }

    @Test
    fun `append should reject an unexpected script result`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val eventStream = generateEventStream(namedAggregate.aggregateId("order-1"), eventCount = 1)
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STREAM_APPEND,
                any<List<String>>(),
                any<List<*>>(),
            )
        } returns Flux.just("Unexpected")
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(IllegalStateException::class.java)
                error.message.assert().contains("Unexpected Redis EventStore append result")
            }
            .verify()
    }

    @Test
    fun `append should reject an empty script result`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val eventStream = generateEventStream(namedAggregate.aggregateId("order-1"), eventCount = 1)
        every {
            redisTemplate.execute(
                RedisEventStore.SCRIPT_EVENT_STREAM_APPEND,
                any<List<String>>(),
                any<List<*>>(),
            )
        } returns Flux.empty()
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.append(eventStream)
            .test()
            .expectErrorSatisfies { error ->
                error.assert().isInstanceOf(IllegalStateException::class.java)
                error.message.assert().contains("returned no result")
            }
            .verify()
    }

    @Test
    fun `scan aggregate id should read from aggregate id index`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val zSetOperations = mockk<ReactiveZSetOperations<String, String>>()
        val rangeSlots = mutableListOf<Range<String>>()
        val limitSlots = mutableListOf<Limit>()
        every { redisTemplate.opsForZSet() } returns zSetOperations
        every {
            zSetOperations.rangeByLex(
                any<String>(),
                capture(rangeSlots),
                capture(limitSlots),
            )
        } returns Flux.empty()
        every {
            zSetOperations.rangeByLex(aggregateIdIndexKey("003"), any(), any())
        } returns Flux.just(toAggregateIdIndexMember(namedAggregate.aggregateId("003", tenantId = "tenant-2")))
        every {
            zSetOperations.rangeByLex(aggregateIdIndexKey("002"), any(), any())
        } returns Flux.just(toAggregateIdIndexMember(namedAggregate.aggregateId("002")))
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

        rangeSlots.assert().isNotEmpty()
        rangeSlots.forEach {
            it.contains(EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("001")))
                .assert().isFalse()
            it.contains(EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("0010")))
                .assert().isTrue()
            it.contains(EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("002")))
                .assert().isTrue()
            it.contains(EventStreamKeyLayout.toAggregateIdIndexMember(namedAggregate.aggregateId("订单-1")))
                .assert().isTrue()
        }
        limitSlots.forEach {
            it.count.assert().isEqualTo(2)
        }
    }

    @Test
    fun `scan aggregate id should complete when aggregate id index is empty`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val zSetOperations = mockk<ReactiveZSetOperations<String, String>>()
        every { redisTemplate.opsForZSet() } returns zSetOperations
        every {
            zSetOperations.rangeByLex(
                any<String>(),
                any(),
                any(),
            )
        } returns Flux.empty()
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.scanAggregateId(namedAggregate, afterId = "001", limit = 2)
            .test()
            .verifyComplete()
    }

    @Test
    fun `scan aggregate id should complete without redis range query when cursor is last id`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>(relaxed = true)
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.scanAggregateId(namedAggregate, afterId = AggregateIdScanner.LAST_ID, limit = 2)
            .test()
            .verifyComplete()

        verify(exactly = 0) {
            redisTemplate.opsForZSet()
        }
    }

    @Test
    fun `scan aggregate id should bound bucket query concurrency`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val zSetOperations = mockk<ReactiveZSetOperations<String, String>>()
        val subscribedKeys = mutableListOf<String>()
        every { redisTemplate.opsForZSet() } returns zSetOperations
        every {
            zSetOperations.rangeByLex(
                any<String>(),
                any(),
                any(),
            )
        } answers {
            subscribedKeys += firstArg<String>()
            Flux.never()
        }
        val eventStore = RedisEventStore(redisTemplate)

        StepVerifier.create(eventStore.scanAggregateId(namedAggregate, afterId = "001", limit = 2))
            .then {
                subscribedKeys.assert().hasSize(EXPECTED_BUCKET_QUERY_CONCURRENCY)
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `scan aggregate id should reject invalid index member`() {
        val namedAggregate = MaterializedNamedAggregate("order-service", "order")
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val zSetOperations = mockk<ReactiveZSetOperations<String, String>>()
        every { redisTemplate.opsForZSet() } returns zSetOperations
        every {
            zSetOperations.rangeByLex(
                any<String>(),
                any(),
                any(),
            )
        } returns Flux.empty()
        every {
            zSetOperations.rangeByLex(aggregateIdIndexKey("002"), any(), any())
        } returns Flux.just("002")
        val eventStore = RedisEventStore(redisTemplate)

        eventStore.scanAggregateId(namedAggregate, afterId = "001", limit = 2)
            .test()
            .expectErrorSatisfies {
                it.assert().isInstanceOf(IllegalArgumentException::class.java)
                it.message.assert().contains("Invalid aggregate id index member:002")
            }
            .verify()
    }
}
