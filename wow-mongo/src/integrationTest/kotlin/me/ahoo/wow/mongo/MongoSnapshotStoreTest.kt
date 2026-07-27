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

package me.ahoo.wow.mongo

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotStoreSpec
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.time.Duration

class MongoSnapshotStoreTest : SnapshotStoreSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    override fun createSnapshotStore(): SnapshotStore {
        val database = mongo.database()
        SnapshotSchemaInitializer(database).initSchema(aggregateMetadata)
        return MongoSnapshotStore(database)
    }

    @Test
    fun saveShouldRepairAStoredSnapshotWithoutAnIntegerVersion() {
        val snapshotStore = createSnapshotStore()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val stateAggregate = ConstructorStateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        stateAggregate.onSourcing(
            MockAggregateCreated("repaired").toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = stateAggregate.version,
            )
        )
        val snapshot = SimpleSnapshot(stateAggregate, snapshotTime = 1)
        snapshotStore.save(snapshot).test().verifyComplete()
        val collection = mongo.database().getCollection(aggregateId.toSnapshotCollectionName())
        val filter = Filters.eq(Documents.ID_FIELD, aggregateId.id)

        collection.updateOne(filter, Updates.set(MessageRecords.VERSION, "invalid"))
            .toMono()
            .then(snapshotStore.save(snapshot))
            .then(collection.find(filter).first().toMono())
            .test()
            .consumeNextWith {
                it.getInteger(MessageRecords.VERSION).assert().isEqualTo(snapshot.version)
            }
            .verifyComplete()

        snapshotStore.load<MockStateAggregate>(aggregateId)
            .test()
            .consumeNextWith {
                it.version.assert().isEqualTo(snapshot.version)
                it.state.data.assert().isEqualTo(snapshot.state.data)
            }
            .verifyComplete()
    }

    @Test
    fun batchShouldKeepTheNewestSnapshotWhenAnOlderSaveArrivesLast() {
        val database = mongo.database()
        SnapshotSchemaInitializer(database).initSchema(aggregateMetadata)
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val older = snapshot(aggregateId.id, version = 2)
        val newer = snapshot(aggregateId.id, version = 3)

        MongoSnapshotStore(
            database = database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { snapshotStore ->
            Flux.merge(
                snapshotStore.save(newer),
                snapshotStore.save(older),
            ).then()
                .test()
                .verifyComplete()

            snapshotStore.load<MockStateAggregate>(aggregateId)
                .test()
                .consumeNextWith {
                    it.version.assert().isEqualTo(newer.version)
                    it.state.data.assert().isEqualTo(newer.state.data)
                }
                .verifyComplete()
        }
    }

    @Test
    fun batchShouldRepairAnInvalidStoredVersionWithoutFailingAnotherSave() {
        val database = mongo.database()
        SnapshotSchemaInitializer(database).initSchema(aggregateMetadata)
        val repaired = snapshot(generateGlobalId(), version = 2)
        val independent = snapshot(generateGlobalId(), version = 1)
        val collection = database.getCollection(repaired.aggregateId.toSnapshotCollectionName())
        val repairedFilter = Filters.eq(Documents.ID_FIELD, repaired.aggregateId.id)
        MongoSnapshotStore(database).save(repaired)
            .then(collection.updateOne(repairedFilter, Updates.set(MessageRecords.VERSION, "invalid")).toMono())
            .then()
            .test()
            .verifyComplete()

        MongoSnapshotStore(
            database = database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { snapshotStore ->
            Flux.merge(
                snapshotStore.save(repaired),
                snapshotStore.save(independent),
            ).then()
                .test()
                .verifyComplete()

            snapshotStore.load<MockStateAggregate>(repaired.aggregateId)
                .test()
                .consumeNextWith {
                    it.version.assert().isEqualTo(repaired.version)
                    it.state.data.assert().isEqualTo(repaired.state.data)
                }
                .verifyComplete()
            snapshotStore.load<MockStateAggregate>(independent.aggregateId)
                .test()
                .consumeNextWith {
                    it.version.assert().isEqualTo(independent.version)
                }
                .verifyComplete()
        }
    }

    @Test
    fun batchShouldKeepTheLastSaveForTheSameVersion() {
        val database = mongo.database()
        SnapshotSchemaInitializer(database).initSchema(aggregateMetadata)
        val id = generateGlobalId()
        val first = snapshot(id, version = 1, data = "first")
        val replacement = snapshot(id, version = 1, data = "replacement")

        MongoSnapshotStore(
            database = database,
            batchOptions = MongoSnapshotStoreBatchOptions(
                enabled = true,
                maxSize = 2,
                maxDelay = Duration.ofSeconds(1),
            ),
        ).use { snapshotStore ->
            Mono.zip(
                snapshotStore.save(first).materialize(),
                snapshotStore.save(replacement).materialize(),
            ).then()
                .test()
                .verifyComplete()

            snapshotStore.load<MockStateAggregate>(replacement.aggregateId)
                .test()
                .consumeNextWith {
                    it.version.assert().isEqualTo(replacement.version)
                    it.state.data.assert().isEqualTo(replacement.state.data)
                }
                .verifyComplete()
        }
    }

    private fun snapshot(
        id: String,
        version: Int,
        data: String = "version-$version",
    ): SimpleSnapshot<MockStateAggregate> {
        require(version > 0)
        val aggregateId = aggregateMetadata.aggregateId(id)
        val aggregate = ConstructorStateAggregateFactory.create(
            aggregateMetadata.state,
            aggregateId,
        )
        aggregate.onSourcing(
            MockAggregateCreated(data).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = aggregate.version,
            )
        )
        repeat(version - 1) {
            aggregate.onSourcing(
                MockAggregateChanged(data).toDomainEventStream(
                    upstream = GivenInitializationCommand(aggregateId),
                    aggregateVersion = aggregate.version,
                )
            )
        }
        return SimpleSnapshot(aggregate, snapshotTime = version.toLong())
    }
}
