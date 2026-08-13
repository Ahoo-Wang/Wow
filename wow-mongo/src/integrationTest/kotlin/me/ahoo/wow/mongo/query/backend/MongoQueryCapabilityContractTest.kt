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

package me.ahoo.wow.mongo.query.backend

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryCapabilityContract
import me.ahoo.wow.tck.query.backend.QueryCapabilityFixture
import me.ahoo.wow.tck.query.backend.QueryNativeCapabilityCase
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MongoQueryCapabilityContractTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_query_capability")

    private val commands = ConcurrentHashMap<String, AtomicLong>()
    private lateinit var client: MongoClient
    private lateinit var database: MongoDatabase

    @BeforeEach
    fun prepareCapabilityDocument() {
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(mongo.connectionString))
            .addCommandListener(object : CommandListener {
                override fun commandStarted(event: CommandStartedEvent) {
                    commands.computeIfAbsent(event.commandName) { AtomicLong() }.incrementAndGet()
                }
            })
            .build()
        client = MongoClients.create(settings)
        database = client.getDatabase(mongo.databaseName)
        val collectionName = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
            .namedAggregate.toSnapshotCollectionName()
        val collection = database.getCollection(collectionName)
        val document = Document("_id", "capability-1")
            .append("logicalId", "capability-1")
            .append("deleted", false)
            .append("title", "capability words")
        StepVerifier.create(
            Mono.from(collection.drop()).onErrorResume { Mono.empty() }
                .then(Mono.from(database.createCollection(collectionName)))
                .then(Mono.from(collection.createIndex(Indexes.text("title"))))
                .then(Mono.from(collection.insertOne(document))),
        ).expectNextCount(1).verifyComplete()
        commands.clear()
    }

    @AfterEach
    fun closeClient() {
        client.close()
    }

    @TestFactory
    fun mongoFullTextObeysSharedCapabilityContract() =
        QueryCapabilityContract(MongoFullTextCapabilityFixture(database, commands)).dynamicTests()

    @TestFactory
    fun mongoNativeObeysSharedCapabilityContract() =
        QueryCapabilityContract(MongoNativeCapabilityFixture(database, commands)).dynamicTests()
}

private abstract class MongoCapabilityFixture(
    protected val database: MongoDatabase,
    private val commands: ConcurrentHashMap<String, AtomicLong>,
) : QueryCapabilityFixture {
    final override val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
    open override val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
    final override val rawCommandCount: Long
        get() = listOf("aggregate", "count").sumOf { command -> commands[command]?.get() ?: 0 }

    final override fun reset() {
        commands.clear()
    }
}

private class MongoFullTextCapabilityFixture(
    database: MongoDatabase,
    commands: ConcurrentHashMap<String, AtomicLong>,
) : MongoCapabilityFixture(database, commands) {
    override val id: String = "mongo-full-text"
    override val capabilityId: QueryCapabilityId = QueryCapabilityId(MongoQueryBackendFactory.FULL_TEXT_CAPABILITY)
    override val expression = FullTextExpression(capabilityId, "capability", setOf(PortableQueryDataset.TITLE))
    override val backendFactory = MongoQueryBackendFactory(database)
}

private class MongoNativeCapabilityFixture(
    database: MongoDatabase,
    commands: ConcurrentHashMap<String, AtomicLong>,
) : MongoCapabilityFixture(database, commands) {
    override val id: String = "mongo-native"
    override val capabilityId: QueryCapabilityId = QueryCapabilityId(MongoQueryBackendFactory.NATIVE_CAPABILITY)
    override val schema = super.schema.withField(
        super.schema.fields.getValue(PortableQueryDataset.TITLE).copy(
            capabilities = super.schema.fields.getValue(PortableQueryDataset.TITLE).capabilities + capabilityId,
        ),
    )
    override val expression = native("mongo", "capability-title")
    override val backendFactory = MongoQueryBackendFactory(
        database,
        MongoNativeQueryTemplateRegistry(
            mapOf(
                "capability-title" to MongoNativeQueryTemplate { parameters ->
                    Filters.eq("title", (parameters.getValue("title") as QueryValue.StringValue).value)
                },
            ),
        ),
    )
    override val nativePreflightCases: List<QueryNativeCapabilityCase> = listOf(
        QueryNativeCapabilityCase("wrong-backend", native("elasticsearch", "capability-title")),
        QueryNativeCapabilityCase("missing-template", native("mongo", "missing-template")),
    )

    private fun native(backendId: String, templateId: String): NativeExpression = NativeExpression(
        capabilityId = capabilityId,
        backendId = backendId,
        templateId = templateId,
        parameters = mapOf("title" to QueryValue.StringValue("capability words")),
        declaredFields = setOf(LogicalField("title")),
    )
}
