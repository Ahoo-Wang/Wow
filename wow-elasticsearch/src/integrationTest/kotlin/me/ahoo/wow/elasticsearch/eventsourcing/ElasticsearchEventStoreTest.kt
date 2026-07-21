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

package me.ahoo.wow.elasticsearch.eventsourcing

import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class ElasticsearchEventStoreTest : EventStoreSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    override fun createEventStore(): EventStore {
        val elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initEventStreamTemplate()
        return ElasticsearchEventStore(
            elasticsearchClient = elasticsearchClient,
        )
    }

    override fun appendEventStreamWhenDuplicateRequestIdException() = Unit

    @Test
    fun `scan aggregate id should be empty when index is missing`() {
        eventStore.scanAggregateId(namedAggregate)
            .test()
            .verifyComplete()
    }

    @Test
    fun `load by version should continue across version gaps`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initEventStreamTemplate()
        val store = ElasticsearchEventStore(client, batchSize = 2)
        val aggregateId = namedAggregate.aggregateId(generateGlobalId())
        val streams = listOf(1, 3, 5).map { version ->
            generateEventStream(
                aggregateId = aggregateId,
                aggregateVersion = version - 1,
                eventCount = 1,
            )
        }
        Flux.concat(streams.map(store::append))
            .then()
            .test()
            .verifyComplete()

        store.load(aggregateId)
            .map { it.version }
            .test()
            .expectNext(1, 3, 5)
            .verifyComplete()
    }

    @Test
    fun `load by event time should read every page`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initEventStreamTemplate()
        val store = ElasticsearchEventStore(client, batchSize = 2)
        val aggregateId = namedAggregate.aggregateId(generateGlobalId())
        val streams = listOf(1, 2, 3).map { version ->
            generateEventStream(
                aggregateId = aggregateId,
                aggregateVersion = version - 1,
                eventCount = 1,
            )
        }
        Flux.concat(streams.map(store::append))
            .then()
            .test()
            .verifyComplete()

        store.load(aggregateId, 0L, Long.MAX_VALUE)
            .map { it.version }
            .test()
            .expectNext(1, 2, 3)
            .verifyComplete()
    }
}
