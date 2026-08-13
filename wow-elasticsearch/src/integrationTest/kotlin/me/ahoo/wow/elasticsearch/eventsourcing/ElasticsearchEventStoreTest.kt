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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.IndexNameConverter.EVENT_STREAM_SUFFIX
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryReadiness
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryReadinessRequirements
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.eventsourcing.EventStoreSpec
import me.ahoo.wow.query.backend.QueryBackendReadiness
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration

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
    fun `production writer and managed template cover an unmaterialized event null marker`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initEventStreamTemplate()
        val eventStream = generateEventStream(
            aggregateId = namedAggregate.aggregateId(generateGlobalId()),
            eventCount = 1,
            createdEventSupplier = { EmptyPresenceEvent() },
        )

        ElasticsearchEventStore(client).use { store ->
            store.append(eventStream)
                .then(
                    ElasticsearchQueryReadiness(
                        client = client,
                        index = eventStream.aggregateId.toEventStreamIndexName(),
                        requirements = ElasticsearchQueryReadinessRequirements(
                            configurationValid = true,
                            fields = emptySet(),
                            presenceVersion = ElasticsearchQueryPresenceEncoder.VERSION,
                            presenceFields = setOf(
                                "__wow_query.present",
                                "__wow_query.null",
                                "body.__wow_query.present",
                                "body.__wow_query.null",
                                "body.body.__wow_query.present",
                                "body.body.__wow_query.null",
                                "body.body.emptyObject.__wow_query.present",
                                "body.body.emptyObject.__wow_query.null",
                            ),
                        ),
                    ).inspect(),
                )
                .test()
                .expectNext(QueryBackendReadiness.Ready)
                .verifyComplete()
        }
    }

    @Test
    fun `last should fail closed when elasticsearch returns a hit without source`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val eventStream = generateEventStream(
            aggregateId = namedAggregate.aggregateId(generateGlobalId()),
            eventCount = 1,
        )
        val index = eventStream.aggregateId.toEventStreamIndexName()
        ElasticsearchEventStore(client).use { store ->
            client.indices().create { request ->
                request.index(index).mappings { mapping -> mapping.source { source -> source.enabled(false) } }
            }.then(Mono.defer { store.append(eventStream) })
                .then(Mono.defer { store.last(eventStream.aggregateId) })
                .test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }
    }

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

    @Test
    fun `bulk create should isolate a version conflict from another append`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initEventStreamTemplate()
        val conflicting = generateEventStream(
            aggregateId = namedAggregate.aggregateId(generateGlobalId()),
            aggregateVersion = 1,
            eventCount = 1,
        )
        val successful = generateEventStream(
            aggregateId = namedAggregate.aggregateId(generateGlobalId()),
            aggregateVersion = 1,
            eventCount = 1,
        )
        ElasticsearchEventStore(client).append(conflicting).block()

        ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { store ->
            Flux.merge(
                store.append(conflicting).materialize(),
                store.append(successful).materialize(),
            )
                .collectList()
                .test()
                .assertNext { signals ->
                    signals.single { it.isOnError }.throwable
                        .assert()
                        .isInstanceOf(EventVersionConflictException::class.java)
                    signals.count { it.isOnComplete }.assert().isEqualTo(1)
                }
                .verifyComplete()

            store.load(successful.aggregateId)
                .test()
                .expectNext(successful)
                .verifyComplete()
        }
    }

    @Test
    fun `bulk create through a write alias should accept its concrete response index`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initEventStreamTemplate()
        val aggregateId = namedAggregate.aggregateId(generateGlobalId())
        val alias = aggregateId.toEventStreamIndexName()
        val concreteIndex = alias.removeSuffix(EVENT_STREAM_SUFFIX) +
            "-000001$EVENT_STREAM_SUFFIX"
        client.indices().create { create -> create.index(concreteIndex) }
            .then(
                reactor.core.publisher.Mono.defer {
                    client.indices().updateAliases { aliases ->
                        aliases.actions { action ->
                            action.add { add ->
                                add.index(concreteIndex)
                                    .alias(alias)
                                    .isWriteIndex(true)
                            }
                        }
                    }
                }
            )
            .block()
        val streams = listOf(1, 2).map { version ->
            generateEventStream(
                aggregateId = aggregateId,
                aggregateVersion = version - 1,
                eventCount = 1,
            )
        }

        ElasticsearchEventStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchEventStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { store ->
            Flux.merge(streams.map(store::append))
                .then()
                .test()
                .verifyComplete()

            store.load(aggregateId)
                .map { it.version }
                .test()
                .expectNext(1, 2)
                .verifyComplete()
        }
    }
}

private data class EmptyPresenceEvent(
    val emptyObject: Map<String, Any?> = emptyMap(),
    val emptyList: List<Any?> = emptyList(),
)
