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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.elasticsearch.query.planned

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.BackendProjection
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.NormalizedValue
import me.ahoo.wow.query.backend.Nullability
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
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class ElasticsearchSnapshotRecordMapperTest {
    @Test
    fun `mapper should freeze source and apply logical projection without leaking identity`() {
        val bytes = byteArrayOf(1, 2)
        val state = linkedMapOf<String, Any?>("name" to "alice", "secret" to "hidden", "bytes" to bytes)
        val source = linkedMapOf<String, Any?>(
            MessageRecords.AGGREGATE_ID to "order-1",
            "state" to state,
            "backendOnly" to "hidden",
        )

        val record = mapper.map(
            "order-1",
            source,
            BackendProjection.Include(listOf(QueryFieldId.Path(listOf("state", "name")))),
        )
        state["name"] = "changed"
        bytes[0] = 9
        source["backendOnly"] = "changed"

        record.identity.assert().isEqualTo("order-1")
        record.document.values.keys.assert().containsExactly("state")
        val projectedState = record.document.values["state"] as NormalizedValue.ObjectValue
        projectedState.values.assert().containsEntry("name", NormalizedValue.Text("alice"))
        projectedState.values.assert().doesNotContainKey("secret")
    }

    @Test
    fun `mapper should reject missing or inconsistent source identity and cyclic values`() {
        assertMappingFailure {
            mapper.map("order-1", emptyMap())
        }
        assertMappingFailure {
            mapper.map(
                "order-1",
                mapOf(MessageRecords.AGGREGATE_ID to "order-2"),
            )
        }
        val cycle = linkedMapOf<String, Any?>()
        cycle[MessageRecords.AGGREGATE_ID] = "order-1"
        cycle["cycle"] = cycle
        assertMappingFailure {
            mapper.map("order-1", cycle)
        }
    }

    private fun assertMappingFailure(action: () -> Unit) {
        assertThrownBy<QueryBackendException>(action).satisfies(
            Consumer { error -> error.kind.assert().isEqualTo(QueryBackendFailureKind.MAPPING_FAILURE) },
        )
    }

    private val mapper: ElasticsearchSnapshotRecordMapper
        get() {
            val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
            val state = QueryFieldId.Path(listOf("state"))
            val name = QueryFieldId.Path(listOf("state", "name"))
            val schema = QueryDocumentSchema(
                QueryTarget(MaterializedNamedAggregate("sales", "order"), QueryDocumentKind.SNAPSHOT),
                listOf(
                    QueryFieldSchema(
                        identity,
                        LogicalFieldType.Text,
                        Presence.REQUIRED,
                        Nullability.NON_NULL,
                        emptyList(),
                        emptyList()
                    ),
                    QueryFieldSchema(
                        state,
                        LogicalFieldType.Object,
                        Presence.REQUIRED,
                        Nullability.NON_NULL,
                        emptyList(),
                        emptyList()
                    ),
                    QueryFieldSchema(
                        name,
                        LogicalFieldType.Text,
                        Presence.OPTIONAL,
                        Nullability.NULLABLE,
                        emptyList(),
                        emptyList()
                    ),
                ),
                emptyList(),
            )
            return ElasticsearchSnapshotRecordMapper(
                ElasticsearchPreparedQueryBinding(
                    schema,
                    "wow.sales.order.snapshot",
                    "v1",
                    mapOf(
                        identity to ElasticsearchFieldBinding(MessageRecords.AGGREGATE_ID, emptySet()),
                        state to ElasticsearchFieldBinding("state", emptySet()),
                        name to ElasticsearchFieldBinding("state.name", emptySet()),
                    ),
                    emptyMap(),
                    BackendId("elasticsearch"),
                ),
            )
        }
}
