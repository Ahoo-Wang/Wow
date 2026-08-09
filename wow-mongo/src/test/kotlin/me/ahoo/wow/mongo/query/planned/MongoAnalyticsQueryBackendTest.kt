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

import com.mongodb.MongoNamespace
import com.mongodb.reactivestreams.client.AggregatePublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.AnalyticsAlias
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
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.bson.Document
import org.bson.types.Decimal128
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier
import reactor.test.publisher.TestPublisher
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

class MongoAnalyticsQueryBackendTest {
    @Test
    fun `result mapper should apply Decimal128 policy and synthesize one empty global bucket`() {
        val mapper = MongoAnalyticsResultMapper(binding.prepared)
        val plan = plan(
            listOf(
                BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
                BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), amount),
                BackendAnalyticsMetric.Average(AnalyticsAlias("average"), amount),
            ),
        )

        val mapped = mapper.map(
            Document("_id", null)
                .append("count", 2L)
                .append("total", Decimal128(BigDecimal("20.125")))
                .append("average", Decimal128(BigDecimal("10.0625"))),
            plan,
        )
        mapped.metrics[AnalyticsAlias("count")].assert().isEqualTo(NormalizedValue.Int64(2))
        mapped.metrics[AnalyticsAlias("total")].assert()
            .isEqualTo(NormalizedValue.Decimal(BigDecimal("20.13")))
        mapped.metrics[AnalyticsAlias("average")].assert()
            .isEqualTo(NormalizedValue.Decimal(BigDecimal("10.06")))

        val empty = mapper.emptyGlobal(plan)
        empty.metrics[AnalyticsAlias("count")].assert().isEqualTo(NormalizedValue.Int64(0))
        empty.metrics[AnalyticsAlias("total")].assert().isEqualTo(NormalizedValue.Decimal(BigDecimal.ZERO))
        empty.metrics[AnalyticsAlias("average")].assert().isEqualTo(NormalizedValue.Null)
    }

    @Test
    fun `result mapper should reject values exceeding the declared Decimal128 precision`() {
        val mapper = MongoAnalyticsResultMapper(binding.prepared)
        val policy = BackendAnalyticsNumericPolicy(
            BackendAnalyticsNumericPromotion.DECIMAL128,
            3,
            0,
            RoundingMode.UNNECESSARY,
            BackendAnalyticsOverflowPolicy.REJECT,
        )
        val plan = plan(
            listOf(BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), amount)),
            policy,
        )

        assertBackendFailure(QueryBackendFailureKind.MAPPING_FAILURE) {
            mapper.map(
                Document("_id", null).append("total", Decimal128(BigDecimal("1234"))),
                plan,
            )
        }
    }

    @Test
    fun `unsupported exceeded and expired analytics budgets should fail before Mongo access`() {
        val storageCalls = AtomicInteger()
        val backend = MongoAnalyticsQueryBackend(
            rejectingCollection(storageCalls),
            binding.prepared,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val plan = plan(listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count"))))

        listOf(
            QueryBackendFailureKind.UNSUPPORTED to QueryBackendExecutionOptions(null, null, maxScannedRecords = 1),
            QueryBackendFailureKind.UNSUPPORTED to QueryBackendExecutionOptions(null, null, maxCandidateBuckets = 1),
            QueryBackendFailureKind.UNSUPPORTED to QueryBackendExecutionOptions(null, null, maxCursorPages = 1),
            QueryBackendFailureKind.BUDGET_EXCEEDED to QueryBackendExecutionOptions(
                null,
                null,
                maxReturnedBuckets = 1,
            ),
            QueryBackendFailureKind.TIMEOUT to QueryBackendExecutionOptions(NOW, null),
        ).forEach { (kind, options) ->
            assertBackendFailure(kind) { backend.analyze(groupedPlan(plan, 2), options).block() }
        }
        storageCalls.get().assert().isZero()
    }

    @Test
    fun `analytics backend should propagate remaining deadline and cancellation to Mongo publisher`() {
        val publisher = TestPublisher.createCold<Document>()
        val maxTimeMillis = AtomicLong(-1)
        val backend = MongoAnalyticsQueryBackend(
            aggregateCollection(publisher, maxTimeMillis),
            binding.prepared,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

        StepVerifier.create(
            backend.analyze(
                plan(listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")))),
                QueryBackendExecutionOptions(NOW.plusSeconds(2), null),
            ),
        ).thenCancel().verify()

        maxTimeMillis.get().assert().isEqualTo(2_000)
        publisher.assertWasSubscribed()
        publisher.assertCancelled()
    }

    private fun groupedPlan(source: BackendAnalyticsQueryPlan, limit: Int) = BackendAnalyticsQueryPlan(
        source.target,
        source.schemaContractId,
        source.filter,
        BackendAnalyticsGrouping.By(
            listOf(
                BackendAnalyticsDimension(
                    AnalyticsAlias("amount"),
                    amount,
                    BackendAnalyticsMissingPolicy.AS_NULL_BUCKET,
                ),
            ),
        ),
        source.metrics,
        source.having,
        BackendAnalyticsBucketOrder.DimensionKeyAscending(
            BackendAnalyticsNullPlacement.FIRST,
            BackendAnalyticsTextCollation.BINARY,
        ),
        BackendAnalyticsPageWindow(limit),
        source.numericPolicy,
        source.requiredConsistency,
        source.requiredCompleteness,
        source.requiredCapabilities,
        source.semanticTier,
        source.fingerprint,
    )

    private fun plan(
        metrics: List<BackendAnalyticsMetric>,
        numericPolicy: BackendAnalyticsNumericPolicy? = NUMERIC_POLICY.takeIf {
            metrics.any { metric -> metric !is BackendAnalyticsMetric.DocumentCount }
        },
    ) = BackendAnalyticsQueryPlan(
        target,
        schema.contractId,
        BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All),
        BackendAnalyticsGrouping.Global,
        metrics,
        BackendAnalyticsCondition.All,
        BackendAnalyticsBucketOrder.Global,
        BackendAnalyticsPageWindow(1),
        numericPolicy,
        BackendAnalyticsConsistency.EVENTUAL,
        BackendAnalyticsCompleteness.EXACT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("6".repeat(64)),
    )

    private fun assertBackendFailure(kind: QueryBackendFailureKind, action: () -> Unit) {
        assertThrownBy<QueryBackendException>(action).satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(kind) },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun rejectingCollection(calls: AtomicInteger): MongoCollection<Document> = Proxy.newProxyInstance(
        MongoCollection::class.java.classLoader,
        arrayOf(MongoCollection::class.java),
    ) { _, method, _ ->
        calls.incrementAndGet()
        error("Mongo collection method ${method.name} must not be called before budget validation.")
    } as MongoCollection<Document>

    @Suppress("UNCHECKED_CAST")
    private fun aggregateCollection(
        publisher: TestPublisher<Document>,
        maxTimeMillis: AtomicLong,
    ): MongoCollection<Document> {
        lateinit var aggregate: AggregatePublisher<Document>
        aggregate = Proxy.newProxyInstance(
            AggregatePublisher::class.java.classLoader,
            arrayOf(AggregatePublisher::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "allowDiskUse", "collation" -> aggregate
                "maxTime" -> aggregate.also {
                    maxTimeMillis.set((arguments!![0] as Number).toLong())
                }

                "subscribe" -> {
                    publisher.subscribe(arguments!![0] as org.reactivestreams.Subscriber<in Document>)
                    null
                }

                else -> error("Unexpected AggregatePublisher method: ${method.name}")
            }
        } as AggregatePublisher<Document>
        return Proxy.newProxyInstance(
            MongoCollection::class.java.classLoader,
            arrayOf(MongoCollection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "aggregate" -> aggregate
                else -> error("Unexpected MongoCollection method: ${method.name}")
            }
        } as MongoCollection<Document>
    }

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val amount = QueryFieldId.Path(listOf("amount"))
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            QueryFieldSchema(
                identity,
                LogicalFieldType.Text,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                emptySet(),
                setOf(FieldCapability.EXACT),
            ),
            QueryFieldSchema(
                amount,
                LogicalFieldType.Decimal,
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                emptySet(),
                setOf(FieldCapability.AGGREGATABLE),
            ),
        ),
        emptyList(),
    )
    private val binding = MongoSnapshotQueryBinding(
        schema,
        MongoNamespace("sales", "order_snapshot"),
        mapOf(
            identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
            amount to MongoFieldBinding("amount", setOf(FieldCapability.AGGREGATABLE), MongoValueEncoding.DECIMAL128),
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
        val NUMERIC_POLICY = BackendAnalyticsNumericPolicy(
            BackendAnalyticsNumericPromotion.DECIMAL128,
            34,
            2,
            RoundingMode.HALF_UP,
            BackendAnalyticsOverflowPolicy.REJECT,
        )
    }
}
