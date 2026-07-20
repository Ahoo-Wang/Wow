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

import com.mongodb.MongoWriteException
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCheckpointCollectionName
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.eventsourcing.snapshot.VersionedSnapshotStoreSpec
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.bson.Document
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class MongoSnapshotStoreTest : VersionedSnapshotStoreSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    override fun createSnapshotStore(): VersionedSnapshotStore {
        val database = mongo.database()
        SnapshotSchemaInitializer(database).initSchema(aggregateMetadata)
        SnapshotCheckpointSchemaInitializer(database).initSchema(aggregateMetadata)
        return MongoSnapshotStore(database)
    }

    @Test
    fun `should propagate duplicate key errors from unrelated unique indexes`() {
        val database = mongo.database()
        val collectionName = aggregateMetadata.toSnapshotCheckpointCollectionName()
        val collection = database.getCollection(collectionName)
        collection.drop().toMono().block()
        SnapshotCheckpointSchemaInitializer(database).initSchema(aggregateMetadata)
        collection.createIndex(
            Indexes.ascending(MessageRecords.CONTEXT_NAME),
            IndexOptions().unique(true),
        ).toMono().block()
        val store = MongoSnapshotStore(database)
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        try {
            store.saveCheckpoint(snapshot(aggregateId, 5)).block()

            store.saveCheckpoint(snapshot(aggregateId, 10))
                .test()
                .expectError(MongoWriteException::class.java)
                .verify()
        } finally {
            collection.drop().toMono().block()
        }
    }

    @Test
    fun `should reject invalid checkpoint versions`() {
        val store = MongoSnapshotStore(mongo.database())
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())

        assertThrows<IllegalArgumentException> {
            store.loadAtOrBefore<MockStateAggregate>(aggregateId, -1)
        }
        assertThrows<IllegalArgumentException> {
            store.saveCheckpoint(snapshot(aggregateId, 0))
        }
    }

    @Test
    fun `should reject a checkpoint without its payload`() {
        val database = mongo.database()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val collection = database.getCollection(aggregateMetadata.toSnapshotCheckpointCollectionName())
        collection.insertOne(
            Document()
                .append(MessageRecords.AGGREGATE_ID, aggregateId.id)
                .append(MessageRecords.TENANT_ID, aggregateId.tenantId)
                .append(MessageRecords.VERSION, 5),
        ).toMono().block()

        MongoSnapshotStore(database)
            .loadAtOrBefore<MockStateAggregate>(aggregateId, 5)
            .test()
            .expectError(IllegalStateException::class.java)
            .verify()
    }

    private fun snapshot(
        aggregateId: AggregateId,
        version: Int,
    ): Snapshot<MockStateAggregate> {
        val state = MockStateAggregate(aggregateId.id)
        val aggregate = aggregateMetadata.state.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = version,
        )
        return SimpleSnapshot(aggregate, snapshotTime = version.toLong())
    }
}
