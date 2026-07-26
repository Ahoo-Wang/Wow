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
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
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
}
