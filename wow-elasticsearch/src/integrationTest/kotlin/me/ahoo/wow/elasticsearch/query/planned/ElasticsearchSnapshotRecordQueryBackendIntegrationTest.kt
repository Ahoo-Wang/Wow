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

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.AnalyticsAlias
import me.ahoo.wow.query.backend.AnalyticsQueryBackend
import me.ahoo.wow.query.backend.AnalyticsQueryCursorLifecycle
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
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageConsistency
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendRequiredConsistency
import me.ahoo.wow.query.backend.BackendSingleQueryPlan
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendStreamQueryPlan
import me.ahoo.wow.query.backend.BackendTotalMode
import me.ahoo.wow.query.backend.BackendTotalRelation
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.JunctionOperator
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.RecordQueryBackend
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.QuerySearchScopeDefinition
import me.ahoo.wow.query.backend.RecordResultShape
import me.ahoo.wow.query.backend.SearchScopeId
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryAuthority
import me.ahoo.wow.query.gateway.QueryAuthorityResolver
import me.ahoo.wow.query.gateway.QueryCall
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
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.ExactNumericAnalyticsExpectation
import me.ahoo.wow.tck.query.PlannedAnalyticsQueryBackendSpec
import me.ahoo.wow.tck.query.PlannedRecordQueryBackendSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ElasticsearchSnapshotRecordQueryBackendIntegrationTest :
    PlannedAnalyticsQueryBackendSpec,
    PlannedRecordQueryBackendSpec {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    private lateinit var client: ReactiveElasticsearchClient
    private lateinit var indexName: String
    private lateinit var binding: ElasticsearchSnapshotQueryBinding

    override val analyticsBackend: AnalyticsQueryBackend
        get() = binding.prepareContribution(client).block()!!.analyticsBackend!!
    override val analyticsOptions: QueryBackendExecutionOptions
        get() = OPTIONS.copy(maxReturnedBuckets = 10)
    override val expectedGlobalCount: Long = 2
    override val expectedUnrestrictedGlobalCount: Long = 4
    override val expectedFirstKey: NormalizedValue = NormalizedValue.Text("alice")
    override val expectedSecondKey: NormalizedValue = NormalizedValue.Text("carol")
    override val expectedNullBucketCount: Long = 2
    override val dimensionAlias: AnalyticsAlias = NAME_ALIAS
    override val countAlias: AnalyticsAlias = COUNT_ALIAS
    override val exactNumericAnalyticsExpectation: ExactNumericAnalyticsExpectation =
        ExactNumericAnalyticsExpectation.Unsupported
    override val recordBackend: RecordQueryBackend
        get() = binding.prepareContribution(client).block()!!.backend
    override val recordOptions: QueryBackendExecutionOptions
        get() = OPTIONS
    override val expectedRecordIdentities: List<String> = listOf("order-1", "order-4")

    override fun globalCountPlan(): BackendAnalyticsQueryPlan = globalAnalyticsPlan()

    override fun unrestrictedGlobalCountPlan(): BackendAnalyticsQueryPlan = globalAnalyticsPlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
    )

    override fun groupedCountPlan(afterKey: List<NormalizedValue>?, limit: Int): BackendAnalyticsQueryPlan =
        groupedAnalyticsPlan(afterKey, limit)

    override fun nullBucketCountPlan(): BackendAnalyticsQueryPlan = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
        BackendAnalyticsGrouping.By(
            listOf(BackendAnalyticsDimension(NOTE_ALIAS, note, BackendAnalyticsMissingPolicy.AS_NULL_BUCKET)),
        ),
        listOf(BackendAnalyticsMetric.DocumentCount(COUNT_ALIAS)),
        BackendAnalyticsCondition.All,
        BackendAnalyticsBucketOrder.DimensionKeyAscending(
            BackendAnalyticsNullPlacement.FIRST,
            BackendAnalyticsTextCollation.BINARY,
        ),
        BackendAnalyticsPageWindow(10),
        null,
        BackendAnalyticsConsistency.EVENTUAL,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("7".repeat(64)),
    )

    override fun exactNumericMetricPlan(): BackendAnalyticsQueryPlan = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
        BackendAnalyticsGrouping.Global,
        listOf(
            BackendAnalyticsMetric.Min(AnalyticsAlias("minimum"), amount),
            BackendAnalyticsMetric.Max(AnalyticsAlias("maximum"), amount),
            BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), amount),
            BackendAnalyticsMetric.Average(AnalyticsAlias("average"), amount),
        ),
        BackendAnalyticsCondition.All,
        BackendAnalyticsBucketOrder.Global,
        BackendAnalyticsPageWindow(1),
        NUMERIC_POLICY,
        BackendAnalyticsConsistency.EVENTUAL,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("8".repeat(64)),
    )

    override fun portableCountPlan(): BackendCountQueryPlan = countPlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
    )

    override fun portableStreamPlan(): BackendStreamQueryPlan = streamPlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
    )

    override fun portableSecondPagePlan(): BackendPageQueryPlan = pagePlan(
        BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
    )

    @BeforeEach
    fun setup() {
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        indexName = "wow.sales.order.snapshot"
        binding = createBinding(indexName)
        if (client.indices().exists { exists -> exists.index(indexName) }.block()!!.value()) {
            client.indices().delete { delete -> delete.index(indexName) }.block()
        }
        client.indices().create { create ->
            create.index(indexName).mappings { mapping ->
                mapping.meta(MAPPING_VERSION_META, JsonData.of(MAPPING_VERSION))
                    .meta(DOCUMENT_KIND_META, JsonData.of(QueryDocumentKind.SNAPSHOT.name))
                    .meta(SCHEMA_CONTRACT_META, JsonData.of(schema.contractId.value))
                    .meta(CAPABILITY_DIGEST_META, JsonData.of(binding.prepared.capabilityDigest))
                    .properties(MessageRecords.AGGREGATE_ID, keyword())
                    .properties(MessageRecords.TENANT_ID, keyword())
                    .properties(StateAggregateRecords.DELETED, Property.of { it.boolean_ { boolean -> boolean } })
                    .properties(
                        "state",
                        Property.of { state ->
                            state.`object` { objectField ->
                                objectField
                                    .properties(
                                        "name",
                                        Property.of { text ->
                                            text.text { definition ->
                                                definition.analyzer("standard").searchAnalyzer("standard")
                                                    .fields("exact", keyword())
                                            }
                                        },
                                    )
                                    .properties("note", keyword())
                                    .properties("notePresent", Property.of { it.boolean_ { boolean -> boolean } })
                                    .properties("noteGroup", keyword())
                                    .properties("amount", Property.of { number -> number.long_ { it } })
                            }
                        },
                    )
            }
        }.block()
        documents().forEach { (id, document) ->
            client.index<Map<String, Any?>> { index ->
                index.index(indexName).id(id).document(document)
            }.block()
        }
        client.indices().refresh { refresh -> refresh.index(indexName) }.block()
    }

    @Test
    fun `planned Elasticsearch should enforce mandatory filter and return complete record shapes`() {
        val backend = binding.prepareContribution(client).block()!!.backend
        val filter = BackendEnforcedFilter(
            predicate(name, PredicateOperator.EQ, NormalizedValue.Text("alice")),
            mandatory(),
        )
        val single = backend.single(singlePlan(filter), OPTIONS).block()!!

        single.identity.assert().isEqualTo("order-1")
        single.document.values.keys.assert().containsExactly("state")
        val state = single.document.values["state"] as NormalizedValue.ObjectValue
        state.values.assert().containsEntry("name", NormalizedValue.Text("alice"))

        val all = BackendEnforcedFilter(BackendPlannedCondition.All, mandatory())
        val stream = backend.stream(streamPlan(all), OPTIONS).collectList().block()!!
        stream.map { record -> record.identity }.assert().containsExactly("order-1", "order-4")

        val page = backend.page(pagePlan(all), OPTIONS).block()!!
        page.total.assert().isEqualTo(2)
        page.totalRelation.assert().isEqualTo(BackendTotalRelation.EXACT)
        page.consistency.assert().isEqualTo(BackendPageConsistency.SAME_INPUT)
        page.records.single().identity.assert().isEqualTo("order-4")

        backend.count(countPlan(all), OPTIONS).block().assert().isEqualTo(2)
    }

    @Test
    fun `gateway Elasticsearch rehearsal should shadow cut over and roll back against one index`() {
        val contribution = binding.prepareContribution(client).block()!!
        val raw = LegacyElasticsearchCountQueryService(target.namedAggregate, client, indexName)
        val observation = arrayOfNulls<QueryShadowObservation>(1)
        val observed = CountDownLatch(1)
        val call = QueryCall(target, QueryPurpose("elasticsearch-rollout-rehearsal"))

        fun gateway(
            mode: QueryExecutionMode,
            shadowObserver: QueryShadowObserver = QueryShadowObserver.NONE,
        ) = QueryGatewayRuntime.create(
            namedAggregates = listOf(target.namedAggregate),
            backendComposition = QueryBackendComposition(
                listOf(contribution),
                mapOf(target to contribution.backendId),
            ),
            rawServiceSource = object : QueryRawServiceSource {
                override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

                override fun eventStream(namedAggregate: NamedAggregate) =
                    NoOpEventStreamQueryServiceFactory.create(namedAggregate)
            },
            dialectResolver = QueryLegacyDialectResolver {
                QueryLegacyDialect(QueryElementPathMode.ROOT_QUALIFIED, QueryMatchScopeMode.FIELD)
            },
            authorityResolver = QueryAuthorityResolver {
                Mono.just(QueryAuthority.System("integration-test", "elasticsearch-rollout-rehearsal"))
            },
            executionProfiles = QueryExecutionProfiles(
                operationProfiles = mapOf(
                    QueryOperationProfileKey(target, QueryOperation.COUNT) to
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

    @Test
    fun `planned Elasticsearch should preserve null missing literal and search bindings`() {
        val backend = binding.prepareContribution(client).block()!!.backend
        val nullOrMissing = BackendEnforcedFilter(
            predicate(note, PredicateOperator.EQ, NormalizedValue.Null),
            mandatory(),
        )
        backend.count(countPlan(nullOrMissing), OPTIONS).block().assert().isEqualTo(2)
        val explicitOrNonNull = BackendEnforcedFilter(
            predicate(note, PredicateOperator.EXISTS, NormalizedValue.BooleanValue(true)),
            mandatory(),
        )
        backend.count(countPlan(explicitOrNonNull), OPTIONS).block().assert().isEqualTo(1)
        val missingOnly = BackendEnforcedFilter(
            predicate(note, PredicateOperator.EXISTS, NormalizedValue.BooleanValue(false)),
            mandatory(),
        )
        backend.count(countPlan(missingOnly), OPTIONS).block().assert().isEqualTo(1)

        val literal = BackendEnforcedFilter(
            predicate(name, PredicateOperator.CONTAINS, NormalizedValue.Text("li")),
            mandatory(),
        )
        backend.count(countPlan(literal), OPTIONS).block().assert().isEqualTo(1)

        val search = BackendEnforcedFilter(
            BackendPlannedCondition.Search(scope, "alice"),
            mandatory(),
        )
        backend.count(countPlan(search, SemanticTier.SEARCH), OPTIONS).block().assert().isEqualTo(1)
    }

    @Test
    fun `planned Elasticsearch page should cross the ten thousand window with PIT search after`() {
        (0..10_000).chunked(500).forEach { batch ->
            val response = client.bulk { bulk ->
                bulk.operations(
                    batch.map { index ->
                        val id = "bulk-${index.toString().padStart(5, '0')}"
                        BulkOperation.of { operation ->
                            operation.index { request ->
                                request.index(indexName)
                                    .id(id)
                                    .document(document(id, "tenant-1", false, "bulk", MISSING, index.toLong()))
                            }
                        }
                    },
                )
            }.block()!!
            response.errors().assert().isFalse()
        }
        client.indices().refresh { refresh -> refresh.index(indexName) }.block()
        val backend = binding.prepareContribution(client).block()!!.backend
        val options = OPTIONS.copy(maxPageWindow = 10_001, maxCursorPages = 11)

        val page = backend.page(
            pagePlan(BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()), 10_000, 1),
            options,
        ).block()!!

        page.records.single().identity.assert().isEqualTo("bulk-10000")
        page.total.assert().isEqualTo(10_003)
    }

    @Test
    fun `planned Elasticsearch page should fail incomplete when the real PIT expires`() {
        val backend = ElasticsearchSnapshotRecordQueryBackend(ExpiringPitClient(client), binding.prepared)
        val error = runCatching {
            backend.page(
                pagePlan(BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()), 0, 1),
                OPTIONS,
            ).block()
        }.exceptionOrNull() as QueryBackendException

        error.kind.assert().isEqualTo(QueryBackendFailureKind.INCOMPLETE_RESULT)
    }

    @Test
    fun `planned Elasticsearch page cancellation should close the real PIT`() {
        val cancellingClient = NeverCompletingPitClient(client)
        val backend = ElasticsearchSnapshotRecordQueryBackend(cancellingClient, binding.prepared)

        StepVerifier.create(
            backend.page(
                pagePlan(BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()), 0, 1),
                OPTIONS,
            ),
        ).thenAwait(Duration.ofMillis(100))
            .thenCancel()
            .verify()

        cancellingClient.closedPit.asMono().block(Duration.ofSeconds(5)).assert().isNotBlank()
    }

    @Test
    fun `planned Elasticsearch page should classify a closed real transport as unavailable`() {
        val unavailableClient = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        unavailableClient.close()
        val backend = ElasticsearchSnapshotRecordQueryBackend(unavailableClient, binding.prepared)

        val error = runCatching {
            backend.page(
                pagePlan(BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()), 0, 1),
                OPTIONS,
            ).block()
        }.exceptionOrNull() as QueryBackendException

        error.kind.assert().isEqualTo(QueryBackendFailureKind.UNAVAILABLE)
    }

    @Test
    fun `planned Elasticsearch composite analytics should preserve mandatory filter order and response cursor`() {
        val analytics = binding.prepareContribution(client).block()!!.analyticsBackend!!
        val first = analytics.analyze(groupedAnalyticsPlan(), OPTIONS.copy(maxReturnedBuckets = 1)).block()!!

        first.buckets.single().keys[NAME_ALIAS].assert().isEqualTo(NormalizedValue.Text("alice"))
        first.buckets.single().metrics[COUNT_ALIAS].assert().isEqualTo(NormalizedValue.Int64(1))
        first.afterKey!!.assert().containsExactly(NormalizedValue.Text("alice"))

        val second = analytics.analyze(
            groupedAnalyticsPlan(first.afterKey),
            OPTIONS.copy(maxReturnedBuckets = 1),
        ).block()!!
        second.buckets.single().keys[NAME_ALIAS].assert().isEqualTo(NormalizedValue.Text("carol"))
        second.buckets.single().metrics[COUNT_ALIAS].assert().isEqualTo(NormalizedValue.Int64(1))

        val global = analytics.analyze(globalAnalyticsPlan(), OPTIONS.copy(maxReturnedBuckets = 1)).block()!!
        global.buckets.single().metrics[COUNT_ALIAS].assert().isEqualTo(NormalizedValue.Int64(2))
    }

    @Test
    fun `planned Elasticsearch analytics should replay every high cardinality bucket without gaps or duplicates`() {
        val generated = (0 until 257).map { index -> "key-${index.toString().padStart(3, '0')}" }
        val response = client.bulk { bulk ->
            bulk.operations(
                generated.mapIndexed { index, value ->
                    val id = "analytics-$value"
                    BulkOperation.of { operation ->
                        operation.index { request ->
                            request.index(indexName)
                                .id(id)
                                .document(document(id, "tenant-1", false, value, MISSING, index.toLong()))
                        }
                    }
                },
            )
        }.block()!!
        response.errors().assert().isFalse()
        client.indices().refresh { refresh -> refresh.index(indexName) }.block()
        val analytics = binding.prepareContribution(client).block()!!.analyticsBackend!!
        val actual = mutableListOf<String>()
        var afterKey: List<NormalizedValue>? = null
        var pageCount = 0

        do {
            val page = analytics.analyze(
                groupedAnalyticsPlan(afterKey, HIGH_CARDINALITY_PAGE_SIZE),
                OPTIONS.copy(maxReturnedBuckets = HIGH_CARDINALITY_PAGE_SIZE),
            ).block()!!
            page.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
            page.completeness.assert().isEqualTo(BackendAnalyticsCompleteness.EXACT)
            actual += page.buckets.map { bucket ->
                (bucket.keys.getValue(NAME_ALIAS) as NormalizedValue.Text).value
            }
            afterKey = page.afterKey
            pageCount++
        } while (afterKey != null && pageCount < MAX_HIGH_CARDINALITY_PAGES)

        actual.assert().containsExactlyElementsOf(listOf("alice", "carol") + generated)
        actual.distinct().assert().hasSize(actual.size)
        afterKey.assert().isNull()
        pageCount.assert().isLessThanOrEqualTo(MAX_HIGH_CARDINALITY_PAGES)
    }

    @Test
    fun `planned Elasticsearch analytics eventual cursor should observe a later concurrent bucket`() {
        val analytics = binding.prepareContribution(client).block()!!.analyticsBackend!!
        val first = analytics.analyze(groupedAnalyticsPlan(), OPTIONS.copy(maxReturnedBuckets = 1)).block()!!
        first.buckets.single().keys.getValue(NAME_ALIAS).assert().isEqualTo(NormalizedValue.Text("alice"))

        val id = "order-concurrent"
        client.index<Map<String, Any?>> { index ->
            index.index(indexName)
                .id(id)
                .document(document(id, "tenant-1", false, "bob", MISSING, 5))
        }.block()
        client.indices().refresh { refresh -> refresh.index(indexName) }.block()

        val second = analytics.analyze(
            groupedAnalyticsPlan(first.afterKey),
            OPTIONS.copy(maxReturnedBuckets = 1),
        ).block()!!
        second.buckets.single().keys.getValue(NAME_ALIAS).assert().isEqualTo(NormalizedValue.Text("bob"))
        second.consistency.assert().isEqualTo(BackendAnalyticsConsistency.EVENTUAL)
        second.completeness.assert().isEqualTo(BackendAnalyticsCompleteness.EXACT)
    }

    @Test
    fun `planned Elasticsearch snapshot analytics should keep one PIT across continuation pages`() {
        val contribution = binding.prepareContribution(client).block()!!
        val analytics = contribution.analyticsBackend!!
        val first = analytics.analyze(
            groupedAnalyticsPlan(consistency = BackendAnalyticsConsistency.SNAPSHOT),
            OPTIONS.copy(maxReturnedBuckets = 1),
            null,
        ).block()!!
        first.buckets.single().keys.getValue(NAME_ALIAS).assert().isEqualTo(NormalizedValue.Text("alice"))
        first.cursorState.assert().isNotNull()

        val id = "order-snapshot-concurrent"
        client.index<Map<String, Any?>> { index ->
            index.index(indexName)
                .id(id)
                .document(document(id, "tenant-1", false, "bob", MISSING, 5))
        }.block()
        client.indices().refresh { refresh -> refresh.index(indexName) }.block()

        val second = analytics.analyze(
            groupedAnalyticsPlan(
                first.afterKey,
                consistency = BackendAnalyticsConsistency.SNAPSHOT,
            ),
            OPTIONS.copy(maxReturnedBuckets = 1),
            first.cursorState,
        ).block()!!
        second.buckets.single().keys.getValue(NAME_ALIAS).assert().isEqualTo(NormalizedValue.Text("carol"))
        second.consistency.assert().isEqualTo(BackendAnalyticsConsistency.SNAPSHOT)
        second.cursorState.assert().isNotNull()

        (analytics as AnalyticsQueryCursorLifecycle).close(second.cursorState!!).block()
    }

    @Test
    fun `planned Elasticsearch snapshot analytics should classify an expired PIT as incomplete`() {
        val contribution = binding.prepareContribution(client).block()!!
        val analytics = contribution.analyticsBackend!!
        val first = analytics.analyze(
            groupedAnalyticsPlan(consistency = BackendAnalyticsConsistency.SNAPSHOT),
            OPTIONS.copy(maxReturnedBuckets = 1),
            null,
        ).block()!!
        val state = first.cursorState!!
        (analytics as AnalyticsQueryCursorLifecycle).close(state).block()

        val error = runCatching {
            analytics.analyze(
                groupedAnalyticsPlan(
                    first.afterKey,
                    consistency = BackendAnalyticsConsistency.SNAPSHOT,
                ),
                OPTIONS.copy(maxReturnedBuckets = 1),
                state,
            ).block()
        }.exceptionOrNull() as QueryBackendException

        error.kind.assert().isEqualTo(QueryBackendFailureKind.INCOMPLETE_RESULT)
    }

    private fun singlePlan(filter: BackendEnforcedFilter) = BackendSingleQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.Include(listOf(name)),
        emptyList(),
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("1".repeat(64)),
    )

    private fun streamPlan(filter: BackendEnforcedFilter) = BackendStreamQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.All,
        stableSort(),
        10,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("2".repeat(64)),
    )

    private fun pagePlan(
        filter: BackendEnforcedFilter,
        offset: Long = 1,
        size: Int = 1,
    ) = BackendPageQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.All,
        stableSort(),
        BackendPageWindow(offset, size),
        BackendTotalMode.EXACT,
        BackendRequiredConsistency.SAME_INPUT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("3".repeat(64)),
    )

    private fun countPlan(
        filter: BackendEnforcedFilter,
        tier: SemanticTier = SemanticTier.PORTABLE,
    ) = BackendCountQueryPlan(
        target,
        schema.contractId,
        filter,
        BackendRequiredCapabilities(),
        tier,
        PlanFingerprint("4".repeat(64)),
    )

    private fun groupedAnalyticsPlan(
        afterKey: List<NormalizedValue>? = null,
        limit: Int = 1,
        consistency: BackendAnalyticsConsistency = BackendAnalyticsConsistency.EVENTUAL,
    ) = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
        BackendAnalyticsGrouping.By(
            listOf(BackendAnalyticsDimension(NAME_ALIAS, name, BackendAnalyticsMissingPolicy.EXCLUDE)),
        ),
        listOf(BackendAnalyticsMetric.DocumentCount(COUNT_ALIAS)),
        BackendAnalyticsCondition.All,
        BackendAnalyticsBucketOrder.DimensionKeyAscending(
            BackendAnalyticsNullPlacement.FIRST,
            BackendAnalyticsTextCollation.BINARY,
        ),
        BackendAnalyticsPageWindow(limit, afterKey),
        null,
        consistency,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("5".repeat(64)),
    )

    private fun globalAnalyticsPlan(
        filter: BackendEnforcedFilter = BackendEnforcedFilter(BackendPlannedCondition.All, mandatory()),
    ) = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        filter,
        BackendAnalyticsGrouping.Global,
        listOf(BackendAnalyticsMetric.DocumentCount(COUNT_ALIAS)),
        BackendAnalyticsCondition.All,
        BackendAnalyticsBucketOrder.Global,
        BackendAnalyticsPageWindow(1),
        null,
        BackendAnalyticsConsistency.EVENTUAL,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("6".repeat(64)),
    )

    private fun stableSort() = listOf(
        BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER),
    )

    private fun mandatory() = BackendPlannedCondition.Junction(
        JunctionOperator.AND,
        listOf(
            predicate(tenant, PredicateOperator.EQ, NormalizedValue.Text("tenant-1")),
            predicate(deleted, PredicateOperator.IS_FALSE),
        ),
    )

    private fun predicate(
        field: QueryFieldId,
        operator: PredicateOperator,
        value: NormalizedValue? = null,
    ) = BackendPlannedCondition.Predicate(field, operator, value)

    private fun documents(): Map<String, Map<String, Any?>> = linkedMapOf(
        "order-1" to document("order-1", "tenant-1", false, "alice", null, 1),
        "order-2" to document("order-2", "tenant-2", false, "alice", "visible", 2),
        "order-3" to document("order-3", "tenant-1", true, "alice", "deleted", 3),
        "order-4" to document("order-4", "tenant-1", false, "carol", MISSING, 4),
    )

    private fun document(
        id: String,
        tenantId: String,
        isDeleted: Boolean,
        currentName: String,
        currentNote: Any?,
        amount: Long,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        MessageRecords.AGGREGATE_ID to id,
        MessageRecords.TENANT_ID to tenantId,
        StateAggregateRecords.DELETED to isDeleted,
        "state" to linkedMapOf<String, Any?>("name" to currentName).also { state ->
            state["notePresent"] = currentNote !== MISSING
            if (currentNote !== MISSING) state["note"] = currentNote
            if (currentNote is String) state["noteGroup"] = currentNote
            state["amount"] = amount
        },
    )

    private fun createBinding(index: String) = ElasticsearchSnapshotQueryBinding(
        schema,
        index,
        MAPPING_VERSION,
        linkedMapOf(
            identity to ElasticsearchFieldBinding(
                MessageRecords.AGGREGATE_ID,
                EXACT_SORT_PROJECT,
                exactField = "_id",
                sortField = MessageRecords.AGGREGATE_ID,
                keywordReadiness = KEYWORD_READINESS,
            ),
            tenant to ElasticsearchFieldBinding(
                MessageRecords.TENANT_ID,
                setOf(FieldCapability.EXACT),
                exactField = MessageRecords.TENANT_ID,
                keywordReadiness = KEYWORD_READINESS,
            ),
            deleted to ElasticsearchFieldBinding(
                StateAggregateRecords.DELETED,
                setOf(FieldCapability.EXACT),
                exactField = StateAggregateRecords.DELETED,
            ),
            stateField to ElasticsearchFieldBinding("state", emptySet()),
            name to ElasticsearchFieldBinding(
                "state.name",
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.FULL_TEXT,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
                exactField = "state.name.exact",
                searchField = "state.name",
                searchAnalyzer = "standard",
                literalField = "state.name.exact",
                groupField = "state.name.exact",
                groupReadiness = GROUP_READINESS,
                keywordReadiness = KEYWORD_READINESS,
            ),
            note to ElasticsearchFieldBinding(
                "state.note",
                setOf(FieldCapability.EXACT, FieldCapability.PRESENCE, FieldCapability.AGGREGATABLE),
                exactField = "state.note",
                presenceField = "state.notePresent",
                groupField = "state.noteGroup",
                groupReadiness = GROUP_READINESS,
                keywordReadiness = KEYWORD_READINESS,
            ),
            amount to ElasticsearchFieldBinding(
                "state.amount",
                setOf(FieldCapability.AGGREGATABLE),
                groupField = "state.amount",
                groupReadiness = GROUP_READINESS,
            ),
        ),
        listOf(ElasticsearchSearchScopeBinding(scope, mapOf(name to "state.name"))),
    )

    private fun keyword(): Property = Property.of { property ->
        property.keyword { keyword -> keyword.ignoreAbove(128) }
    }

    private class ExpiringPitClient(delegate: ReactiveElasticsearchClient) :
        ReactiveElasticsearchClient(delegate._transport(), delegate._transportOptions()) {
        override fun <T : Any> search(
            request: SearchRequest,
            tDocumentClass: Class<T>,
        ): Mono<ResponseBody<T>> {
            val pitId = request.pit()?.id() ?: return super.search(request, tDocumentClass)
            val closeRequest = ClosePointInTimeRequest.of { close -> close.id(pitId) }
            return closePointInTime(closeRequest).then(super.search(request, tDocumentClass))
        }
    }

    private class NeverCompletingPitClient(delegate: ReactiveElasticsearchClient) :
        ReactiveElasticsearchClient(delegate._transport(), delegate._transportOptions()) {
        val closedPit: Sinks.One<String> = Sinks.one()

        override fun <T : Any> search(
            request: SearchRequest,
            tDocumentClass: Class<T>,
        ): Mono<ResponseBody<T>> = if (request.pit() == null) {
            super.search(request, tDocumentClass)
        } else {
            Mono.never()
        }

        override fun closePointInTime(request: ClosePointInTimeRequest): Mono<ClosePointInTimeResponse> =
            super.closePointInTime(request).doOnNext { response ->
                if (response.succeeded()) closedPit.tryEmitValue(request.id())
            }
    }

    private class LegacyElasticsearchCountQueryService(
        override val namedAggregate: NamedAggregate,
        private val client: ReactiveElasticsearchClient,
        private val indexName: String,
    ) : SnapshotQueryService<Any> {
        override val name: String = "legacy-elasticsearch-integration-test"
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
            val request = CountRequest.of { count ->
                count.index(indexName).query(SnapshotConditionConverter.convert(condition))
            }
            client.count(request).map { response -> response.count() }
        }
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
    private val deleted = QueryFieldId.System(SystemFieldKind.DELETED)
    private val stateField = QueryFieldId.Path(listOf("state"))
    private val name = QueryFieldId.Path(listOf("state", "name"))
    private val note = QueryFieldId.Path(listOf("state", "note"))
    private val amount = QueryFieldId.Path(listOf("state", "amount"))
    private val scope = SearchScopeId("state-name")
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            field(identity, LogicalFieldType.Text, setOf(PredicateOperator.EQ), EXACT_SORT_PROJECT),
            field(tenant, LogicalFieldType.Text, setOf(PredicateOperator.EQ), setOf(FieldCapability.EXACT)),
            field(deleted, LogicalFieldType.Boolean, setOf(PredicateOperator.IS_FALSE), setOf(FieldCapability.EXACT)),
            field(stateField, LogicalFieldType.Object),
            field(
                name,
                LogicalFieldType.Text,
                setOf(PredicateOperator.EQ, PredicateOperator.CONTAINS),
                setOf(
                    FieldCapability.EXACT,
                    FieldCapability.FULL_TEXT,
                    FieldCapability.LITERAL_PATTERN,
                    FieldCapability.PROJECTABLE,
                    FieldCapability.AGGREGATABLE,
                ),
            ),
            field(
                note,
                LogicalFieldType.Text,
                setOf(PredicateOperator.EQ, PredicateOperator.EXISTS),
                setOf(FieldCapability.EXACT, FieldCapability.PRESENCE, FieldCapability.AGGREGATABLE),
            ),
            field(amount, LogicalFieldType.Int64, capabilities = setOf(FieldCapability.AGGREGATABLE)),
        ),
        listOf(QuerySearchScopeDefinition(scope, null, listOf(name), listOf(name))),
    )

    private fun field(
        id: QueryFieldId,
        type: LogicalFieldType,
        operators: Set<PredicateOperator> = emptySet(),
        capabilities: Set<FieldCapability> = emptySet(),
    ) = QueryFieldSchema(id, type, Presence.OPTIONAL, Nullability.NULLABLE, operators, capabilities)

    private companion object {
        const val MAPPING_VERSION_META = "wow_query_mapping_version"
        const val DOCUMENT_KIND_META = "wow_query_document_kind"
        const val SCHEMA_CONTRACT_META = "wow_query_schema_contract_id"
        const val CAPABILITY_DIGEST_META = "wow_query_capability_digest"
        const val MAPPING_VERSION = "order-query-v1"
        const val HIGH_CARDINALITY_PAGE_SIZE = 31
        const val MAX_HIGH_CARDINALITY_PAGES = 10
        val MISSING = Any()
        val KEYWORD_READINESS = ElasticsearchKeywordReadiness(128, 512, true, true)
        val GROUP_READINESS = ElasticsearchGroupReadiness(historicalValuesAudited = true)
        val NAME_ALIAS = AnalyticsAlias("name")
        val NOTE_ALIAS = AnalyticsAlias("note")
        val COUNT_ALIAS = AnalyticsAlias("count")
        val NUMERIC_POLICY = BackendAnalyticsNumericPolicy(
            BackendAnalyticsNumericPromotion.DECIMAL128,
            34,
            4,
            RoundingMode.HALF_UP,
            BackendAnalyticsOverflowPolicy.REJECT,
        )
        val EXACT_SORT_PROJECT = setOf(
            FieldCapability.EXACT,
            FieldCapability.SORTABLE,
            FieldCapability.PROJECTABLE,
        )
        val OPTIONS = QueryBackendExecutionOptions(
            Instant.now().plusSeconds(300),
            10,
            maxPageWindow = 10,
        )
    }
}
