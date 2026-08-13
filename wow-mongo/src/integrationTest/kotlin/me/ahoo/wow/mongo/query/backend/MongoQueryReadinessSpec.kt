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
import com.mongodb.client.model.Indexes
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandListener
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendReadinessReason
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.schema.QueryBackendFieldPath
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryCapabilityBinding
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.StringQueryOptions
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.backend.ObservableQueryBackendFactory
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendClientHold
import me.ahoo.wow.tck.query.backend.QueryBackendTestKit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.bson.Document
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MongoQueryReadinessSpec {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("mongo_query_readiness")

    private val commands = ConcurrentHashMap<String, AtomicLong>()
    private lateinit var client: MongoClient

    @BeforeEach
    fun createObservedClient() {
        val settings = MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(mongo.connectionString))
            .addCommandListener(object : CommandListener {
                override fun commandStarted(event: CommandStartedEvent) {
                    commands.computeIfAbsent(event.commandName) { AtomicLong() }.incrementAndGet()
                }
            })
            .build()
        client = MongoClients.create(settings)
    }

    @AfterEach
    fun closeClient() {
        client.close()
    }

    @Test
    fun `missing collection is not ready with one collection inspection`() {
        val backend = MongoQueryBackendFactory(database()).bind(context(PortableQueryDataset.vectors.first().expression))

        verifyReadiness(backend.readiness(), QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING))

        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isZero()
    }

    @Test
    fun `missing collection fails at query time before backend execution`() {
        val notReady = QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING)
        val factory = NoopObservableFactory(MongoQueryBackendFactory(database()))
        val testKit = QueryBackendTestKit(
            factory,
            QueryDocumentKind.SNAPSHOT,
            expectedReadiness = notReady
        )

        StepVerifier.create(
            testKit.gateway.list(
                ListQueryRequest(
                    target = testKit.target,
                    expression = MatchAll,
                    resultShape = QueryResultShape.Dynamic,
                    limit = 0
                )
            )
        ).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
            error.stage.assert().isEqualTo(QueryStage.PLANNING)
            error.reason.assert().isEqualTo(QueryErrorReason.BACKEND_UNAVAILABLE)
        }.verify()

        testKit.executionSubscriptionCount.assert().isZero()
        commandCount("listCollections").assert().isOne()
        commandCount("find").assert().isZero()
    }

    @Test
    fun `full text requires one matching text index inspection`() {
        createCollection()
        commands.clear()
        val backend = MongoQueryBackendFactory(database()).bind(context(fullText()))

        verifyReadiness(backend.readiness(), QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING))

        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isOne()
    }

    @Test
    fun `full text is ready after matching text index`() {
        createCollection()
        StepVerifier.create(Mono.from(collection().createIndex(Indexes.text(PortableQueryDataset.TITLE.value))))
            .expectNextCount(1)
            .verifyComplete()
        commands.clear()
        val backend = MongoQueryBackendFactory(database()).bind(context(fullText()))

        verifyReadiness(backend.readiness(), QueryBackendReadiness.Ready)

        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isOne()
    }

    @Test
    fun `compound text index with an ordinary prefix is conservatively not ready`() {
        createCollection()
        StepVerifier.create(
            Mono.from(
                collection().createIndex(
                    Indexes.compoundIndex(
                        Indexes.ascending("tenant"),
                        Indexes.text(PortableQueryDataset.TITLE.value)
                    )
                )
            )
        ).expectNextCount(1).verifyComplete()
        commands.clear()

        verifyReadiness(
            MongoQueryBackendFactory(database()).bind(context(fullText())).readiness(),
            QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING)
        )

        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isOne()
        commandCount("find").assert().isZero()
        commandCount("aggregate").assert().isZero()
    }

    @Test
    fun `full text uses search binding and requires the exact searchable field set`() {
        createCollection()
        StepVerifier.create(
            Mono.from(
                collection().createIndex(
                    Indexes.compoundIndex(Indexes.text("search_title"), Indexes.text("secret"))
                )
            )
        ).expectNextCount(1).verifyComplete()
        commands.clear()
        val schema = schemaWithTitle { title ->
            title.copy(
                bindings = setOf(
                    QueryCapabilityBinding(
                        QueryBackendId(MongoQueryBackendFactory.BACKEND_ID),
                        QueryFieldUsage.SEARCH,
                        QueryBackendFieldPath("search_title")
                    )
                )
            )
        }
        val backend = MongoQueryBackendFactory(database()).bind(context(fullText(), schema))

        verifyReadiness(backend.readiness(), QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING))

        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isOne()
        commandCount("find").assert().isZero()
        commandCount("aggregate").assert().isZero()
    }

    @Test
    fun `full text search binding is ready only when the text index has no extra fields`() {
        createCollection()
        StepVerifier.create(Mono.from(collection().createIndex(Indexes.text("search_title"))))
            .expectNextCount(1)
            .verifyComplete()
        commands.clear()
        val schema = schemaWithTitle { title ->
            title.copy(
                bindings = setOf(
                    QueryCapabilityBinding(
                        QueryBackendId(MongoQueryBackendFactory.BACKEND_ID),
                        QueryFieldUsage.SEARCH,
                        QueryBackendFieldPath("search_title")
                    )
                )
            )
        }

        verifyReadiness(
            MongoQueryBackendFactory(database()).bind(context(fullText(), schema)).readiness(),
            QueryBackendReadiness.Ready
        )
        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isOne()
    }

    @Test
    fun `wildcard text index is conservatively not ready for declared search fields`() {
        createCollection()
        StepVerifier.create(Mono.from(collection().createIndex(Indexes.text("\$**"))))
            .expectNextCount(1)
            .verifyComplete()
        commands.clear()

        verifyReadiness(
            MongoQueryBackendFactory(database()).bind(context(fullText())).readiness(),
            QueryBackendReadiness.NotReady(QueryBackendReadinessReason.INDEX_MISSING)
        )
        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isOne()
        commandCount("find").assert().isZero()
        commandCount("aggregate").assert().isZero()
    }

    @Test
    fun `invalid full text structures fail synchronously before collection io`() {
        val invalid = listOf(
            fullText().copy(capabilityId = QueryCapabilityId("x-wow:wrong-full-text")),
            fullText().copy(fields = setOf(LogicalField("undeclared"))),
            LogicalExpression(LogicalOperator.OR, listOf(MatchAll, fullText())),
            LogicalExpression(LogicalOperator.NOR, listOf(MatchAll, fullText())),
            LogicalExpression(LogicalOperator.AND, listOf(fullText(), fullText()))
        )

        invalid.forEach { expression ->
            val error = assertThrows<QueryException> {
                MongoQueryBackendFactory(database()).bind(context(expression))
            }
            error.code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
            error.stage.assert().isEqualTo(QueryStage.PLANNING)
            error.reason.assert().isEqualTo(QueryErrorReason.CAPABILITY_DENIED)
            error.message.assert().isEqualTo("UNSUPPORTED_CAPABILITY:PLANNING:CAPABILITY_DENIED")
        }
        commands.values.sumOf(AtomicLong::get).assert().isZero()
    }

    @Test
    fun `default string comparison is an explicit case-sensitive literal when collation is absent`() {
        createCollection()
        StepVerifier.create(
            collection().insertMany(
                listOf(
                    Document("_id", "upper").append("title", "Alpha").append("deleted", false),
                    Document("_id", "lower").append("title", "alpha").append("deleted", false)
                )
            )
        )
            .expectNextCount(1)
            .verifyComplete()
        commands.clear()
        val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
        val gateway = MongoQueryGatewayHarness(
            PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
            schema,
            MongoQueryBackendFactory(database()),
            fieldAccess = QueryFieldAccess.Restricted(
                setOf(LogicalField("aggregateId"), LogicalField("deleted"), PortableQueryDataset.TITLE)
            )
        ).gateway

        StepVerifier.create(
            gateway.list(
                ListQueryRequest(
                    target = schema.target,
                    expression = contains("Alpha"),
                    resultShape = QueryResultShape.Dynamic,
                    limit = 0
                )
            )
        ).assertNext { result -> result[PortableQueryDataset.TITLE.value].assert().isEqualTo("Alpha") }
            .verifyComplete()

        commandCount("find").assert().isOne()
    }

    @Test
    fun `default string comparison with configured or unknown collation is not ready before collection io`() {
        listOf(StringQueryOptions(collation = "en"), null).forEach { options ->
            val schema = schemaWithTitle { title -> title.copy(stringOptions = options) }
            val backend = MongoQueryBackendFactory(database()).bind(context(contains("Alpha"), schema))

            verifyReadiness(
                backend.readiness(),
                QueryBackendReadiness.NotReady(QueryBackendReadinessReason.CONFIGURATION_INVALID)
            )
        }
        commands.values.sumOf(AtomicLong::get).assert().isZero()
    }

    @Test
    fun `explicit string comparison remains ready with configured collation`() {
        createCollection()
        commands.clear()
        val schema = schemaWithTitle { title -> title.copy(stringOptions = StringQueryOptions(collation = "en")) }
        val backend = MongoQueryBackendFactory(database()).bind(
            context(contains("Alpha", StringComparisonMode.CASE_INSENSITIVE), schema)
        )

        verifyReadiness(backend.readiness(), QueryBackendReadiness.Ready)
        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isZero()
    }

    @Test
    fun `invalid native declarations fail before collection io with low information tuple`() {
        listOf(
            native("unknown"),
            native("unknown").copy(backendId = "elasticsearch"),
            native("unknown").copy(capabilityId = QueryCapabilityId("x-wow:wrong-native"))
        ).forEach { expression ->
            val error = assertThrows<QueryException> {
                MongoQueryBackendFactory(database()).bind(context(expression))
            }
            error.code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
            error.stage.assert().isEqualTo(QueryStage.PLANNING)
            error.reason.assert().isEqualTo(QueryErrorReason.CAPABILITY_DENIED)
            error.message.assert().isEqualTo("UNSUPPORTED_CAPABILITY:PLANNING:CAPABILITY_DENIED")
        }
        commands.values.sumOf(AtomicLong::get).assert().isZero()
    }

    @Test
    fun `registered native template is ready without index inspection`() {
        createCollection()
        commands.clear()
        val registry = MongoNativeQueryTemplateRegistry(mapOf("registered" to MongoNativeQueryTemplate { org.bson.Document() }))
        val backend = MongoQueryBackendFactory(database(), registry).bind(context(native("registered")))

        verifyReadiness(backend.readiness(), QueryBackendReadiness.Ready)

        commandCount("listCollections").assert().isOne()
        commandCount("listIndexes").assert().isZero()
    }

    private fun createCollection() {
        StepVerifier.create(database().createCollection(collectionName())).verifyComplete()
    }

    private fun verifyReadiness(
        publisher: Mono<QueryBackendReadiness>,
        expected: QueryBackendReadiness
    ) {
        StepVerifier.create(publisher).expectNext(expected).verifyComplete()
    }

    private fun context(
        expression: me.ahoo.wow.api.query.expression.QueryExpression,
        schema: QuerySchema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
    ) =
        QueryBackendResolutionContext(
            PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
            schema,
            expression
        )

    private fun fullText() = FullTextExpression(
        QueryCapabilityId(MongoQueryBackendFactory.FULL_TEXT_CAPABILITY),
        "alpha",
        setOf(PortableQueryDataset.TITLE)
    )

    private fun native(templateId: String) = NativeExpression(
        QueryCapabilityId(MongoQueryBackendFactory.NATIVE_CAPABILITY),
        MongoQueryBackendFactory.BACKEND_ID,
        templateId,
        emptyMap(),
        setOf(PortableQueryDataset.TITLE)
    )

    private fun contains(value: String, comparison: StringComparisonMode = StringComparisonMode.DEFAULT) =
        PredicateExpression(
            PortableQueryDataset.TITLE,
            PortableOperator.CONTAINS,
            listOf(QueryValue.StringValue(value)),
            comparison
        )

    private fun schemaWithTitle(transform: (me.ahoo.wow.query.schema.QueryFieldSchema) -> me.ahoo.wow.query.schema.QueryFieldSchema): QuerySchema {
        val original = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT)
        return QuerySchema(
            original.target,
            original.fields.values.map { field ->
                if (field.path == PortableQueryDataset.TITLE) transform(field) else field
            }
        )
    }

    private fun database() = client.getDatabase(mongo.databaseName)

    private fun collection() = database().getCollection(collectionName())

    private fun collectionName() = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
        .namedAggregate.toSnapshotCollectionName()

    private fun commandCount(commandName: String): Long = commands[commandName]?.get() ?: 0
}

private class NoopObservableFactory(
    private val delegate: QueryBackendFactory
) : ObservableQueryBackendFactory {
    override val subscriptionCount: Long = 0
    override val cancellationCount: Long = 0

    override fun bind(context: QueryBackendResolutionContext): QueryBackend = delegate.bind(context)

    override fun reset() = Unit

    override fun holdNextList(hold: QueryBackendClientHold) = Unit
}
