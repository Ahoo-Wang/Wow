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
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
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

    private fun context(expression: me.ahoo.wow.api.query.expression.QueryExpression) =
        QueryBackendResolutionContext(
            PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
            PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
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
