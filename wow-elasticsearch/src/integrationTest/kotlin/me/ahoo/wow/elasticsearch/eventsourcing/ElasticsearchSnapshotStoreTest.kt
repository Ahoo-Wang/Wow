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

import co.elastic.clients.elasticsearch._types.Refresh
import me.ahoo.test.asserts.assert
import me.ahoo.wow.elasticsearch.IndexNameConverter.SNAPSHOT_SUFFIX
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotStoreSpec
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.time.Duration

internal class ElasticsearchSnapshotStoreTest : SnapshotStoreSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    override fun createSnapshotStore(): SnapshotStore {
        val elasticsearchClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        elasticsearchClient.initSnapshotTemplate()
        return ElasticsearchSnapshotStore(
            elasticsearchClient = elasticsearchClient
        )
    }

    @Test
    fun `batch should keep the newest snapshot when an older save arrives last`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val olderAggregate = ConstructorStateAggregateFactory.create(
            aggregateMetadata.state,
            aggregateId,
        )
        val newerAggregate = ConstructorStateAggregateFactory.create(
            aggregateMetadata.state,
            aggregateId,
        )
        val firstEventStream = listOf(
            MockAggregateCreated(generateGlobalId()),
            MockAggregateChanged(generateGlobalId()),
        ).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
        )
        olderAggregate.onSourcing(firstEventStream)
        newerAggregate.onSourcing(firstEventStream)
        newerAggregate.onSourcing(
            listOf(MockAggregateChanged(generateGlobalId())).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = newerAggregate.version,
            )
        )
        val older = SimpleSnapshot(olderAggregate, snapshotTime = 1)
        val newer = SimpleSnapshot(newerAggregate, snapshotTime = 2)

        ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { store ->
            Flux.merge(
                store.save(newer),
                store.save(older),
            )
                .then()
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(aggregateId)
                .test()
                .assertNext { snapshot ->
                    snapshot.version.assert().isEqualTo(newer.version)
                    snapshot.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `direct save should upgrade a legacy internal version document atomically`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val older = snapshot(id = generateGlobalId(), version = 2)
        writeLegacySnapshot(client, older, writes = 5)
        val newer = snapshot(id = older.aggregateId.id, version = 3)

        ElasticsearchSnapshotStore(client).use { store ->
            store.save(newer)
                .test()
                .verifyComplete()
            store.save(older)
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                    loaded.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `batch save should upgrade a legacy internal version document without failing another item`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val older = snapshot(id = generateGlobalId(), version = 2)
        writeLegacySnapshot(client, older, writes = 5)
        val newer = snapshot(id = older.aggregateId.id, version = 3)
        val independent = snapshot(id = generateGlobalId(), version = 1)

        ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { store ->
            Flux.merge(store.save(newer), store.save(independent))
                .then()
                .test()
                .verifyComplete()
            store.save(older)
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                    loaded.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
            store.load<MockStateAggregate>(independent.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(independent.version)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `batch stale save should not overwrite a legacy snapshot with a higher source version`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val newer = snapshot(id = generateGlobalId(), version = 100)
        writeLegacySnapshot(client, newer, writes = 5)
        val stale = snapshot(id = newer.aggregateId.id, version = 99)
        val independent = snapshot(id = generateGlobalId(), version = 1)

        ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { store ->
            Flux.merge(store.save(stale), store.save(independent))
                .then()
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                    loaded.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `batch save through a write alias should accept its concrete response index`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val snapshot = snapshot(id = generateGlobalId(), version = 2)
        val alias = snapshot.aggregateId.toSnapshotIndexName()
        createWriteAlias(client, alias)

        ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofMillis(5),
            ),
        ).use { store ->
            store.save(snapshot)
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(snapshot.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(snapshot.version)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `direct stale save against a current snapshot should remain a no-op`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val newer = snapshot(id = generateGlobalId(), version = 3)
        val older = snapshot(id = newer.aggregateId.id, version = 2)

        ElasticsearchSnapshotStore(client).use { store ->
            store.save(newer)
                .then(store.save(older))
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                    loaded.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `direct stale save should not overwrite a legacy snapshot with a higher source version`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val newer = snapshot(id = generateGlobalId(), version = 100)
        writeLegacySnapshot(client, newer, writes = 5)
        val stale = snapshot(id = newer.aggregateId.id, version = 99)

        ElasticsearchSnapshotStore(client).use { store ->
            store.save(stale)
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                    loaded.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    @Test
    fun `concurrent direct saves from separate stores should keep the newer snapshot`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val newer = snapshot(id = generateGlobalId(), version = 3)
        val older = snapshot(id = newer.aggregateId.id, version = 2)
        val newerStore = ElasticsearchSnapshotStore(client)
        val olderStore = ElasticsearchSnapshotStore(client)

        try {
            Flux.merge(newerStore.save(newer), olderStore.save(older))
                .then()
                .test()
                .verifyComplete()

            newerStore.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                }
                .verifyComplete()
        } finally {
            newerStore.close()
            olderStore.close()
        }
    }

    @Test
    fun `concurrent batches from separate stores should keep the newer snapshot`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val newer = snapshot(id = generateGlobalId(), version = 3)
        val older = snapshot(id = newer.aggregateId.id, version = 2)
        val options = ElasticsearchSnapshotStoreBatchOptions(
            enabled = true,
            maxSize = 2,
            maxDelay = Duration.ofMillis(5),
        )
        val newerStore = ElasticsearchSnapshotStore(client, options)
        val olderStore = ElasticsearchSnapshotStore(client, options)

        try {
            Flux.merge(newerStore.save(newer), olderStore.save(older))
                .then()
                .test()
                .verifyComplete()

            newerStore.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                }
                .verifyComplete()
        } finally {
            newerStore.close()
            olderStore.close()
        }
    }

    @Test
    fun `batch alias guarded update should upgrade a legacy concrete document`() {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val older = snapshot(id = generateGlobalId(), version = 2)
        val alias = older.aggregateId.toSnapshotIndexName()
        val concreteIndex = createWriteAlias(client, alias)
        writeLegacySnapshot(client, older, writes = 5, index = concreteIndex)
        val newer = snapshot(id = older.aggregateId.id, version = 3)

        ElasticsearchSnapshotStore(
            elasticsearchClient = client,
            batchOptions = ElasticsearchSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofMillis(5),
            ),
        ).use { store ->
            store.save(newer)
                .test()
                .verifyComplete()

            store.load<MockStateAggregate>(newer.aggregateId)
                .test()
                .assertNext { loaded ->
                    loaded.version.assert().isEqualTo(newer.version)
                    loaded.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    private fun snapshot(
        id: String,
        version: Int,
    ): SimpleSnapshot<MockStateAggregate> {
        val aggregateId = aggregateMetadata.aggregateId(id)
        val aggregate: StateAggregate<MockStateAggregate> = ConstructorStateAggregateFactory.create(
            aggregateMetadata.state,
            aggregateId,
        )
        aggregate.onSourcing(
            listOf(MockAggregateCreated(generateGlobalId())).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = 0,
            )
        )
        repeat(version - 1) {
            aggregate.onSourcing(
                listOf(MockAggregateChanged(generateGlobalId())).toDomainEventStream(
                    upstream = GivenInitializationCommand(aggregateId),
                    aggregateVersion = aggregate.version,
                )
            )
        }
        return SimpleSnapshot(aggregate, snapshotTime = version.toLong())
    }

    private fun writeLegacySnapshot(
        client: org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient,
        snapshot: SimpleSnapshot<MockStateAggregate>,
        writes: Int,
        index: String = snapshot.aggregateId.toSnapshotIndexName(),
    ) {
        repeat(writes) {
            client.index<Map<String, Any?>> { request ->
                request.index(index)
                    .id(snapshot.aggregateId.id)
                    .document(snapshot.toLinkedHashMap())
                    .refresh(Refresh.True)
            }.block()
        }
    }

    private fun createWriteAlias(
        client: org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient,
        alias: String,
    ): String {
        val concreteIndex = alias.removeSuffix(SNAPSHOT_SUFFIX) +
            "-000001$SNAPSHOT_SUFFIX"
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
        return concreteIndex
    }
}
