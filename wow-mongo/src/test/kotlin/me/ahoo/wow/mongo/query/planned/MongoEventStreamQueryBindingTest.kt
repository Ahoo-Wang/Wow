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
import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.BackendCountQueryPlan
import me.ahoo.wow.query.backend.BackendEnforcedFilter
import me.ahoo.wow.query.backend.BackendPlannedCondition
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.BackendRequiredCapabilities
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PlanFingerprint
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.SemanticTier
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.junit.jupiter.api.Test

class MongoEventStreamQueryBindingTest {
    @Test
    fun `event stream system fields should bind without snapshot deletion semantics`() {
        val binding = MongoEventStreamQueryBinding.frameworkFields(schema(), namespace)

        binding.fields.getValue(identity).path.assert().isEqualTo(Documents.ID_FIELD)
        binding.fields.getValue(aggregateId).path.assert().isEqualTo(MessageRecords.AGGREGATE_ID)
        binding.fields.containsKey(deleted).assert().isFalse()

        val plan = BackendCountQueryPlan(
            target,
            binding.schema.contractId,
            BackendEnforcedFilter(
                BackendPlannedCondition.Predicate(
                    aggregateId,
                    PredicateOperator.EQ,
                    NormalizedValue.Text("order-1"),
                ),
                BackendPlannedCondition.Predicate(
                    tenant,
                    PredicateOperator.EQ,
                    NormalizedValue.Text("tenant-1"),
                ),
            ),
            BackendRequiredCapabilities(),
            SemanticTier.PORTABLE,
            PlanFingerprint("5".repeat(64)),
        )

        MongoRecordQueryCompiler(binding).compile(plan).filter.toBsonDocument().assert().isEqualTo(
            Filters.and(
                Filters.eq(MessageRecords.AGGREGATE_ID, "order-1"),
                Filters.eq(MessageRecords.TENANT_ID, "tenant-1"),
            ).toBsonDocument(),
        )
    }

    @Test
    fun `event stream mapper should restore id independently from aggregate id`() {
        val binding = MongoEventStreamQueryBinding.frameworkFields(schema(), namespace)
        val source = Document(
            linkedMapOf(
                Documents.ID_FIELD to "stream-1",
                MessageRecords.AGGREGATE_ID to "order-1",
                MessageRecords.TENANT_ID to "tenant-1",
            ),
        )

        val record = MongoRecordMapper(binding.prepared).map(source)
        record.identity.assert().isEqualTo("stream-1")
        (record.document.values.getValue(MessageRecords.ID) as NormalizedValue.Text).value.assert()
            .isEqualTo("stream-1")
        (record.document.values.getValue(MessageRecords.AGGREGATE_ID) as NormalizedValue.Text).value.assert()
            .isEqualTo("order-1")

        val excluded = MongoRecordMapper(binding.prepared).map(
            source,
            BackendProjection.Exclude(listOf(identity)),
        )
        excluded.document.values.containsKey(MessageRecords.ID).assert().isFalse()
        excluded.document.values.containsKey(MessageRecords.AGGREGATE_ID).assert().isTrue()
    }

    @Test
    fun `event stream schema should reject snapshot deleted field`() {
        assertThrownBy<IllegalArgumentException> {
            MongoEventStreamQueryBinding.frameworkFields(schema(includeDeleted = true), namespace)
        }
    }

    private fun schema(includeDeleted: Boolean = false): QueryDocumentSchema = QueryDocumentSchema(
        target,
        buildList {
            add(
                textField(identity, setOf(FieldCapability.EXACT, FieldCapability.SORTABLE, FieldCapability.PROJECTABLE))
            )
            add(textField(aggregateId, setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE)))
            add(textField(tenant, setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE)))
            if (includeDeleted) {
                add(
                    QueryFieldSchema(
                        deleted,
                        LogicalFieldType.Boolean,
                        Presence.REQUIRED,
                        Nullability.NON_NULL,
                        setOf(PredicateOperator.IS_FALSE),
                        setOf(FieldCapability.EXACT),
                    ),
                )
            }
        },
        emptyList(),
    )

    private fun textField(id: QueryFieldId, capabilities: Set<FieldCapability>) = QueryFieldSchema(
        id,
        LogicalFieldType.Text,
        Presence.REQUIRED,
        Nullability.NON_NULL,
        setOf(PredicateOperator.EQ),
        capabilities,
    )

    private companion object {
        val target = QueryTarget(
            MaterializedNamedAggregate("sales", "order"),
            QueryDocumentKind.EVENT_STREAM,
        )
        val namespace = MongoNamespace("sales", "order_event_stream")
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        val aggregateId = QueryFieldId.System(SystemFieldKind.AGGREGATE_ID)
        val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
        val deleted = QueryFieldId.System(SystemFieldKind.DELETED)
    }
}
