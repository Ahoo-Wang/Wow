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
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.function.Consumer

class MongoAnalyticsQueryCompilerTest {
    private val fixture = Fixture()
    private val compiler = MongoAnalyticsQueryCompiler(fixture.binding.prepared)

    @Test
    fun `global pipeline should preserve enforced filter and exact numeric metrics`() {
        val filter = BackendEnforcedFilter(
            fixture.predicate(fixture.status, NormalizedValue.Text("PAID")),
            fixture.predicate(fixture.tenant, NormalizedValue.Text("tenant-1")),
        )
        val compiled = compiler.compile(
            fixture.plan(
                BackendAnalyticsGrouping.Global,
                listOf(
                    BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
                    BackendAnalyticsMetric.Sum(AnalyticsAlias("total"), fixture.amount),
                ),
                filter = filter,
            ),
        )
        val pipeline = compiled.pipeline.map { stage -> stage.toBsonDocument() }

        pipeline.map { stage -> stage.keys.single() }.assert().containsExactly("\$match", "\$group", "\$limit")
        pipeline[0].getDocument("\$match").toJson().assert().contains(MessageRecords.TENANT_ID)
        val group = pipeline[1].getDocument("\$group")
        requireNotNull(group["_id"]).isNull.assert().isTrue()
        group.getDocument("count").getInt32("\$sum").value.assert().isEqualTo(1)
        group.getDocument("total").getDocument("\$sum").getString("\$toDecimal").value.assert()
            .isEqualTo("\$state.amount")
        pipeline[2].getInt32("\$limit").value.assert().isEqualTo(2)
    }

    @Test
    fun `grouped pipeline should apply missing policy compound cursor and stable order`() {
        val plan = fixture.plan(
            BackendAnalyticsGrouping.By(
                listOf(
                    BackendAnalyticsDimension(
                        AnalyticsAlias("status"),
                        fixture.status,
                        BackendAnalyticsMissingPolicy.EXCLUDE,
                    ),
                    BackendAnalyticsDimension(
                        AnalyticsAlias("amount"),
                        fixture.amount,
                        BackendAnalyticsMissingPolicy.AS_NULL_BUCKET,
                    ),
                ),
            ),
            listOf(
                BackendAnalyticsMetric.Min(AnalyticsAlias("minimum"), fixture.amount),
                BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count")),
            ),
            window = BackendAnalyticsPageWindow(
                2,
                listOf(NormalizedValue.Text("PAID"), NormalizedValue.Decimal(BigDecimal.TEN)),
            ),
        )
        val pipeline = compiler.compile(plan).pipeline.map { stage -> stage.toBsonDocument() }

        pipeline.map { stage -> stage.keys.single() }.assert().containsExactly(
            "\$match",
            "\$match",
            "\$group",
            "\$match",
            "\$sort",
            "\$limit",
        )
        pipeline[1].getDocument("\$match").toJson().assert().contains("state.status")
        val groupId = pipeline[2].getDocument("\$group").getDocument("_id")
        groupId.getString("status").value.assert().isEqualTo("\$state.status")
        groupId.getDocument("amount").getArray("\$ifNull").values.last().isNull.assert().isTrue()
        val cursor = pipeline[3].getDocument("\$match").getArray("\$or")
        cursor.size.assert().isEqualTo(2)
        cursor.values[1].asDocument().getString("_id.status").value.assert().isEqualTo("PAID")
        cursor.values[1].asDocument().getDocument("_id.amount").getDecimal128("\$gt").value
            .bigDecimalValue().compareTo(BigDecimal.TEN).assert().isZero()
        pipeline[4].getDocument("\$sort").assertKeys("_id.status", "_id.amount")
        pipeline[5].getInt32("\$limit").value.assert().isEqualTo(3)
    }

    @Test
    fun `cursor should retain the exact canonical dimension type`() {
        val plan = fixture.plan(
            BackendAnalyticsGrouping.By(
                listOf(
                    BackendAnalyticsDimension(
                        AnalyticsAlias("amount"),
                        fixture.amount,
                        BackendAnalyticsMissingPolicy.AS_NULL_BUCKET,
                    ),
                ),
            ),
            listOf(BackendAnalyticsMetric.DocumentCount(AnalyticsAlias("count"))),
            window = BackendAnalyticsPageWindow(2, listOf(NormalizedValue.Text("10"))),
        )

        assertThrownBy<QueryBackendException> { compiler.compile(plan) }.satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.UNSUPPORTED) },
        )
    }

    private fun BsonDocument.assertKeys(vararg keys: String) {
        this.keys.toList().assert().containsExactly(*keys)
    }

    private class Fixture {
        val target = QueryTarget(MaterializedNamedAggregate("sales", "order"), QueryDocumentKind.SNAPSHOT)
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
        val deleted = QueryFieldId.System(SystemFieldKind.DELETED)
        val state = QueryFieldId.Path(listOf("state"))
        val status = QueryFieldId.Path(listOf("state", "status"))
        val amount = QueryFieldId.Path(listOf("state", "amount"))
        val schema = QueryDocumentSchema(
            target,
            listOf(
                field(identity, LogicalFieldType.Text, FieldCapability.EXACT),
                field(tenant, LogicalFieldType.Text, FieldCapability.EXACT),
                field(deleted, LogicalFieldType.Boolean, FieldCapability.EXACT),
                field(state, LogicalFieldType.Object),
                field(status, LogicalFieldType.Text, FieldCapability.EXACT, FieldCapability.AGGREGATABLE),
                field(amount, LogicalFieldType.Decimal, FieldCapability.AGGREGATABLE),
            ),
            emptyList(),
        )
        val binding = MongoSnapshotQueryBinding(
            schema,
            MongoNamespace("sales", "order_snapshot"),
            linkedMapOf(
                identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                deleted to MongoFieldBinding(StateAggregateRecords.DELETED, setOf(FieldCapability.EXACT)),
                state to MongoFieldBinding("state", emptySet()),
                status to MongoFieldBinding(
                    "state.status",
                    setOf(FieldCapability.EXACT, FieldCapability.AGGREGATABLE),
                ),
                amount to MongoFieldBinding(
                    "state.amount",
                    setOf(FieldCapability.AGGREGATABLE),
                    MongoValueEncoding.DECIMAL128,
                ),
            ),
        )

        fun predicate(field: QueryFieldId, value: NormalizedValue) = BackendPlannedCondition.Predicate(
            field,
            PredicateOperator.EQ,
            value,
        )

        fun plan(
            grouping: BackendAnalyticsGrouping,
            metrics: List<BackendAnalyticsMetric>,
            filter: BackendEnforcedFilter = BackendEnforcedFilter(
                BackendPlannedCondition.All,
                BackendPlannedCondition.All,
            ),
            window: BackendAnalyticsPageWindow = BackendAnalyticsPageWindow(1),
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
            BackendAnalyticsNumericPolicy(
                BackendAnalyticsNumericPromotion.DECIMAL128,
                34,
                2,
                RoundingMode.HALF_UP,
                BackendAnalyticsOverflowPolicy.REJECT,
            ),
            BackendAnalyticsConsistency.EVENTUAL,
            BackendAnalyticsCompleteness.EXACT,
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("5".repeat(64)),
        )

        private fun field(
            id: QueryFieldId,
            type: LogicalFieldType,
            vararg capabilities: FieldCapability,
        ): QueryFieldSchema {
            val capabilitySet = capabilities.toSet()
            return QueryFieldSchema(
                id,
                type,
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                if (FieldCapability.EXACT in capabilitySet) setOf(PredicateOperator.EQ) else emptySet(),
                capabilitySet,
            )
        }
    }
}
