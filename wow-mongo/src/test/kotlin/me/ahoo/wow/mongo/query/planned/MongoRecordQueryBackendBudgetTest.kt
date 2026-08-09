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
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendPageWindow
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.BackendRequiredConsistency
import me.ahoo.wow.query.backend.BackendSort
import me.ahoo.wow.query.backend.BackendSortOrigin
import me.ahoo.wow.query.backend.BackendTotalMode
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedSortDirection
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.RecordResultShape
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import org.bson.Document
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class MongoRecordQueryBackendBudgetTest {
    @Test
    fun `unsupported and exceeded budgets should fail before obtaining a Mongo publisher`() {
        val storageCalls = AtomicInteger()
        val backend = MongoRecordQueryBackend(
            rejectingCollection(storageCalls),
            binding.prepared,
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

        assertBackendFailure(QueryBackendFailureKind.UNSUPPORTED) {
            backend.count(countPlan, QueryBackendExecutionOptions(null, null, maxScannedRecords = 1)).block()
        }
        assertBackendFailure(QueryBackendFailureKind.BUDGET_EXCEEDED) {
            backend.page(pagePlan, QueryBackendExecutionOptions(null, 1)).block()
        }
        assertBackendFailure(QueryBackendFailureKind.BUDGET_EXCEEDED) {
            backend.page(
                pagePlan,
                QueryBackendExecutionOptions(null, null, maxPageWindow = 1),
            ).block()
        }
        assertBackendFailure(QueryBackendFailureKind.TIMEOUT) {
            backend.count(
                countPlan,
                QueryBackendExecutionOptions(NOW, null),
            ).block()
        }

        storageCalls.get().assert().isZero()
    }

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

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val deleted = QueryFieldId.System(SystemFieldKind.DELETED)
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            QueryFieldSchema(
                identity,
                LogicalFieldType.Text,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                listOf(PredicateOperator.EQ),
                listOf(FieldCapability.EXACT, FieldCapability.SORTABLE),
            ),
            QueryFieldSchema(
                deleted,
                LogicalFieldType.Boolean,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                listOf(PredicateOperator.IS_FALSE),
                listOf(FieldCapability.EXACT),
            ),
        ),
        emptyList(),
    )
    private val binding = MongoSnapshotQueryBinding.frameworkFields(
        schema,
        MongoNamespace("test", "order_snapshot"),
    )
    private val filter = BackendEnforcedFilter(BackendPlannedCondition.All, BackendPlannedCondition.All)
    private val countPlan = BackendCountQueryPlan(
        target,
        schema.contractId,
        filter,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("1".repeat(64)),
    )
    private val pagePlan = BackendPageQueryPlan(
        target,
        schema.contractId,
        filter,
        RecordResultShape.DYNAMIC,
        BackendProjection.All,
        listOf(BackendSort(identity, NormalizedSortDirection.ASC, BackendSortOrigin.STABILITY_TIE_BREAKER)),
        BackendPageWindow(1, 2),
        BackendTotalMode.EXACT,
        BackendRequiredConsistency.SAME_INPUT,
        BackendRequiredCapabilities(),
        SemanticTier.PORTABLE,
        PlanFingerprint("2".repeat(64)),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
