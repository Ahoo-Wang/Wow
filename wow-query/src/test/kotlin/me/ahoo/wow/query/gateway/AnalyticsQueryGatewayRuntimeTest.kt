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

package me.ahoo.wow.query.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.analytics.AnalyticsBucketWindow
import me.ahoo.wow.api.query.analytics.AnalyticsDimension
import me.ahoo.wow.api.query.analytics.AnalyticsGrouping
import me.ahoo.wow.api.query.analytics.AnalyticsMetric
import me.ahoo.wow.api.query.analytics.AnalyticsMetricKind
import me.ahoo.wow.api.query.analytics.AnalyticsNumericPolicy
import me.ahoo.wow.api.query.analytics.AnalyticsQuery
import me.ahoo.wow.api.query.analytics.AnalyticsValue
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.AnalyticsQueryCursorLifecycle
import me.ahoo.wow.query.backend.BackendAnalyticsBucket
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsCursorState
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
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.cursor.QueryCursorHmacKey
import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult
import me.ahoo.wow.query.cursor.QueryCursorLeaseEntry
import me.ahoo.wow.query.cursor.QueryCursorLeaseId
import me.ahoo.wow.query.cursor.QueryCursorLeaseStore
import me.ahoo.wow.query.cursor.QueryCursorSigningKeys
import me.ahoo.wow.query.cursor.QueryCursorStoreRevision
import me.ahoo.wow.query.cursor.StoredQueryCursorLease
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import me.ahoo.wow.api.query.analytics.AnalyticsConsistency as PublicAnalyticsConsistency

@OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi::class,
    ExperimentalQueryGatewayApi::class,
)
class AnalyticsQueryGatewayRuntimeTest {
    @Test
    fun `persistent cursor should resume exactly once across runtime nodes`() {
        val store = InMemoryCursorStore()
        val backend = PagingAnalyticsBackend()
        val firstNode = runtime(store, backend, authority = AUTHORITY).analyticsGateway
        val secondNode = runtime(store, backend, authority = AUTHORITY).analyticsGateway

        val first = firstNode.analyze(CALL, query()).block()!!
        first.buckets.single().keys.assert().containsEntry("status", AnalyticsValue.of("A"))
        first.nextCursor.assert().isNotNull()
        store.size.assert().isEqualTo(1)

        val second = secondNode.analyze(CALL, query(first.nextCursor)).block()!!
        second.buckets.single().keys.assert().containsEntry("status", AnalyticsValue.of("B"))
        second.nextCursor.assert().isNull()
        store.size.assert().isZero()
        backend.calls.get().assert().isEqualTo(2)

        assertThrownBy<QueryExecutionException> {
            firstNode.analyze(CALL, query(first.nextCursor)).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.INVALID_CURSOR)
                error.path.assert().isEqualTo("$.cursor")
                error.code.assert().isEqualTo("INVALID_CURSOR_TOKEN")
            }
        )
        backend.calls.get().assert().isEqualTo(2)
    }

    @Test
    fun `wrong security or mapping binding must not consume a cursor`() {
        val store = InMemoryCursorStore()
        val backend = PagingAnalyticsBackend()
        val first = runtime(store, backend, authority = AUTHORITY).analyticsGateway
            .analyze(CALL, query()).block()!!
        val token = checkNotNull(first.nextCursor)

        val wrongAuthority = QueryAuthority.System("other", "different-security-context")
        assertInvalidBinding {
            runtime(store, backend, authority = wrongAuthority).analyticsGateway
                .analyze(CALL, query(token)).block()
        }
        store.size.assert().isEqualTo(1)

        assertInvalidBinding {
            runtime(store, backend, authority = AUTHORITY, mappingDigest = "b".repeat(64)).analyticsGateway
                .analyze(CALL, query(token)).block()
        }
        store.size.assert().isEqualTo(1)

        runtime(store, backend, authority = AUTHORITY).analyticsGateway
            .analyze(CALL, query(token)).block()!!.nextCursor.assert().isNull()
        store.size.assert().isZero()
    }

    @Test
    fun `cursor continuation must not remove or relax the initial execution budget`() {
        val store = InMemoryCursorStore()
        val backend = PagingAnalyticsBackend()
        val gateway = runtime(store, backend, authority = AUTHORITY).analyticsGateway
        val boundedCall = CALL.copy(
            budget = QueryExecutionBudget(
                maxScannedRecords = 100,
                maxCandidateBuckets = 10,
                maxCursorPages = 2,
            ),
        )
        val cursor = gateway.analyze(boundedCall, query()).block()!!.nextCursor

        listOf(
            CALL,
            boundedCall.copy(
                budget = boundedCall.budget.copy(maxScannedRecords = 101),
            ),
            boundedCall.copy(
                budget = boundedCall.budget.copy(allowDiskUse = true),
            ),
        ).forEach { relaxed ->
            assertThrownBy<QueryExecutionException> {
                gateway.analyze(relaxed, query(cursor)).block()
            }.satisfies(
                Consumer { error ->
                    error.category.assert().isEqualTo(QueryErrorCategory.BUDGET_EXCEEDED)
                    error.path.assert().isEqualTo("$.cursor")
                    error.code.assert().isEqualTo("CURSOR_BUDGET_RELAXATION_NOT_ALLOWED")
                },
            )
            store.size.assert().isEqualTo(1)
        }

        gateway.analyze(boundedCall, query(cursor)).block()!!.nextCursor.assert().isNull()
        store.size.assert().isZero()
    }

    @Test
    fun `numeric policy scale should be preserved in the public decimal representation`() {
        val gateway = runtime(null, NumericAnalyticsBackend, authority = AUTHORITY).analyticsGateway

        val page = gateway.analyze(CALL, numericQuery()).block()!!

        page.buckets.single().metrics.getValue("total").value.assert().isEqualTo("120.50")
    }

    @Test
    fun `grouped analytics without persistent cursor store should reject before backend`() {
        val backend = PagingAnalyticsBackend()
        val runtime = runtime(null, backend, authority = AUTHORITY)

        assertThrownBy<QueryExecutionException> {
            runtime.analyticsGateway.analyze(CALL, query()).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.UNSUPPORTED_FEATURE)
                error.path.assert().isEqualTo("$.cursor")
                error.code.assert().isEqualTo("CURSOR_STORE_REQUIRED")
            }
        )
        backend.calls.get().assert().isZero()
    }

    @Test
    fun `snapshot analytics without a backend cursor lifecycle should reject before backend`() {
        val backend = PagingAnalyticsBackend()
        val runtime = runtime(InMemoryCursorStore(), backend, authority = AUTHORITY)

        assertThrownBy<QueryExecutionException> {
            runtime.analyticsGateway.analyze(CALL, query(consistency = PublicAnalyticsConsistency.SNAPSHOT)).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.BACKEND_UNAVAILABLE)
                error.path.assert().isEqualTo("$.backend")
                error.code.assert().isEqualTo("BACKEND_OPERATION_UNSUPPORTED")
            },
        )
        backend.calls.get().assert().isZero()
    }

    @Test
    fun `snapshot cursor should transfer opaque backend state and close the terminal lease`() {
        val store = InMemoryCursorStore()
        val backend = SnapshotPagingAnalyticsBackend()
        val runtime = runtime(store, backend, authority = AUTHORITY)

        val first = runtime.analyticsGateway.analyze(CALL, query(consistency = PublicAnalyticsConsistency.SNAPSHOT))
            .block()!!
        first.nextCursor.assert().isNotNull()
        backend.continuations.assert().containsExactly(null)

        val second = runtime.analyticsGateway.analyze(
            CALL,
            query(first.nextCursor, PublicAnalyticsConsistency.SNAPSHOT),
        ).block()!!
        second.nextCursor.assert().isNull()
        backend.continuations.assert().containsExactly(null, "pit-1")
        backend.closed.assert().containsExactly("pit-2")
        store.size.assert().isZero()
    }

    @Test
    fun `expired snapshot cursor reaper should acquire once and close its backend state`() {
        val store = InMemoryCursorStore()
        val backend = SnapshotPagingAnalyticsBackend()
        val clock = MutableClock(CLOCK.instant())
        val runtime = runtime(store, backend, authority = AUTHORITY, clock = clock)

        runtime.analyticsGateway.analyze(CALL, query(consistency = PublicAnalyticsConsistency.SNAPSHOT)).block()!!
        store.size.assert().isEqualTo(1)
        clock.advance(Duration.ofMinutes(3))

        runtime.reapExpiredQueryCursors(10).block()!!.assert().isEqualTo(1)
        runtime.reapExpiredQueryCursors(10).block()!!.assert().isZero()
        backend.closed.assert().containsExactly("pit-1")
        store.size.assert().isZero()
    }

    @Test
    fun `cursor store rejection should close newly returned snapshot state`() {
        val backend = SnapshotPagingAnalyticsBackend()
        val runtime = runtime(CapacityExceededCursorStore, backend, authority = AUTHORITY)

        assertThrownBy<QueryExecutionException> {
            runtime.analyticsGateway.analyze(CALL, query(consistency = PublicAnalyticsConsistency.SNAPSHOT)).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.BUDGET_EXCEEDED)
                error.path.assert().isEqualTo("$.cursor")
                error.code.assert().isEqualTo("CURSOR_CAPACITY_EXCEEDED")
            },
        )
        backend.closed.assert().containsExactly("pit-1")
    }

    @Test
    fun `continuation error and cancellation should consume the lease and close its snapshot state`() {
        val errorStore = InMemoryCursorStore()
        val failing = SnapshotPagingAnalyticsBackend(failContinuation = true)
        val errorRuntime = runtime(errorStore, failing, authority = AUTHORITY)
        val errorCursor = errorRuntime.analyticsGateway
            .analyze(CALL, query(consistency = PublicAnalyticsConsistency.SNAPSHOT)).block()!!.nextCursor

        assertThrownBy<QueryExecutionException> {
            errorRuntime.analyticsGateway.analyze(
                CALL,
                query(errorCursor, PublicAnalyticsConsistency.SNAPSHOT),
            ).block()
        }
        failing.closed.assert().containsExactly("pit-1")
        errorStore.size.assert().isZero()

        val cancelStore = InMemoryCursorStore()
        val never = SnapshotPagingAnalyticsBackend(neverContinuation = true)
        val cancelRuntime = runtime(cancelStore, never, authority = AUTHORITY)
        val cancelCursor = cancelRuntime.analyticsGateway
            .analyze(CALL, query(consistency = PublicAnalyticsConsistency.SNAPSHOT)).block()!!.nextCursor
        StepVerifier.create(
            cancelRuntime.analyticsGateway.analyze(
                CALL,
                query(cancelCursor, PublicAnalyticsConsistency.SNAPSHOT),
            ),
        ).thenAwait(Duration.ofMillis(10)).thenCancel().verify()
        never.closed.assert().containsExactly("pit-1")
        cancelStore.size.assert().isZero()
    }

    private fun assertInvalidBinding(action: () -> Unit) {
        assertThrownBy<QueryExecutionException>(action).satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.INVALID_CURSOR)
                error.path.assert().isEqualTo("$.cursor")
                error.code.assert().isEqualTo("INVALID_CURSOR_BINDING")
            }
        )
    }

    private fun runtime(
        store: QueryCursorLeaseStore?,
        backend: AnalyticsQueryBackend,
        authority: QueryAuthority,
        mappingDigest: String = "a".repeat(64),
        clock: Clock = CLOCK,
    ): QueryGatewayRuntime {
        val composition = composition(backend, mappingDigest)
        val arguments = RuntimeArguments(composition, authority)
        return if (store == null) {
            QueryGatewayRuntime.create(
                arguments.aggregates,
                composition,
                arguments.raw,
                arguments.dialect,
                arguments.authority,
                executionProfiles = PROFILES,
                clock = clock,
            )
        } else {
            QueryGatewayRuntime.create(
                arguments.aggregates,
                composition,
                QueryCursorLeaseConfiguration(
                    store,
                    QueryCursorSigningKeys(QueryCursorHmacKey(1, ByteArray(32) { 7 })),
                ),
                arguments.raw,
                arguments.dialect,
                arguments.authority,
                executionProfiles = PROFILES,
                clock = clock,
            )
        }
    }

    private fun composition(analytics: AnalyticsQueryBackend, mappingDigest: String): QueryBackendComposition {
        val fields = listOf(
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
            QueryFieldSchema(
                AMOUNT,
                LogicalFieldType.Decimal,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                emptyList(),
                listOf(FieldCapability.AGGREGATABLE),
            ),
        )
        val schema = QueryDocumentSchema(CALL.target, fields, emptyList())
        val backendId = BackendId("probe")
        return QueryBackendComposition(
            listOf(
                RecordQueryBackendContribution(
                    schema,
                    backendId,
                    setOf(QueryOperation.ANALYZE),
                    BackendStreamSupport.NONE,
                    setOf(SemanticTier.PORTABLE),
                    mapOf(
                        DELETED to setOf(FieldCapability.EXACT),
                        STATUS to setOf(FieldCapability.AGGREGATABLE),
                        AMOUNT to setOf(FieldCapability.AGGREGATABLE),
                    ),
                    backend = NO_OP_RECORD_BACKEND,
                    analyticsBackend = analytics,
                    mappingGenerationDigest = mappingDigest,
                ),
            ),
            defaultRoutes = mapOf(CALL.target to backendId),
        )
    }

    private fun query(
        cursor: me.ahoo.wow.api.query.analytics.AnalyticsCursor? = null,
        consistency: PublicAnalyticsConsistency = PublicAnalyticsConsistency.EVENTUAL,
    ) = AnalyticsQuery(
        grouping = AnalyticsGrouping.by(listOf(AnalyticsDimension("status", "state.status"))),
        metrics = listOf(AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT)),
        window = AnalyticsBucketWindow(1, cursor),
        consistency = consistency,
    )

    private fun numericQuery() = AnalyticsQuery(
        grouping = AnalyticsGrouping.global(),
        metrics = listOf(AnalyticsMetric("total", AnalyticsMetricKind.SUM, "state.amount")),
        window = AnalyticsBucketWindow(1),
        numericPolicy = AnalyticsNumericPolicy(scale = 2),
    )

    private data class RuntimeArguments(
        val composition: QueryBackendComposition,
        val publicAuthority: QueryAuthority,
    ) {
        val aggregates: List<NamedAggregate> = listOf(NAMED_AGGREGATE)
        val raw = object : QueryRawServiceSource {
            override fun snapshot(namedAggregate: NamedAggregate) =
                NoOpSnapshotQueryServiceFactory.create<Any>(namedAggregate)

            override fun eventStream(namedAggregate: NamedAggregate) =
                NoOpEventStreamQueryServiceFactory.create(namedAggregate)
        }
        val dialect = QueryLegacyDialectResolver {
            QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
        }
        val authority = QueryAuthorityResolver { Mono.just(publicAuthority) }
    }

    private class PagingAnalyticsBackend : AnalyticsQueryBackend {
        val calls = AtomicInteger()

        override fun analyze(
            plan: me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Mono<BackendAnalyticsPage> {
            calls.incrementAndGet()
            val continued = plan.bucketWindow.afterKey != null
            val status = if (continued) "B" else "A"
            return Mono.just(
                BackendAnalyticsPage(
                    listOf(
                        BackendAnalyticsBucket(
                            mapOf(AnalyticsAlias("status") to NormalizedValue.Text(status)),
                            mapOf(AnalyticsAlias("count") to NormalizedValue.Int64(1)),
                        ),
                    ),
                    if (continued) null else listOf(NormalizedValue.Text("A")),
                    BackendAnalyticsConsistency.EVENTUAL,
                    BackendAnalyticsCompleteness.EXACT,
                ),
            )
        }
    }

    private object NumericAnalyticsBackend : AnalyticsQueryBackend {
        override fun analyze(
            plan: me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Mono<BackendAnalyticsPage> = Mono.just(
            BackendAnalyticsPage(
                listOf(
                    BackendAnalyticsBucket(
                        emptyMap(),
                        mapOf(AnalyticsAlias("total") to NormalizedValue.Decimal(BigDecimal("120.5"))),
                    ),
                ),
                null,
                BackendAnalyticsConsistency.EVENTUAL,
                BackendAnalyticsCompleteness.EXACT,
            ),
        )
    }

    private class SnapshotPagingAnalyticsBackend(
        private val failContinuation: Boolean = false,
        private val neverContinuation: Boolean = false,
    ) : AnalyticsQueryBackend, AnalyticsQueryCursorLifecycle {
        val continuations = mutableListOf<String?>()
        val closed = mutableListOf<String>()

        override fun analyze(
            plan: me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan,
            options: QueryBackendExecutionOptions,
        ): Mono<BackendAnalyticsPage> = analyze(plan, options, null)

        override fun analyze(
            plan: me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan,
            options: QueryBackendExecutionOptions,
            cursorState: BackendAnalyticsCursorState?,
        ): Mono<BackendAnalyticsPage> = Mono.fromSupplier {
            val current = cursorState?.payload()?.decodeToString()
            continuations += current
            val continued = plan.bucketWindow.afterKey != null
            if (continued && failContinuation) {
                throw QueryBackendException(QueryBackendFailureKind.UNAVAILABLE)
            }
            BackendAnalyticsPage(
                listOf(
                    BackendAnalyticsBucket(
                        mapOf(AnalyticsAlias("status") to NormalizedValue.Text(if (continued) "B" else "A")),
                        mapOf(AnalyticsAlias("count") to NormalizedValue.Int64(1)),
                    ),
                ),
                if (continued) null else listOf(NormalizedValue.Text("A")),
                BackendAnalyticsConsistency.SNAPSHOT,
                BackendAnalyticsCompleteness.EXACT,
                BackendAnalyticsCursorState(
                    if (continued) "pit-2".encodeToByteArray() else "pit-1".encodeToByteArray(),
                ),
            )
        }.let { result -> if (neverContinuation && plan.bucketWindow.afterKey != null) Mono.never() else result }

        override fun close(cursorState: BackendAnalyticsCursorState): Mono<Void> = Mono.fromRunnable {
            closed += cursorState.payload().decodeToString()
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        private var current: Instant = initial

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current
    }

    private class InMemoryCursorStore : QueryCursorLeaseStore {
        private val revisions = AtomicLong()
        private val entries = ConcurrentHashMap<QueryCursorLeaseId, StoredQueryCursorLease>()
        val size: Int
            get() = entries.size

        override fun create(entry: QueryCursorLeaseEntry): Mono<QueryCursorLeaseCreateResult> = Mono.fromSupplier {
            val stored = StoredQueryCursorLease(entry, QueryCursorStoreRevision(revisions.incrementAndGet().toString()))
            if (entries.putIfAbsent(entry.id, stored) == null) {
                QueryCursorLeaseCreateResult.CREATED
            } else {
                QueryCursorLeaseCreateResult.COLLISION
            }
        }

        override fun load(id: QueryCursorLeaseId): Mono<StoredQueryCursorLease> = Mono.justOrEmpty(entries[id])

        override fun compareAndDelete(expected: StoredQueryCursorLease): Mono<Boolean> = Mono.fromSupplier {
            entries.remove(expected.entry.id, expected)
        }

        override fun scanExpired(
            before: Instant,
            afterId: QueryCursorLeaseId?,
            limit: Int,
        ): Flux<StoredQueryCursorLease> = Flux.fromIterable(
            entries.values.filter { stored -> !stored.entry.expiresAt.isAfter(before) }
                .sortedBy { stored -> stored.entry.id.value }
                .filter { stored -> afterId == null || stored.entry.id.value > afterId.value }
                .take(limit),
        )
    }

    private object CapacityExceededCursorStore : QueryCursorLeaseStore {
        override fun create(entry: QueryCursorLeaseEntry): Mono<QueryCursorLeaseCreateResult> =
            Mono.just(QueryCursorLeaseCreateResult.CAPACITY_EXCEEDED)

        override fun load(id: QueryCursorLeaseId): Mono<StoredQueryCursorLease> = Mono.empty()

        override fun compareAndDelete(expected: StoredQueryCursorLease): Mono<Boolean> = Mono.just(false)

        override fun scanExpired(
            before: Instant,
            afterId: QueryCursorLeaseId?,
            limit: Int,
        ): Flux<StoredQueryCursorLease> = Flux.empty()
    }

    private companion object {
        val NAMED_AGGREGATE = MaterializedNamedAggregate("sales", "order")
        val CALL = QueryCall(
            QueryTarget(NAMED_AGGREGATE, QueryDocumentKind.SNAPSHOT),
            QueryPurpose("analytics-test"),
        )
        val AUTHORITY = QueryAuthority.System("test", "analytics-runtime")
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC)
        val PROFILES = QueryExecutionProfiles(
            operationProfiles = mapOf(
                QueryOperationProfileKey(CALL.target, QueryOperation.ANALYZE) to
                    QueryExecutionProfile(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT),
            ),
        )
        val DELETED = QueryFieldId.System(SystemFieldKind.DELETED)
        val STATE = QueryFieldId.Path(listOf("state"))
        val STATUS = QueryFieldId.Path(listOf("state", "status"))
        val AMOUNT = QueryFieldId.Path(listOf("state", "amount"))
        val NO_OP_RECORD_BACKEND = object : RecordQueryBackend {
            override fun single(
                plan: BackendSingleQueryPlan,
                options: QueryBackendExecutionOptions
            ): Mono<BackendRecord> =
                Mono.empty()

            override fun stream(plan: BackendStreamQueryPlan, options: QueryBackendExecutionOptions): Flux<BackendRecord> =
                Flux.empty()

            override fun page(
                plan: me.ahoo.wow.query.backend.BackendPageQueryPlan,
                options: QueryBackendExecutionOptions,
            ) = Mono.empty<me.ahoo.wow.query.backend.BackendPage>()

            override fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions): Mono<Long> =
                Mono.empty()
        }
    }
}
