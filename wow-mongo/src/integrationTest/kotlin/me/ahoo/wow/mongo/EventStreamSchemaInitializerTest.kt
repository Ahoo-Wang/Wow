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

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME
import me.ahoo.wow.mongo.AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class EventStreamSchemaInitializerTest : SchemaInitializerSpec() {

    override fun initAllAggregateSchema(database: MongoDatabase) {
        val eventStreamSchemaInitializer = EventStreamSchemaInitializer(database)
        eventStreamSchemaInitializer.initAll()
    }

    override fun initAggregateSchema(database: MongoDatabase, namedAggregate: NamedAggregate) {
        val eventStreamSchemaInitializer = EventStreamSchemaInitializer(database)
        eventStreamSchemaInitializer.initSchema(namedAggregate)
    }

    override fun getCollectionName(namedAggregate: NamedAggregate): String {
        return namedAggregate.toEventStreamCollectionName()
    }

    override fun getExpectedIndexNames(): Set<String> = setOf(
        "${MessageRecords.AGGREGATE_ID}_hashed",
        AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME,
        "${MessageRecords.AGGREGATE_ID}_1_${MessageRecords.REQUEST_ID}_1",
        "${MessageRecords.TENANT_ID}_hashed",
        "${MessageRecords.OWNER_ID}_hashed",
    )

    override fun getExpectedUniqueIndexNames(): Set<String> = setOf(
        AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME,
        "${MessageRecords.AGGREGATE_ID}_1_${MessageRecords.REQUEST_ID}_1",
    )

    @Test
    fun `should reject incompatible existing index options`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testIncompatibleEventStreamIndex")
        val collection = database.getCollection(getCollectionName(namedAggregate))
        collection.drop().toMono().block()
        database.createCollection(getCollectionName(namedAggregate)).toMono().block()
        collection.createIndex(
            Indexes.ascending(MessageRecords.AGGREGATE_ID, MessageRecords.VERSION),
            IndexOptions().name(AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME),
        ).toMono().block()

        assertThrownBy<IllegalStateException> {
            initAggregateSchema(database, namedAggregate)
        }
        collection.drop().toMono().block()
    }

    @Test
    fun `should reject a custom name for managed index keys`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testCustomManagedIndexName")
        val collectionName = getCollectionName(namedAggregate)
        val collection = database.getCollection(collectionName)
        collection.drop().toMono().block()
        database.createCollection(collectionName).toMono().block()
        collection.createIndex(
            Indexes.ascending(MessageRecords.AGGREGATE_ID, MessageRecords.VERSION),
            IndexOptions().name("legacy_aggregate_version").unique(true),
        ).toMono().block()

        runCatching {
            initAggregateSchema(database, namedAggregate)
        }.exceptionOrNull()!!.message.assert()
            .contains("same ordered keys", "legacy_aggregate_version")
        collection.drop().toMono().block()
    }

    @Test
    fun `should reject stale global request id uniqueness in aggregate scoped mode`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testStaleRequestIdIndex")
        val collection = database.getCollection(getCollectionName(namedAggregate))
        collection.drop().toMono().block()
        database.createCollection(getCollectionName(namedAggregate)).toMono().block()
        collection.createIndex(
            Indexes.ascending(MessageRecords.REQUEST_ID),
            IndexOptions().name(REQUEST_ID_UNIQUE_INDEX_NAME).unique(true),
        ).toMono().block()

        assertThrownBy<IllegalStateException> {
            initAggregateSchema(database, namedAggregate)
        }
        collection.drop().toMono().block()
    }

    @Test
    fun `should reject custom named global request id uniqueness in aggregate scoped mode`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testCustomGlobalRequestIdIndex")
        val collectionName = getCollectionName(namedAggregate)
        val collection = database.getCollection(collectionName)
        collection.drop().toMono().block()
        database.createCollection(collectionName).toMono().block()
        collection.createIndex(
            Indexes.descending(MessageRecords.REQUEST_ID),
            IndexOptions().name("legacy_global_request_id").unique(true),
        ).toMono().block()

        runCatching {
            initAggregateSchema(database, namedAggregate)
        }.exceptionOrNull()!!.message.assert().contains("for [legacy_global_request_id]")
        collection.drop().toMono().block()
    }

    @Test
    fun `should initialize global request id uniqueness mode`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testGlobalRequestIdIndex")
        val collection = database.getCollection(getCollectionName(namedAggregate))
        collection.drop().toMono().block()

        EventStreamSchemaInitializer(database, enableRequestIdUniqueIndex = true).initSchema(namedAggregate)

        val indexes = collection.listIndexes().toFlux().collectList().block()!!
            .associateBy { it.getString("name") }
        indexes[REQUEST_ID_UNIQUE_INDEX_NAME]?.getBoolean("unique", false).assert().isTrue()
        indexes.containsKey("${MessageRecords.AGGREGATE_ID}_1_${MessageRecords.REQUEST_ID}_1")
            .assert()
            .isFalse()
        collection.drop().toMono().block()
    }

    @Test
    fun `should reject stale aggregate scoped request id index in global mode`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testStaleAggregateRequestIdIndex")
        val collectionName = getCollectionName(namedAggregate)
        val collection = database.getCollection(collectionName)
        collection.drop().toMono().block()
        database.createCollection(collectionName).toMono().block()
        collection.createIndex(
            Indexes.ascending(MessageRecords.AGGREGATE_ID, MessageRecords.REQUEST_ID),
            IndexOptions().unique(true),
        ).toMono().block()

        assertThrownBy<IllegalStateException> {
            EventStreamSchemaInitializer(database, enableRequestIdUniqueIndex = true).initSchema(namedAggregate)
        }
        collection.drop().toMono().block()
    }

    @Test
    fun `should reject custom named aggregate request id uniqueness in global mode`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testCustomAggregateRequestIdIndex")
        val collectionName = getCollectionName(namedAggregate)
        val collection = database.getCollection(collectionName)
        collection.drop().toMono().block()
        database.createCollection(collectionName).toMono().block()
        collection.createIndex(
            Indexes.ascending(MessageRecords.REQUEST_ID, MessageRecords.AGGREGATE_ID),
            IndexOptions().name("legacy_aggregate_request_id").unique(true),
        ).toMono().block()

        runCatching {
            EventStreamSchemaInitializer(database, enableRequestIdUniqueIndex = true).initSchema(namedAggregate)
        }.exceptionOrNull()!!.message.assert().contains("for [legacy_aggregate_request_id]")
        collection.drop().toMono().block()
    }

    @Test
    fun `should report required unique index creation failure for duplicate existing data`() {
        val database = mongo.database()
        val namedAggregate = MaterializedNamedAggregate("", "testDuplicateExistingData")
        val collectionName = getCollectionName(namedAggregate)
        val collection = database.getCollection(collectionName)
        collection.drop().toMono().block()
        database.createCollection(collectionName).toMono().block()
        collection.insertMany(
            listOf(
                eventStreamDocument("aggregate-1", 1, "request-1"),
                eventStreamDocument("aggregate-1", 1, "request-2"),
            ),
        ).toMono().block()

        assertThrownBy<IllegalStateException> {
            initAggregateSchema(database, namedAggregate)
        }
        collection.drop().toMono().block()
    }

    private fun eventStreamDocument(
        aggregateId: String,
        version: Int,
        requestId: String,
    ): Document = Document()
        .append(MessageRecords.AGGREGATE_ID, aggregateId)
        .append(MessageRecords.VERSION, version)
        .append(MessageRecords.REQUEST_ID, requestId)
        .append(MessageRecords.TENANT_ID, "tenant")
        .append(MessageRecords.OWNER_ID, "owner")
}
