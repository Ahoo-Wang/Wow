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
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.mongo.query.planned

import com.mongodb.ExplainVerbosity
import com.mongodb.MongoNamespace
import com.mongodb.client.model.Collation
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.BackendAnalyticsBucketOrder
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness
import me.ahoo.wow.query.backend.BackendAnalyticsCondition
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency
import me.ahoo.wow.query.backend.BackendAnalyticsDimension
import me.ahoo.wow.query.backend.BackendAnalyticsGrouping
import me.ahoo.wow.query.backend.BackendAnalyticsMetric
import me.ahoo.wow.query.backend.BackendAnalyticsMissingPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsNullPlacement
import me.ahoo.wow.query.backend.BackendAnalyticsNumericPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsNumericPromotion
import me.ahoo.wow.query.backend.BackendAnalyticsOverflowPolicy
import me.ahoo.wow.query.backend.BackendAnalyticsPageWindow
import me.ahoo.wow.query.backend.BackendAnalyticsQueryPlan
import me.ahoo.wow.query.backend.BackendAnalyticsTextCollation
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageConsistency
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRecordCompleteness
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendRequiredConsistency
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendTotalMode
import me.ahoo.wow.query.backend.BackendTotalRelation
import me.ahoo.wow.query.backend.EmptyArraySemantics
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.JunctionOperator
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.QuerySearchScopeDefinition
import me.ahoo.wow.query.backend.RecordResultShape
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.SearchScopeId
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryElementPathMode
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
import me.ahoo.wow.query.gateway.QueryRuntimeHealthObserver
import me.ahoo.wow.query.gateway.QueryShadowObservation
import me.ahoo.wow.query.gateway.QueryShadowObserver
import me.ahoo.wow.query.gateway.QueryShadowOutcome
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.query.gateway.QueryValidationMode
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.mongo.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.query.ExactNumericAnalyticsExpectation
import me.ahoo.wow.tck.query.PlannedAnalyticsQueryBackendSpec
import me.ahoo.wow.tck.query.PlannedRecordQueryBackendSpec
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toMono
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicInteger

