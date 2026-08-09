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
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.backend.FieldCapability
import me.ahoo.wow.query.backend.LogicalFieldType
import me.ahoo.wow.query.backend.Nullability
import me.ahoo.wow.query.backend.PredicateOperator
import me.ahoo.wow.query.backend.Presence
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.QueryFieldId
import me.ahoo.wow.query.backend.QueryFieldSchema
import me.ahoo.wow.query.backend.QuerySearchScopeDefinition
import me.ahoo.wow.query.backend.SearchScopeId
import me.ahoo.wow.query.backend.SystemFieldKind
import me.ahoo.wow.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.junit.jupiter.api.Test

class MongoSnapshotQueryBindingTest {
    @Test
    fun `binding should reject unsafe physical paths and wrong system mapping`() {
        assertThrownBy<IllegalArgumentException> {
            MongoFieldBinding("state.\$expr", emptySet())
        }
        assertThrownBy<IllegalArgumentException> {
            binding(
                mapOf(
                    identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                    tenant to MongoFieldBinding(MessageRecords.OWNER_ID, setOf(FieldCapability.EXACT)),
                    createdAt to MongoFieldBinding(
                        "createdAt",
                        setOf(FieldCapability.RANGE),
                        MongoValueEncoding.EPOCH_MILLIS,
                    ),
                ),
            )
        }

        val collidingPath = QueryFieldId.Path(listOf(MessageRecords.TENANT_ID))
        val collidingSchema = QueryDocumentSchema(
            target,
            schema.fields.values + QueryFieldSchema(
                collidingPath,
                LogicalFieldType.Text,
                Presence.OPTIONAL,
                Nullability.NON_NULL,
                emptySet(),
                emptySet(),
            ),
            emptyList(),
        )
        assertThrownBy<IllegalArgumentException> {
            MongoSnapshotQueryBinding(
                collidingSchema,
                namespace,
                linkedMapOf(
                    identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                    tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                    createdAt to MongoFieldBinding(
                        "createdAt",
                        setOf(FieldCapability.RANGE),
                        MongoValueEncoding.EPOCH_MILLIS,
                    ),
                    collidingPath to MongoFieldBinding(MessageRecords.TENANT_ID, emptySet()),
                ),
            )
        }
        assertThrownBy<IllegalArgumentException> {
            binding(
                mapOf(
                    identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                    tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                ),
            )
        }
    }

    @Test
    fun `binding should reject logical type and value encoding mismatch`() {
        assertThrownBy<IllegalArgumentException> {
            binding(
                mapOf(
                    identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                    createdAt to MongoFieldBinding(
                        "createdAt",
                        setOf(FieldCapability.RANGE),
                        MongoValueEncoding.DEFAULT,
                    ),
                    tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                ),
            )
        }
    }

    @Test
    fun `contribution should require the exact collection namespace`() {
        val binding = binding(
            mapOf(
                identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                createdAt to MongoFieldBinding(
                    "createdAt",
                    setOf(FieldCapability.RANGE),
                    MongoValueEncoding.EPOCH_MILLIS,
                ),
            ),
        )
        val collection = mockk<MongoCollection<Document>>()
        every { collection.namespace } returns MongoNamespace("sales", "cart_snapshot")

        assertThrownBy<IllegalArgumentException> {
            binding.toContribution(collection)
        }
    }

    @Test
    fun `text search contribution should require an exact attested text index`() {
        val description = QueryFieldId.Path(listOf("description"))
        val scopeId = SearchScopeId("document-text")
        val searchSchema = QueryDocumentSchema(
            target,
            schema.fields.values + QueryFieldSchema(
                description,
                LogicalFieldType.Text,
                Presence.OPTIONAL,
                Nullability.NULLABLE,
                emptySet(),
                setOf(FieldCapability.FULL_TEXT),
            ),
            listOf(QuerySearchScopeDefinition(scopeId, null, listOf(description), listOf(description))),
        )
        val binding = MongoSnapshotQueryBinding(
            searchSchema,
            namespace,
            mapOf(
                identity to MongoFieldBinding(Documents.ID_FIELD, setOf(FieldCapability.EXACT)),
                tenant to MongoFieldBinding(MessageRecords.TENANT_ID, setOf(FieldCapability.EXACT)),
                createdAt to MongoFieldBinding(
                    "createdAt",
                    setOf(FieldCapability.RANGE),
                    MongoValueEncoding.EPOCH_MILLIS,
                ),
                description to MongoFieldBinding("description", setOf(FieldCapability.FULL_TEXT)),
            ),
            textSearch = MongoTextSearchBinding(scopeId, "description_text"),
        )
        val ready = Document("name", "description_text")
            .append("key", Document("_fts", "text").append("_ftsx", 1))
            .append("weights", Document("description", 1))

        binding.attestTextIndexReadiness(listOf(ready))
        assertThrownBy<MongoQueryBackendNotReadyException> {
            binding.attestTextIndexReadiness(listOf(Document(ready).append("name", "stale_text")))
        }
        assertThrownBy<MongoQueryBackendNotReadyException> {
            binding.attestTextIndexReadiness(
                listOf(Document(ready).append("weights", Document("other", 1))),
            )
        }
        binding.textSearch?.scope.assert().isEqualTo(scopeId)
    }

    private fun binding(fields: Map<QueryFieldId, MongoFieldBinding>): MongoSnapshotQueryBinding =
        MongoSnapshotQueryBinding(schema, namespace, fields)

    private val target = QueryTarget(
        MaterializedNamedAggregate("sales", "order"),
        QueryDocumentKind.SNAPSHOT,
    )
    private val namespace = MongoNamespace("sales", "order_snapshot")
    private val identity = QueryFieldId.System(SystemFieldKind.IDENTITY)
    private val tenant = QueryFieldId.System(SystemFieldKind.TENANT_ID)
    private val createdAt = QueryFieldId.Path(listOf("createdAt"))
    private val schema = QueryDocumentSchema(
        target,
        listOf(
            QueryFieldSchema(
                identity,
                LogicalFieldType.Text,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                setOf(PredicateOperator.EQ),
                setOf(FieldCapability.EXACT),
            ),
            QueryFieldSchema(
                tenant,
                LogicalFieldType.Text,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                setOf(PredicateOperator.EQ),
                setOf(FieldCapability.EXACT),
            ),
            QueryFieldSchema(
                createdAt,
                LogicalFieldType.Instant,
                Presence.REQUIRED,
                Nullability.NON_NULL,
                setOf(PredicateOperator.GTE),
                setOf(FieldCapability.RANGE),
            ),
        ),
        emptyList(),
    )
}
