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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.mongo.query.cursor

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.analytics.AnalyticsBucketWindow
import me.ahoo.wow.api.query.analytics.AnalyticsDimension
import me.ahoo.wow.api.query.analytics.AnalyticsGrouping
import me.ahoo.wow.api.query.analytics.AnalyticsMetric
import me.ahoo.wow.api.query.analytics.AnalyticsMetricKind
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import me.ahoo.wow.api.query.analytics.AnalyticsValue
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.BackendAnalyticsBucket
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsPage
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.BackendRecord
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendStreamSupport
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.cursor.QueryCursorHmacKey
import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.cursor.QueryCursorSigningKeys
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryElementPathMode
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.gateway.QueryExecutionMode
import me.ahoo.wow.query.gateway.QueryExecutionProfile
import me.ahoo.wow.query.gateway.QueryExecutionProfiles
import me.ahoo.wow.query.gateway.QueryGatewayRuntime
import me.ahoo.wow.query.gateway.QueryLegacyDialect
import me.ahoo.wow.query.gateway.QueryLegacyDialectResolver
import me.ahoo.wow.query.gateway.QueryMatchScopeMode
import me.ahoo.wow.query.gateway.QueryOperation
import me.ahoo.wow.query.gateway.QueryOperationProfileKey
import me.ahoo.wow.query.gateway.QueryPurpose
import me.ahoo.wow.query.gateway.QueryRawServiceSource
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryValidationMode
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.tck.container.MongoTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class MongoAnalyticsCursorGatewayIntegrationTest {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("analytics_cursor_gateway")

    @Test
    fun `should replay an analytics cursor across nodes and rotate signing keys exactly once`() {
        val options = MongoQueryCursorLeaseStoreOptions(collectionName = "analytics_cursor_lease", maxEntries = 8)
        val firstStore = MongoQueryCursorLeaseStore(mongo.database(), options, CLOCK)
        val secondStore = MongoQueryCursorLeaseStore(
            mongo.newClient().getDatabase(mongo.databaseName),
            options,
            CLOCK,
        )
        firstStore.ensureIndexes().block()
        val backend = ThreePageAnalyticsBackend()
        val firstNode = runtime(firstStore, backend, QueryCursorSigningKeys(KEY_ONE)).analyticsGateway
        val secondNode = runtime(
            secondStore,
            backend,
            QueryCursorSigningKeys(KEY_TWO, listOf(KEY_ONE)),
        ).analyticsGateway

        val first = firstNode.analyze(CALL, query()).block()!!
        first.buckets.single().keys.assert().containsEntry("status", AnalyticsValue.of("A"))
        val firstToken = checkNotNull(first.nextCursor)

        val second = secondNode.analyze(CALL, query(firstToken)).block()!!
        second.buckets.single().keys.assert().containsEntry("status", AnalyticsValue.of("B"))
        val rotatedToken = checkNotNull(second.nextCursor)

        assertInvalidCursor {
            firstNode.analyze(CALL, query(rotatedToken)).block()
        }
        val third = secondNode.analyze(CALL, query(rotatedToken)).block()!!
        third.buckets.single().keys.assert().containsEntry("status", AnalyticsValue.of("C"))
        third.nextCursor.assert().isNull()

        assertInvalidCursor {
            secondNode.analyze(CALL, query(firstToken)).block()
        }
        backend.calls.get().assert().isEqualTo(3)
    }

    private fun runtime(
        store: MongoQueryCursorLeaseStore,
        backend: AnalyticsQueryBackend,
        keys: QueryCursorSigningKeys,
    ): QueryGatewayRuntime = QueryGatewayRuntime.create(
        namedAggregates = listOf(AGGREGATE),
        backendComposition = composition(backend),
        cursorLeaseConfiguration = QueryCursorLeaseConfiguration(store, keys),
        rawServiceSource = object : QueryRawServiceSource {
            override fun snapshot(namedAggregate: NamedAggregate) =
                NoOpSnapshotQueryServiceFactory.create<Any>(namedAggregate)

            override fun eventStream(namedAggregate: NamedAggregate) =
                NoOpEventStreamQueryServiceFactory.create(namedAggregate)
        },
        dialectResolver = QueryLegacyDialectResolver {
            QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
        },
        authorityResolver = QueryAuthorityResolver {
            Mono.just(QueryAuthority.System("mongo-cursor-test", "cross-node cursor"))
        },
        executionProfiles = PROFILES,
        clock = CLOCK,
    )

    private fun composition(backend: AnalyticsQueryBackend): QueryBackendComposition = QueryBackendComposition(
        listOf(
            RecordQueryBackendContribution(
                schema = SCHEMA,
                backendId = BACKEND_ID,
                supportedOperations = setOf(QueryOperation.ANALYZE),
                streamSupport = BackendStreamSupport.NONE,
                semanticTiers = setOf(SemanticTier.PORTABLE),
                fieldCapabilities = mapOf(
                    DELETED to setOf(FieldCapability.EXACT),
                    STATUS to setOf(FieldCapability.AGGREGATABLE),
                ),
                backend = NoOpRecordBackend,
                analyticsBackend = backend,
                mappingGenerationDigest = "a".repeat(64),
            ),
        ),
        mapOf(TARGET to BACKEND_ID),
    )

    private fun query(cursor: me.ahoo.wow.api.query.analytics.AnalyticsCursor? = null): AnalyticsQuery = AnalyticsQuery(
        grouping = AnalyticsGrouping.by(listOf(AnalyticsDimension("status", "state.status"))),
        metrics = listOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT)),
        window = AnalyticsBucketWindow(1, cursor),
    )

    private fun assertInvalidCursor(action: () -> Unit) {
        assertThrownBy<QueryExecutionException>(action).satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.INVALID_CURSOR)
                error.path.assert().isEqualTo("$.cursor")
                error.code.assert().isEqualTo("INVALID_CURSOR_TOKEN")
            },
        )
    }

    private class ThreePageAnalyticsBackend : AnalyticsQueryBackend {
        val calls = AtomicInteger()

        override fun analyze(
            plan: me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Mono<BackendAnalyticsPage> {
            calls.incrementAndGet()
            val previous = (plan.bucketWindow.afterKey?.singleOrNull() as? NormalizedValue.Text)?.value
            val current = when (previous) {
                null -> "A"
                "A" -> "B"
                "B" -> "C"
                else -> error("Unexpected analytics cursor position: $previous")
            }
            return Mono.just(
                BackendAnalyticsPage(
                    listOf(
                        BackendAnalyticsBucket(
                            mapOf(AnalyticsAlias("status") to NormalizedValue.Text(current)),
                            mapOf(AnalyticsAlias("count") to NormalizedValue.Int64(1)),
                        ),
                    ),
                    if (current == "C") null else listOf(NormalizedValue.Text(current)),
                    BackendAnalyticsConsistency.EVENTUAL,
                    BackendAnalyticsCompleteness.EXACT,
                ),
            )
        }
    }

    private object NoOpRecordBackend : RecordQueryBackend {
        override fun single(
            plan: BackendSingleQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Mono<BackendRecord> = Mono.empty()

        override fun stream(
            plan: BackendStreamQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Flux<BackendRecord> = Flux.empty()

        override fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions): Mono<Long> = Mono.just(0)
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC)
        val KEY_ONE = QueryCursorHmacKey(1, ByteArray(32) { 1 })
        val KEY_TWO = QueryCursorHmacKey(2, ByteArray(32) { 2 })
        val AGGREGATE = MaterializedNamedAggregate("sales", "order")
        val TARGET = QueryTarget(AGGREGATE, QueryDocumentKind.SNAPSHOT)
        val CALL = QueryCall(TARGET, QueryPurpose("mongo-cursor-integration"))
        val BACKEND_ID = BackendId("mongo-cursor-test")
        val DELETED = QueryFieldId.System(SystemFieldKind.DELETED)
        val STATE = QueryFieldId.Path(listOf("state"))
        val STATUS = QueryFieldId.Path(listOf("state", "status"))
        val SCHEMA = QueryDocumentSchema(
            TARGET,
            listOf(
                QueryFieldSchema(
                    DELETED,
                    LogicalFieldType.Boolean,
                    Presence.REQUIRED,
                    Nullability.NON_NULL,
                    listOf(PredicateOperator.IS_TRUE, PredicateOperator.IS_FALSE),
                    listOf(FieldCapability.EXACT),
                ),
                QueryFieldSchema(
                    STATE,
                    LogicalFieldType.Object,
                    Presence.REQUIRED,
                    Nullability.NON_NULL,
                    emptyList(),
                    emptyList(),
                ),
                QueryFieldSchema(
                    STATUS,
                    LogicalFieldType.Text,
                    Presence.REQUIRED,
                    Nullability.NON_NULL,
                    emptyList(),
                    listOf(FieldCapability.AGGREGATABLE),
                ),
            ),
            emptyList(),
        )
        val PROFILES = QueryExecutionProfiles(
            operationProfiles = mapOf(
                QueryOperationProfileKey(TARGET, QueryOperation.ANALYZE) to
                    QueryExecutionProfile(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT),
            ),
        )
    }
}