class MongoSnapshotRecordQueryBackendIntegrationTest :
    PlannedAnalyticsQueryBackendSpec,
    PlannedRecordQueryBackendSpec {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture("planned_query")

    private lateinit var fixture: Fixture

    override val analyticsBackend: AnalyticsQueryBackend
        get() = fixture.binding.prepareContribution(fixture.collection).block()!!.analyticsBackend!!
    override val analyticsOptions: QueryBackendExecutionOptions
        get() = fixture.analyticsOptions().copy(maxReturnedBuckets = 10)
    override val expectedGlobalCount: Long = 2
    override val expectedUnrestrictedGlobalCount: Long = 4
    override val expectedFirstKey: NormalizedValue = NormalizedValue.Text("CREATED")
    override val expectedSecondKey: NormalizedValue = NormalizedValue.Text("PAID")
    override val expectedNullBucketCount: Long = 2
    override val dimensionAlias: AnalyticsAlias = AnalyticsAlias("status")
    override val countAlias: AnalyticsAlias = AnalyticsAlias("count")
    override val exactNumericAnalyticsExpectation: ExactNumericAnalyticsExpectation =
        ExactNumericAnalyticsExpectation.Supported(
            mapOf(
                AnalyticsAlias("minimum") to NormalizedValue.Decimal(BigDecimal.ONE),
                AnalyticsAlias("maximum") to NormalizedValue.Decimal(BigDecimal("2")),
                AnalyticsAlias("total") to NormalizedValue.Decimal(BigDecimal("3")),
                AnalyticsAlias("average") to NormalizedValue.Decimal(BigDecimal("1.5")),
            ),
        )
    override val recordBackend: RecordQueryBackend
        get() = fixture.binding.prepareContribution(fixture.collection).block()!!.backend
    override val recordOptions: QueryBackendExecutionOptions
        get() = OPTIONS
    override val expectedRecordIdentities: List<String> = listOf("order-1", "order-4")

    override fun globalCountPlan(): BackendAnalyticsQueryPlan = fixture.analyticsPlan(
        BackendAnalyticsGrouping.Global,
        listOf(BackendAnalyticsMetric.DocumentCount(countAlias)),
        BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
    )

    override fun unrestrictedGlobalCountPlan(): BackendAnalyticsQueryPlan = fixture.analyticsPlan(
        BackendAnalyticsGrouping.Global,
        listOf(BackendAnalyticsMetric.DocumentCount(countAlias)),
        BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
    )

    override fun groupedCountPlan(afterKey: List<NormalizedValue>?, limit: Int): BackendAnalyticsQueryPlan =
        fixture.analyticsPlan(
            BackendAnalyticsGrouping.By(
                listOf(
                    BackendAnalyticsDimension(
                        dimensionAlias,
                        fixture.status,
                        BackendAnalyticsMissingPolicy.EXCLUDE,
                    ),
                ),
            ),
            listOf(BackendAnalyticsMetric.DocumentCount(countAlias)),
            BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
            BackendAnalyticsPageWindow(limit, afterKey),
        )

    override fun nullBucketCountPlan(): BackendAnalyticsQueryPlan = fixture.analyticsPlan(
        BackendAnalyticsGrouping.By(
            listOf(
                BackendAnalyticsDimension(
                    AnalyticsAlias("note"),
                    fixture.note,
                    BackendAnalyticsMissingPolicy.AS_NULL_BUCKET,
                ),
            ),
        ),
        listOf(BackendAnalyticsMetric.DocumentCount(countAlias)),
        BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
        BackendAnalyticsPageWindow(10),
    )

    override fun exactNumericMetricPlan(): BackendAnalyticsQueryPlan = fixture.analyticsPlan(
        BackendAnalyticsGrouping.Global,
        listOf(
            BackendAnalyticsMetric.Min(AnalyticsAlias("minimum"), fixture.amount),
            BackendAnalyticsMetric.Max(AnalyticsAlias("maximum"), fixture.amount),
            BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), fixture.amount),
            BackendAnalyticsMetric.Average(AnalyticsAlias("average"), fixture.amount),
        ),
        BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
        numericPolicy = fixture.numericPolicy,
    )

    private fun analyticsMandatory() = BackendPlannedCondition.Junction(
        JunctionOperator.AND,
        listOf(
            fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
            fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
        ),
    )

    override fun portableCountPlan(): BackendCountQueryPlan = fixture.countPlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
    )

    override fun portableStreamPlan(): BackendStreamQueryPlan = fixture.streamPlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
    )

    override fun portableSecondPagePlan(): BackendPageQueryPlan = fixture.pagePlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, analyticsMandatory()),
        1,
        1,
    )

    @BeforeEach
    fun setup() {
        fixture = Fixture(MongoNamespace(mongo.databaseName, "order_snapshot"))
        fixture.collection.createIndex(
            Indexes.text("description"),
            IndexOptions().name(TEXT_INDEX),
        ).toMono().block()
        val documents = listOf(
            fixture.document("order-1", "tenant-1", false, "PAID", listOf("priority", "vip"), "paid order", 1),
            fixture.document("order-2", "tenant-2", false, "PAID", listOf("priority"), "paid order", 1L),
            fixture.document(
                "order-3",
                "tenant-1",
                true,
                "PAID",
                listOf("priority"),
                "paid order",
                Decimal128(BigDecimal("1.0")),
            ),
            fixture.document("order-4", "tenant-1", false, "CREATED", emptyList(), "new order", 2),
        )
        documents[0]["note"] = null
        documents[2]["note"] = "value"
        fixture.collection.insertMany(documents).toMono().block()
    }

    @Test
    fun `planned backend should enforce mandatory conditions and preserve identity outside projection`() {
        val backend = fixture.binding.prepareContribution(fixture.collection).block()!!.backend
        val mandatory = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
                fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
            ),
        )
        val user = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.status, PredicateOperator.EQ, NormalizedValue.Text("PAID")),
                fixture.predicate(
                    fixture.tags,
                    PredicateOperator.ALL_IN,
                    NormalizedValue.ListValue(listOf(NormalizedValue.Text("priority"))),
                ),
            ),
        )
        val filter = BackendEnforcedFilter(user, mandatory)

        backend.count(fixture.countPlan(filter), OPTIONS).block().assert().isEqualTo(1)
        val records = backend.stream(fixture.streamPlan(filter), OPTIONS).collectList().block()!!

        records.assert().hasSize(1)
        records.single().identity.assert().isEqualTo("order-1")
        records.single().completeness.assert().isEqualTo(BackendRecordCompleteness.COMPLETE)
        records.single().document.values.keys.assert().containsExactly("state")
        (records.single().document.values.getValue("state") as NormalizedValue.ObjectValue)
            .values.keys.assert().containsExactly("status")
    }

    @Test
    fun `attested text scope should execute against the real Mongo text index`() {
        val contribution = fixture.binding.prepareContribution(fixture.collection).block()!!
        val search = BackendPlannedCondition.Search(fixture.searchScope, "paid")
        val mandatory = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
                fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
            ),
        )

        contribution.searchScopes.assert().containsExactly(fixture.searchScope)
        contribution.semanticTiers.assert().containsExactly(SemanticTier.PORTABLE, SemanticTier.SEARCH)
        contribution.backend.count(
            fixture.countPlan(BackendEnforcedFilter(search, mandatory), SemanticTier.SEARCH),
            OPTIONS,
        ).block().assert().isEqualTo(1)
    }

    @Test
    fun `planned backend should preserve Mongo null missing and numeric equality baseline`() {
        val backend = fixture.binding.prepareContribution(fixture.collection).block()!!.backend

        backend.count(
            fixture.countPlan(
                BackendEnforcedFilter(
                    fixture.predicate(fixture.note, PredicateOperator.IS_NULL),
                    BackendPlannedCondition.All,
                ),
            ),
            OPTIONS,
        ).block().assert().isEqualTo(3)
        backend.count(
            fixture.countPlan(
                BackendEnforcedFilter(
                    fixture.predicate(fixture.note, PredicateOperator.NOT_NULL),
                    BackendPlannedCondition.All,
                ),
            ),
            OPTIONS,
        ).block().assert().isEqualTo(1)
        backend.count(
            fixture.countPlan(
                BackendEnforcedFilter(
                    fixture.predicate(
                        fixture.amount,
                        PredicateOperator.EQ,
                        NormalizedValue.Decimal(BigDecimal("1.00")),
                    ),
                    BackendPlannedCondition.All,
                ),
            ),
            OPTIONS,
        ).block().assert().isEqualTo(3)
    }

    @Test
    fun `planned page should return exact records and total from one facet input`() {
        val backend = fixture.binding.prepareContribution(fixture.collection).block()!!.backend
        val mandatory = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
                fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
            ),
        )

        val page = backend.page(
            fixture.pagePlan(BackendEnforcedFilter(BackendPlannedCondition.All, mandatory), offset = 1, size = 1),
            QueryBackendExecutionOptions(
                deadline = Instant.now().plusSeconds(300),
                maxReturnedRecords = 1,
                maxPageWindow = 2,
                allowDiskUse = true,
            ),
        ).block()!!

        page.total.assert().isEqualTo(2)
        page.totalRelation.assert().isEqualTo(BackendTotalRelation.EXACT)
        page.consistency.assert().isEqualTo(BackendPageConsistency.SAME_INPUT)
        page.records.assert().hasSize(1)
        page.records.single().identity.assert().isEqualTo("order-4")
    }

    @Test
    fun `planned analytics should enforce mandatory filter and compute exact global metrics`() {
        val contribution = fixture.binding.prepareContribution(fixture.collection).block()!!
        val backend = requireNotNull(contribution.analyticsBackend)
        contribution.supportedOperations.assert().contains(QueryOperation.ANALYZE)
        val mandatory = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
                fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
            ),
        )

        val page = backend.analyze(
            fixture.analyticsPlan(
                BackendAnalyticsGrouping.Global,
                listOf(
                    BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
                    BackendAnalyticsMetric.Min(AnalyticsAlias("minimum"), fixture.amount),
                    BackendAnalyticsMetric.Max(AnalyticsAlias("maximum"), fixture.amount),
                    BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), fixture.amount),
                    BackendAnalyticsMetric.Average(AnalyticsAlias("average"), fixture.amount),
                ),
                BackendEnforcedFilter(BackendPlannedCondition.All, mandatory),
                numericPolicy = fixture.numericPolicy,
            ),
            fixture.analyticsOptions(),
        ).block()!!

        page.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
        page.completeness.assert().isEqualTo(BackendAnalyticsCompleteness.EXACT)
        page.afterKey.assert().isNull()
        page.buckets.assert().hasSize(1)
        page.buckets.single().keys.assert().isEmpty()
        page.buckets.single().metrics.assert().containsEntry(AnalyticsAlias("count"), NormalizedValue.Int64(2))
        page.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("minimum"), NormalizedValue.Decimal(BigDecimal.ONE))
        page.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("maximum"), NormalizedValue.Decimal(BigDecimal("2")))
        page.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("total"), NormalizedValue.Decimal(BigDecimal("3")))
        page.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("average"), NormalizedValue.Decimal(BigDecimal("1.5")))

        val empty = backend.analyze(
            fixture.analyticsPlan(
                BackendAnalyticsGrouping.Global,
                listOf(
                    BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
                    BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), fixture.amount),
                    BackendAnalyticsMetric.Average(AnalyticsAlias("average"), fixture.amount),
                ),
                BackendEnforcedFilter(
                    fixture.predicate(
                        fixture.tenant,
                        PredicateOperator.EQ,
                        NormalizedValue.Text("tenant-without-documents"),
                    ),
                    BackendPlannedCondition.All,
                ),
                numericPolicy = fixture.numericPolicy,
            ),
            fixture.analyticsOptions(),
        ).block()!!
        empty.buckets.assert().hasSize(1)
        empty.buckets.single().metrics.assert().containsEntry(AnalyticsAlias("count"), NormalizedValue.Int64(0))
        empty.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("total"), NormalizedValue.Decimal(BigDecimal.ZERO))
        empty.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("average"), NormalizedValue.Null)
    }

    @Test
    fun `planned analytics should page stable dimension keys and preserve missing null baseline`() {
        val backend = requireNotNull(fixture.binding.prepareContribution(fixture.collection).block()!!.analyticsBackend)
        val mandatory = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
                fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
            ),
        )
        val filter = BackendEnforcedFilter(BackendPlannedCondition.All, mandatory)
        val grouping = BackendAnalyticsGrouping.By(
            listOf(
                BackendAnalyticsDimension(
                    AnalyticsAlias("status"),
                    fixture.status,
                    BackendAnalyticsMissingPolicy.EXCLUDE,
                ),
            ),
        )
        val metrics = listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")))

        val first = backend.analyze(
            fixture.analyticsPlan(grouping, metrics, filter, BackendAnalyticsPageWindow(1)),
            fixture.analyticsOptions(),
        ).block()!!
        first.buckets.single().keys.assert()
            .containsEntry(AnalyticsAlias("status"), NormalizedValue.Text("CREATED"))
        first.afterKey!!.assert().containsExactly(NormalizedValue.Text("CREATED"))

        val second = backend.analyze(
            fixture.analyticsPlan(
                grouping,
                metrics,
                filter,
                BackendAnalyticsPageWindow(1, first.afterKey),
            ),
            fixture.analyticsOptions(),
        ).block()!!
        second.buckets.single().keys.assert()
            .containsEntry(AnalyticsAlias("status"), NormalizedValue.Text("PAID"))
        second.afterKey.assert().isNull()

        val nullBucket = backend.analyze(
            fixture.analyticsPlan(
                BackendAnalyticsGrouping.By(
                    listOf(
                        BackendAnalyticsDimension(
                            AnalyticsAlias("note"),
                            fixture.note,
                            BackendAnalyticsMissingPolicy.AS_NULL_BUCKET,
                        ),
                    ),
                ),
                metrics,
                filter,
                BackendAnalyticsPageWindow(10),
            ),
            fixture.analyticsOptions(),
        ).block()!!
        nullBucket.buckets.assert().hasSize(1)
        nullBucket.buckets.single().keys.assert().containsEntry(AnalyticsAlias("note"), NormalizedValue.Null)
        nullBucket.buckets.single().metrics.assert()
            .containsEntry(AnalyticsAlias("count"), NormalizedValue.Int64(2))

        val excludedMissingAndNull = backend.analyze(
            fixture.analyticsPlan(
                BackendAnalyticsGrouping.By(
                    listOf(
                        BackendAnalyticsDimension(
                            AnalyticsAlias("note"),
                            fixture.note,
                            BackendAnalyticsMissingPolicy.EXCLUDE,
                        ),
                    ),
                ),
                metrics,
                filter,
                BackendAnalyticsPageWindow(10),
            ),
            fixture.analyticsOptions(),
        ).block()!!
        excludedMissingAndNull.buckets.assert().isEmpty()
        excludedMissingAndNull.afterKey.assert().isNull()
    }

    @Test
    fun `planned analytics should replay every high cardinality bucket without gaps or duplicates`() {
        val tenantId = "tenant-high-cardinality"
        val statuses = (0 until 257).map { index -> "HC-${index.toString().padStart(4, '0')}" }
        fixture.collection.insertMany(
            statuses.mapIndexed { index, status ->
                fixture.document(
                    id = "high-cardinality-$index",
                    tenantId = tenantId,
                    deleted = false,
                    status = status,
                    tags = emptyList(),
                    description = "high cardinality",
                    amount = index,
                )
            },
        ).toMono().block()
        val backend = requireNotNull(fixture.binding.prepareContribution(fixture.collection).block()!!.analyticsBackend)
        val grouping = BackendAnalyticsGrouping.By(
            listOf(
                BackendAnalyticsDimension(
                    dimensionAlias,
                    fixture.status,
                    BackendAnalyticsMissingPolicy.EXCLUDE,
                ),
            ),
        )
        val filter = BackendEnforcedFilter(
            fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text(tenantId)),
            fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
        )
        val observed = mutableListOf<String>()
        var afterKey: List<NormalizedValue>? = null
        var pages = 0
        do {
            val page = backend.analyze(
                fixture.analyticsPlan(
                    grouping,
                    listOf(BackendAnalyticsMetric.DocumentCount(countAlias)),
                    filter,
                    BackendAnalyticsPageWindow(31, afterKey),
                ),
                fixture.analyticsOptions().copy(maxReturnedBuckets = 31),
            ).block()!!
            observed += page.buckets.map { bucket ->
                (bucket.keys.getValue(dimensionAlias) as NormalizedValue.Text).value
            }
            afterKey = page.afterKey
            pages++
            check(pages <= 10) { "High-cardinality cursor did not terminate within the expected page bound." }
        } while (afterKey != null)

        observed.assert().containsExactly(*statuses.toTypedArray())
        observed.toSet().assert().hasSize(statuses.size)
    }

    @Test
    fun `planned analytics eventual cursor should observe a later concurrent bucket without claiming snapshot`() {
        val tenantId = "tenant-eventual"
        fixture.collection.insertMany(
            listOf(
                fixture.document("eventual-a", tenantId, false, "A", emptyList(), "eventual", 1),
                fixture.document("eventual-c", tenantId, false, "C", emptyList(), "eventual", 1),
            ),
        ).toMono().block()
        val backend = requireNotNull(fixture.binding.prepareContribution(fixture.collection).block()!!.analyticsBackend)
        val grouping = BackendAnalyticsGrouping.By(
            listOf(BackendAnalyticsDimension(dimensionAlias, fixture.status, BackendAnalyticsMissingPolicy.EXCLUDE)),
        )
        val filter = BackendEnforcedFilter(
            fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text(tenantId)),
            fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
        )
        val metrics = listOf(BackendAnalyticsMetric.DocumentCount(countAlias))

        val first = backend.analyze(
            fixture.analyticsPlan(grouping, metrics, filter, BackendAnalyticsPageWindow(1)),
            fixture.analyticsOptions(),
        ).block()!!
        first.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
        first.buckets.single().keys.getValue(dimensionAlias).assert().isEqualTo(NormalizedValue.Text("A"))

        fixture.collection.insertOne(
            fixture.document("eventual-b", tenantId, false, "B", emptyList(), "eventual", 1),
        ).toMono().block()

        val second = backend.analyze(
            fixture.analyticsPlan(grouping, metrics, filter, BackendAnalyticsPageWindow(1, first.afterKey)),
            fixture.analyticsOptions(),
        ).block()!!
        second.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
        second.buckets.single().keys.getValue(dimensionAlias).assert().isEqualTo(NormalizedValue.Text("B"))
    }

    @Test
    fun `planned analytics should reject real Decimal128 aggregation overflow`() {
        val tenantId = "tenant-overflow"
        val maximum = Decimal128.parse("9.999999999999999999999999999999999E+6144")
        fixture.collection.insertMany(
            listOf(
                fixture.document("overflow-1", tenantId, false, "OVERFLOW", emptyList(), "overflow", maximum),
                fixture.document("overflow-2", tenantId, false, "OVERFLOW", emptyList(), "overflow", maximum),
            ),
        ).toMono().block()
        val backend = requireNotNull(fixture.binding.prepareContribution(fixture.collection).block()!!.analyticsBackend)
        val filter = BackendEnforcedFilter(
            fixture.predicate(fixture.tenant, PredicateOperator.EQ, NormalizedValue.Text(tenantId)),
            fixture.predicate(fixture.deleted, PredicateOperator.IS_FALSE),
        )
        val policy = fixture.numericPolicy.copy(scale = 0)

        assertThrownBy<QueryBackendException> {
            backend.analyze(
                fixture.analyticsPlan(
                    BackendAnalyticsGrouping.Global,
                    listOf(BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), fixture.amount)),
                    filter,
                    numericPolicy = policy,
                ),
                fixture.analyticsOptions(),
            ).block()
        }.satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.MAPPING_FAILURE) },
        )
    }

    @Test
    fun `planned page explain should use the declared tenant deletion identity index`() {
        val explainFixture = Fixture(MongoNamespace(mongo.databaseName, "order_snapshot_explain"))
        explainFixture.collection.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending(MessageRecords.TENANT_ID),
                Indexes.ascending(StateAggregateRecords.DELETED),
                Indexes.ascending(Documents.ID_FIELD),
            ),
            IndexOptions().name(PAGE_INDEX),
        ).toMono().block()
        explainFixture.collection.insertMany(
            (0 until 2_000).map { index ->
                explainFixture.document(
                    id = "order-$index",
                    tenantId = if (index % 10 == 0) "tenant-1" else "tenant-other",
                    deleted = false,
                    status = "PAID",
                    tags = emptyList(),
                    description = "order",
                    amount = index,
                )
            },
        ).toMono().block()
        val mandatory = BackendPlannedCondition.Junction(
            JunctionOperator.AND,
            listOf(
                explainFixture.predicate(
                    explainFixture.tenant,
                    PredicateOperator.EQ,
                    NormalizedValue.Text("tenant-1"),
                ),
                explainFixture.predicate(explainFixture.deleted, PredicateOperator.IS_FALSE),
            ),
        )
        val plan = explainFixture.pagePlan(
            BackendEnforcedFilter(BackendPlannedCondition.All, mandatory),
            offset = 20,
            size = 10,
        )
        val query = MongoRecordQueryCompiler(explainFixture.binding).compile(plan)

        val explain = Mono.from(
            explainFixture.collection.aggregate(query.pagePipeline())
                .collation(Collation.builder().locale("simple").build())
                .allowDiskUse(false)
                .explain(ExplainVerbosity.EXECUTION_STATS),
        ).block()!!
        val rendered = explain.toJson()

        rendered.assert().contains("IXSCAN", "executionStats")
        rendered.contains("COLLSCAN").assert().isFalse()
    }

    @Test
    fun `planned high cardinality analytics explain should use the declared tenant deletion group index`() {
        val explainFixture = Fixture(MongoNamespace(mongo.databaseName, "order_snapshot_analytics_explain"))
        explainFixture.collection.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending(MessageRecords.TENANT_ID),
                Indexes.ascending(StateAggregateRecords.DELETED),
                Indexes.ascending("state.status"),
            ),
            IndexOptions().name(ANALYTICS_INDEX),
        ).toMono().block()
        explainFixture.collection.insertMany(
            (0 until 2_000).map { index ->
                explainFixture.document(
                    id = "analytics-order-$index",
                    tenantId = if (index % 10 == 0) "tenant-analytics" else "tenant-other",
                    deleted = false,
                    status = "STATUS-${index.toString().padStart(4, '0')}",
                    tags = emptyList(),
                    description = "analytics explain",
                    amount = index,
                )
            },
        ).toMono().block()
        val filter = BackendEnforcedFilter(
            explainFixture.predicate(
                explainFixture.tenant,
                PredicateOperator.EQ,
                NormalizedValue.Text("tenant-analytics"),
            ),
            explainFixture.predicate(explainFixture.deleted, PredicateOperator.IS_FALSE),
        )
        val plan = explainFixture.analyticsPlan(
            BackendAnalyticsGrouping.By(
                listOf(
                    BackendAnalyticsDimension(
                        AnalyticsAlias("status"),
                        explainFixture.status,
                        BackendAnalyticsMissingPolicy.EXCLUDE,
                    ),
                ),
            ),
            listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
            filter,
            BackendAnalyticsPageWindow(100),
        )
        val query = MongoAnalyticsQueryCompiler(explainFixture.binding.prepared).compile(plan)

        val explain = Mono.from(
            explainFixture.collection.aggregate(query.pipeline)
                .collation(Collation.builder().locale("simple").build())
                .allowDiskUse(true)
                .explain(ExplainVerbosity.EXECUTION_STATS),
        ).block()!!
        val rendered = explain.toJson()

        rendered.assert().contains("IXSCAN", ANALYTICS_INDEX, "executionStats")
        rendered.contains("COLLSCAN").assert().isFalse()
    }

    @Test
    fun `gateway shadow should compare legacy and planned Mongo against the same collection`() {
        val contribution = fixture.binding.prepareContribution(fixture.collection).block()!!
        val observation = arrayOfNulls<QueryShadowObservation>(1)
        val observed = CountDownLatch(1)
        val raw = LegacyMongoCountQueryService(fixture.target.namedAggregate, fixture.collection)
        val gateway = QueryGatewayRuntime.create(
            namedAggregates = listOf(fixture.target.namedAggregate),
            backendComposition = QueryBackendComposition(
                listOf(contribution),
                mapOf(fixture.target to contribution.backendId),
            ),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("integration-test", "mongo-shadow"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(fixture.target, QueryOperation.COUNT) to
                        QueryExecutionProfile(QueryExecutionMode.SHADOW, QueryValidationMode.STRICT),
                ),
            ),
            shadowObserver = QueryShadowObserver { current ->
                observation[0] = current
                observed.countDown()
            },
            runtimeHealthObserver = QueryRuntimeHealthObserver { },
        ).gateway

        gateway.count(QueryCall(fixture.target, QueryPurpose("mongo-shadow-test")), Condition.ALL)
            .block().assert().isEqualTo(3)

        observed.await(5, TimeUnit.SECONDS).assert().isTrue()
        observation.single()!!.outcome.assert().isEqualTo(QueryShadowOutcome.MATCH)
    }

    @Test
    fun `gateway planned page should execute one exact Mongo facet`() {
        val contribution = fixture.binding.prepareContribution(fixture.collection).block()!!
        val raw = LegacyMongoCountQueryService(fixture.target.namedAggregate, fixture.collection)
        val gateway = QueryGatewayRuntime.create(
            namedAggregates = listOf(fixture.target.namedAggregate),
            backendComposition = QueryBackendComposition(
                listOf(contribution),
                mapOf(fixture.target to contribution.backendId),
            ),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("integration-test", "mongo-planned-page"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(fixture.target, QueryOperation.PAGE) to
                        QueryExecutionProfile(QueryExecutionMode.PLANNED, QueryValidationMode.STRICT),
                ),
            ),
        ).gateway

        val page = gateway.page(
            QueryCall(fixture.target, QueryPurpose("mongo-planned-page-test")),
            PagedQuery(Condition.ALL, pagination = Pagination(2, 1)),
        ).block()!!

        page.total.assert().isEqualTo(3)
        page.list.assert().hasSize(1)
        page.list.single()[MessageRecords.AGGREGATE_ID].assert().isEqualTo("order-2")
    }

    @Test
    fun `gateway Mongo rehearsal should shadow cut over and roll back against one collection`() {
        val contribution = fixture.binding.prepareContribution(fixture.collection).block()!!
        val raw = LegacyMongoCountQueryService(fixture.target.namedAggregate, fixture.collection)
        val observation = arrayOfNulls<QueryShadowObservation>(1)
        val observed = CountDownLatch(1)
        val call = QueryCall(fixture.target, QueryPurpose("mongo-rollout-rehearsal"))

        fun gateway(
            mode: QueryExecutionMode,
            shadowObserver: QueryShadowObserver = QueryShadowObserver.NONE,
        ) = QueryGatewayRuntime.create(
            namedAggregates = listOf(fixture.target.namedAggregate),
            backendComposition = QueryBackendComposition(
                listOf(contribution),
                mapOf(fixture.target to contribution.backendId),
            ),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("integration-test", "mongo-rollout-rehearsal"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(fixture.target, QueryOperation.COUNT) to
                        QueryExecutionProfile(mode, QueryValidationMode.STRICT),
                ),
            ),
            shadowObserver = shadowObserver,
            runtimeHealthObserver = QueryRuntimeHealthObserver { },
        ).gateway

        gateway(
            QueryExecutionMode.SHADOW,
            QueryShadowObserver { current ->
                observation[0] = current
                observed.countDown()
            },
        ).count(call, Condition.ALL).block().assert().isEqualTo(3)
        observed.await(5, TimeUnit.SECONDS).assert().isTrue()
        observation.single()!!.outcome.assert().isEqualTo(QueryShadowOutcome.MATCH)
        raw.countInvocations.get().assert().isEqualTo(1)

        gateway(QueryExecutionMode.PLANNED)
            .count(call, Condition.ALL).block().assert().isEqualTo(3)
        raw.countInvocations.get().assert().isEqualTo(1)

        gateway(QueryExecutionMode.LEGACY)
            .count(call, Condition.ALL).block().assert().isEqualTo(3)
        raw.countInvocations.get().assert().isEqualTo(2)
    }

    private inner class Fixture(val namespace: MongoNamespace) {
        val collection = mongo.database().getCollection(namespace.collectionName)
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
        val deleted = QueryFieldId.System(SystemFieldKind.DELETED)
        val state = QueryFieldId.Path(listOf("state"))
        val status = QueryFieldId.Path(listOf("state", "status"))
        val tags = QueryFieldId.Path(listOf("state", "tags"))
        val note = QueryFieldId.Path(listOf("note"))
        val amount = QueryFieldId.Path(listOf("amount"))
        private val description = QueryFieldId.Path(listOf("description"))
        val searchScope = SearchScopeId("document-text")
        val target = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.SNAPSHOT,
        )
        private val schema = QueryDocumentSchema(
            target,
            listOf(
                field(identity, LogicalFieldType.Text, setOf(PredicateOperator.EQ), EXACT_SORT_PROJECT),
                field(tenant, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
                field(deleted, LogicalFieldType.Boolean, setOf(PredicateOperator.IS_FALSE), setOf(FieldCapability.EXACT)),
                field(state, LogicalFieldType.Object),
                field(
                    status,
                    LogicalFieldType.Text,
                    setOf(PredicateOperator.EQ),
                    setOf(
                        FieldCapability.EXACT,
                        FieldCapability.PROJECTABLE,
                        FieldCapability.AGGREGATABLE,
                    ),
                ),
                field(
                    tags,
                    LogicalFieldType.Array(
                        LogicalFieldType.Text,
                        Nullability.NON_NULL,
                        EmptyArraySemantics.DISTINCT,
                    ),
                    setOf(PredicateOperator.ALL_IN),
                    setOf(FieldCapability.EXACT),
                ),
                field(description, LogicalFieldType.Text, capabilities = setOf(FieldCapability.FULL_TEXT)),
                field(
                    note,
                    LogicalFieldType.Text,
                    setOf(PredicateOperator.IS_NULL, PredicateOperator.NOT_NULL),
                    setOf(FieldCapability.PRESENCE, FieldCapability.AGGREGATABLE),
                ),
                field(
                    amount,
                    LogicalFieldType.Decimal,
                    setOf(PredicateOperator.EQ),
                    setOf(FieldCapability.EXACT, FieldCapability.AGGREGATABLE),
                ),
            ),
            listOf(QuerySearchScopeDefinition(searchScope, null, listOf(description), listOf(description))),
        )
        val binding = MongoSnapshotQueryBinding(
            schema,
            namespace,
            linkedMapOf(
                identity to MongoFieldBinding(Documents.ID_FIELD, EXACT_SORT_PROJECT),
                tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                deleted to MongoFieldBinding(StateAggregateRecords.DELETED, setOf(FieldCapability.EXACT)),
                state to MongoFieldBinding("state", emptySet()),
                status to MongoFieldBinding(
                    "state.status",
                    setOf(
                        FieldCapability.EXACT,
                        FieldCapability.PROJECTABLE,
                        FieldCapability.AGGREGATABLE,
                    ),
                ),
                tags to MongoFieldBinding("state.tags", setOf(FieldCapability.EXACT)),
                description to MongoFieldBinding("description", setOf(FieldCapability.FULL_TEXT)),
                note to MongoFieldBinding(
                    "note",
                    setOf(FieldCapability.PRESENCE, FieldCapability.AGGREGATABLE),
                ),
                amount to MongoFieldBinding(
                    "amount",
                    setOf(FieldCapability.EXACT, FieldCapability.AGGREGATABLE),
                    MongoValueEncoding.DECIMAL128,
                ),
            ),
            textSearch = MongoTextSearchBinding(searchScope, TEXT_INDEX),
        )

        fun countPlan(
            filter: BackendEnforcedFilter,
            tier: SemanticTier = SemanticTier.PORTABLE,
        ) = BackendCountQueryPlan(
            target,
            schema.contractId,
            filter,
            BackendRequiredCapabilities(),
            tier,
            PlanFingerprint("1".repeat(64)),
        )

        fun streamPlan(filter: BackendEnforcedFilter) = BackendStreamQueryPlan(
            target,
            schema.contractId,
            filter,
            RecordResultShape.DYNAMIC,
            BackendProjection.Include(listOf(status)),
            listOf(BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER)),
            10,
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("2".repeat(64)),
        )

        fun pagePlan(filter: BackendEnforcedFilter, offset: Long, size: Int) = BackendPageQueryPlan(
            target,
            schema.contractId,
            filter,
            RecordResultShape.DYNAMIC,
            BackendProjection.Include(listOf(status)),
            listOf(BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER)),
            BackendPageWindow(offset, size),
            BackendTotalMode.EXACT,
            BackendRequiredConsistency.SAME_INPUT,
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("4".repeat(64)),
        )

        val numericPolicy = BackendAnalyticsNumericPolicy(
            BackendAnalyticsNumericPromotion.DECIMAL128,
            34,
            4,
            RoundingMode.HALF_UP,
            BackendAnalyticsOverflowPolicy.REJECT,
        )

        fun analyticsPlan(
            grouping: BackendAnalyticsGrouping,
            metrics: List<BackendAnalyticsMetric>,
            filter: BackendEnforcedFilter,
            window: BackendAnalyticsPageWindow = BackendAnalyticsPageWindow(1),
            numericPolicy: BackendAnalyticsNumericPolicy? = null,
        ) = BackendAnalyticsQueryPlan(
            target,
            schema.contractId,
            filter,
            grouping,
            metrics,
            BackendAnalyticsCondition.All,
            when (grouping) {
                BackendAnalyticsGrouping.Global -> BackendAnalyticsBucketOrder.Global
                is BackendAnalyticsGrouping.By -> BackendAnalyticsBucketOrder.DimensionKeyAscending(
                    BackendAnalyticsNullPlacement.FIRST,
                    BackendAnalyticsTextCollation.BINARY,
                )
            },
            window,
            numericPolicy,
            BackendAnalyticsConsistency.EVENTUAL,
            BackendAnalyticsCompleteness.EXACT,
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("7".repeat(64)),
        )

        fun analyticsOptions() = QueryBackendExecutionOptions(
            deadline = Instant.now().plusSeconds(300),
            maxReturnedRecords = null,
            maxReturnedBuckets = 10,
            allowDiskUse = true,
        )

        fun predicate(
            field: QueryFieldId,
            operator: PredicateOperator,
            value: NormalizedValue? = null,
        ) = BackendPlannedCondition.Predicate(field, operator, value)

        fun document(
            id: String,
            tenantId: String,
            deleted: Boolean,
            status: String,
            tags: List<String>,
            description: String,
            amount: Any,
        ): Document = Document(Documents.ID_FIELD, id)
            .append(MessageRecords.TENANT_ID, tenantId)
            .append(StateAggregateRecords.DELETED, deleted)
            .append("state", Document("status", status).append("tags", tags))
            .append("description", description)
            .append("amount", amount)

        private fun field(
            id: QueryFieldId,
            type: LogicalFieldType,
            operators: Set<PredicateOperator> = emptySet(),
            capabilities: Set<FieldCapability> = emptySet(),
        ) = QueryFieldSchema(id, type, Presence.OPTIONAL, Nullability.NULLABLE, operators, capabilities)
    }

    private class LegacyMongoCountQueryService(
        override val namedAggregate: NamedAggregate,
        private val collection: com.mongodb.reactivestreams.client.MongoCollection<Document>,
    ) : SnapshotQueryService<Any> {
        override val name: String = "legacy-mongo-integration-test"
        val countInvocations = AtomicInteger()

        override fun single(singleQuery: ISingleQuery) = Mono.empty<me.ahoo.wow.api.query.MaterializedSnapshot<Any>>()

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.empty()

        override fun list(listQuery: IListQuery) = Flux.empty<me.ahoo.wow.api.query.MaterializedSnapshot<Any>>()

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.empty()

        override fun paged(pagedQuery: IPagedQuery) =
            Mono.just(PagedList.empty<me.ahoo.wow.api.query.MaterializedSnapshot<Any>>())

        override fun dynamicPaged(pagedQuery: IPagedQuery) = Mono.just(PagedList.empty<DynamicDocument>())

        override fun count(condition: Condition): Mono<Long> = Mono.defer {
            countInvocations.incrementAndGet()
            Mono.from(collection.countDocuments(SnapshotConditionConverter.convert(condition)))
        }
    }

    private companion object {
        const val ANALYTICS_INDEX = "tenant_deleted_status"
        const val PAGE_INDEX = "tenant_deleted_identity"
        const val TEXT_INDEX = "description_text"
        val OPTIONS = QueryBackendExecutionOptions(null, null)
        val EXACT_SORT_PROJECT = setOf(
            FieldCapability.EXACT,
            FieldCapability.SORTABLE,
            FieldCapability.PROJECTABLE,
        )
    }
}
