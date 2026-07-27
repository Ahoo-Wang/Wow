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
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

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
}
