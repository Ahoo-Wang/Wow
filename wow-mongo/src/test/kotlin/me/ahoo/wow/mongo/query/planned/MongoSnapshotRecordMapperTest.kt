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
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class MongoSnapshotRecordMapperTest {
    private val mapper = MongoRecordMapper(binding.prepared)

    @Test
    fun `should freeze one source observation and restore logical identity`() {
        val bytes = byteArrayOf(1, 2)
        val nested = Document("bytes", bytes)
        val source = Document(
            linkedMapOf(
                Documents.ID_FIELD to "order-1",
                "state" to nested,
                "backendOnly" to "must-not-leak",
            ),
        )

        val record = mapper.map(source)
        bytes[0] = 9
        nested["bytes"] = byteArrayOf(9)
        source[Documents.ID_FIELD] = "order-2"

        record.identity.assert().isEqualTo("order-1")
        (record.document.values[MessageRecords.AGGREGATE_ID] as NormalizedValue.Text).value.assert()
            .isEqualTo("order-1")
        val frozenBytes = (
            (record.document.values["state"] as NormalizedValue.ObjectValue).values["bytes"] as NormalizedValue.Bytes
            ).toByteArray()
        frozenBytes.assert().isEqualTo(byteArrayOf(1, 2))
        record.document.values.containsKey(Documents.ID_FIELD).assert().isFalse()
        record.document.values.containsKey("backendOnly").assert().isFalse()
    }

    @Test
    fun `should reject missing or non-text identity`() {
        assertThrownBy<QueryBackendException> {
            mapper.map(Document("state", Document()))
        }
        assertThrownBy<QueryBackendException> {
            mapper.map(Document(Documents.ID_FIELD, 1))
        }
    }

    @Test
    fun `should reject cyclic or over-budget source before materialization`() {
        val cyclic = Document(Documents.ID_FIELD, "id")
        cyclic["self"] = cyclic
        assertThrownBy<QueryBackendException> {
            mapper.map(cyclic)
        }

        val oversized = Document(Documents.ID_FIELD, "id")
        oversized["values"] = listOf(1, 2, 3)
        assertThrownBy<QueryBackendException> {
            MongoRecordMapper(
                binding.prepared,
                limits = MongoRecordMapper.MappingLimits(maxDepth = 8, maxNodes = 3, maxCollectionSize = 8),
            ).map(oversized)
        }
    }

    @Test
    fun `should apply logical projection after extracting the physical identity`() {
        val source = Document(
            linkedMapOf(
                Documents.ID_FIELD to "order-1",
                "tenantId" to "tenant-1",
                "state" to Document(linkedMapOf("name" to "Alice", "internal" to "hidden")),
                "backendOnly" to "must-not-leak",
            ),
        )
        val stateName = QueryFieldId.Path(listOf("state", "name"))
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)

        val included = mapper.map(source, BackendProjection.Include(listOf(stateName)))
        included.identity.assert().isEqualTo("order-1")
        included.document.values.keys.assert().containsExactly("state")
        (included.document.values["state"] as NormalizedValue.ObjectValue).values.keys.assert()
            .containsExactly("name")

        val excluded = mapper.map(source, BackendProjection.Exclude(listOf(identity)))
        excluded.document.values.containsKey(MessageRecords.AGGREGATE_ID).assert().isFalse()
        excluded.document.values.containsKey("tenantId").assert().isTrue()
    }

    @Test
    fun `should classify non-finite numeric source as mapping failure`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrownBy<QueryBackendException> {
                mapper.map(
                    Document(linkedMapOf(Documents.ID_FIELD to "order-1", "value" to value)),
                )
            }.satisfies(
                Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.MAPPING_FAILURE) },
            )
        }
    }

    private companion object {
        val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
        val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
        val state = QueryFieldId.Path(listOf("state"))
        val stateName = QueryFieldId.Path(listOf("state", "name"))
        val schema = QueryDocumentSchema(
            QueryTarget(MaterializedNamedAggregate("sales", "order"), QueryDocumentKind.SNAPSHOT),
            listOf(
                QueryFieldSchema(
                    identity,
                    LogicalFieldType.Text,
                    Presence.REQUIRED,
                    Nullability.NON_NULL,
                    setOf(PredicateOperator.EQ),
                    setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
                ),
                QueryFieldSchema(
                    tenant,
                    LogicalFieldType.Text,
                    Presence.OPTIONAL,
                    Nullability.NON_NULL,
                    setOf(PredicateOperator.EQ),
                    setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
                ),
                QueryFieldSchema(
                    state,
                    LogicalFieldType.Object,
                    Presence.OPTIONAL,
                    Nullability.NON_NULL,
                    emptySet(),
                    emptySet(),
                ),
                QueryFieldSchema(
                    stateName,
                    LogicalFieldType.Text,
                    Presence.OPTIONAL,
                    Nullability.NON_NULL,
                    setOf(PredicateOperator.EQ),
                    setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
                ),
            ),
            emptyList(),
        )
        val binding = MongoSnapshotQueryBinding(
            schema,
            MongoNamespace("sales", "order_snapshot"),
            linkedMapOf(
                identity to MongoFieldBinding(
                    Documents.ID_FIELD,
                    setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
                ),
                tenant to MongoFieldBinding(
                    MessageRecords.TENANT_ID,
                    setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
                ),
                state to MongoFieldBinding("state", emptySet()),
                stateName to MongoFieldBinding(
                    "state.name",
                    setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
                ),
            ),
        )
    }
}
