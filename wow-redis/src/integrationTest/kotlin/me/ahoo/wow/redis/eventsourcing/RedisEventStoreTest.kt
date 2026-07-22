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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.DuplicateAggregateIdException
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.container.RedisTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class RedisEventStoreTest : EventStoreSpec() {
    @JvmField
    @RegisterExtension
    val redis = RedisTestFixture()

    override fun createEventStore(): EventStore {
        return RedisEventStore(redis.redisTemplate)
    }

    override fun loadEventStreamByEventTime() = Unit

    @Test
    fun `append should atomically reject same id in different tenants`() {
        val eventStore = createEventStore()
        val namedAggregate = MaterializedNamedAggregate("redis-v2-${generateGlobalId()}", "order")
        val aggregateId = generateGlobalId()
        val streams = listOf(
            generateEventStream(namedAggregate.aggregateId(aggregateId, "tenant-1"), eventCount = 1),
            generateEventStream(namedAggregate.aggregateId(aggregateId, "tenant-2"), eventCount = 1),
        )

        Flux.fromIterable(streams)
            .flatMap(
                { stream ->
                    eventStore.append(stream)
                        .thenReturn(AppendOutcome(stream))
                        .onErrorResume { error -> Mono.just(AppendOutcome(stream, error)) }
                },
                2,
            ).collectList()
            .flatMap { outcomes ->
                val winner = outcomes.single { it.error == null }.eventStream
                val loser = outcomes.single { it.error != null }
                loser.error.assert().isInstanceOf(DuplicateAggregateIdException::class.java)

                eventStore.load(winner.aggregateId)
                    .single()
                    .doOnNext { stored ->
                        stored.aggregateId.assert().isEqualTo(winner.aggregateId)
                        stored.requestId.assert().isEqualTo(winner.requestId)
                    }.then(eventStore.load(loser.eventStream.aggregateId).hasElements())
                    .doOnNext { hasLoserEvents -> hasLoserEvents.assert().isFalse() }
                    .then(eventStore.existsRequestId(loser.eventStream.aggregateId, loser.eventStream.requestId))
                    .doOnNext { hasLoserRequestId -> hasLoserRequestId.assert().isFalse() }
                    .then(eventStore.scanAggregateId(namedAggregate).collectList())
                    .doOnNext { aggregateIds -> aggregateIds.assert().containsExactly(winner.aggregateId) }
                    .then()
            }.test()
            .verifyComplete()
    }

    private data class AppendOutcome(
        val eventStream: DomainEventStream,
        val error: Throwable? = null,
    )
}
