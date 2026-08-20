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

package me.ahoo.wow.mongo.query.gateway

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.ElementMatchExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QuerySort
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.mongo.MongoSnapshotStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.test.test
import reactor.kotlin.core.publisher.toMono
import org.bson.Document
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MongoSnapshotQueryGatewayTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    private lateinit var gateway: SnapshotQueryGateway<MockStateAggregate>
    private lateinit var database: MongoDatabase
    private lateinit var aggregateId: String

    @BeforeEach
    fun setup() {
        database = mongo.database()
        val snapshotStore = MongoSnapshotStore(database)
        val id = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, id)
        snapshotStore.save(SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())).test().verifyComplete()
        aggregateId = id.id
        val backend = MongoSnapshotQueryBackend(database)
        gateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter { backend },
            objectMapper = JsonSerializer
        ).create(MOCK_AGGREGATE_METADATA)
    }

    @Test
    fun `should query snapshot records and typed results`() {
        val query = aggregateQuery()

        gateway.firstRecord(query)
            .test()
            .assertNext { record -> check(record["aggregateId"].asString() == aggregateId) }
            .verifyComplete()

        gateway.first(query)
            .test()
            .assertNext { snapshot -> check(snapshot.aggregateId == aggregateId) }
            .verifyComplete()
    }

    @Test
    fun `should return exact page total and count`() {
        val query = aggregateQuery()

        gateway.pageRecords(query, page = 1, size = 10)
            .test()
            .assertNext { page ->
                check(page.total == 1L)
                check(page.items.single()["aggregateId"].asString() == aggregateId)
            }
            .verifyComplete()

        gateway.count(query.filter)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `should fail closed when the full text index is not ready`() {
        val query = Query(filter = SearchExpression("missing", setOf(LogicalField("state.data"))))

        gateway.firstRecord(query)
            .test()
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.BACKEND_NOT_READY
            }
            .verify()
    }

    @Test
    fun `should query nested element matches with relative physical paths`() {
        database.getCollection(MOCK_AGGREGATE_METADATA.namedAggregate.toSnapshotCollectionName())
            .updateOne(
                Filters.eq(Documents.ID_FIELD, aggregateId),
                Updates.set(
                    "state.orders",
                    listOf(Document("lines", listOf(Document("sku", "target"))))
                )
            ).toMono().test().expectNextCount(1).verifyComplete()
        val fields = LinkedHashMap(JacksonQuerySchemaProvider(JsonSerializer).getSchema(MOCK_AGGREGATE_METADATA).fields)
        listOf("state.orders", "state.orders.lines").forEach { path ->
            val field = LogicalField(path)
            fields[field] = QueryFieldSchema(
                field,
                QueryValueKind.OBJECT,
                nullable = false,
                collectionKind = QueryCollectionKind.OBJECT,
                queryable = true,
                sortable = false
            )
        }
        val sku = LogicalField("state.orders.lines.sku")
        fields[sku] = QueryFieldSchema(sku, QueryValueKind.STRING, nullable = false)
        val localGateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = { QuerySchema(fields) },
            router = QueryRouter { MongoSnapshotQueryBackend(database) },
            objectMapper = JsonSerializer
        ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val query = Query(
            filter = ElementMatchExpression(
                LogicalField("state.orders"),
                ElementMatchExpression(
                    LogicalField("lines"),
                    PredicateExpression(
                        LogicalField("sku"),
                        PredicateOperator.EQ,
                        listOf(JsonNodeFactory.instance.stringNode("target"))
                    )
                )
            )
        )

        localGateway.firstRecord(query)
            .test()
            .assertNext { record -> check(record["aggregateId"].asString() == aggregateId) }
            .verifyComplete()
    }

    @Test
    fun `should kill cursor when downstream cancels`() {
        val killed = CountDownLatch(1)
        val listener = object : CommandListener {
            override fun commandStarted(event: CommandStartedEvent) {
                if (event.commandName == "killCursors") killed.countDown()
            }
        }
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(mongo.connectionString))
            .addCommandListener(listener)
            .build()
        val client = MongoClients.create(settings)
        try {
            val database = client.getDatabase(mongo.databaseName)
            val store = MongoSnapshotStore(database)
            repeat(5) {
                val id = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
                val state = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, id)
                store.save(SimpleSnapshot(state, Clock.systemUTC().millis())).test().verifyComplete()
            }
            val backend = MongoSnapshotQueryBackend(database, batchSize = 1)
            val localGateway = SnapshotQueryGatewayFactory.create(
                schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
                router = QueryRouter { backend },
                objectMapper = JsonSerializer
            ).create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)

            localGateway.streamRecords(Query())
                .test(1)
                .expectNextCount(1)
                .thenCancel()
                .verify()

            check(killed.await(5, TimeUnit.SECONDS))
        } finally {
            client.close()
        }
    }

    private fun aggregateQuery(): Query = Query(
        filter = PredicateExpression(
            LogicalField("aggregateId"),
            PredicateOperator.EQ,
            listOf(JsonNodeFactory.instance.textNode(aggregateId))
        ),
        sort = listOf(QuerySort(LogicalField("aggregateId"), QuerySortDirection.ASC))
    )
}
