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

package me.ahoo.wow.mongo.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.SupportMode
import org.bson.Document
import org.junit.jupiter.api.Test

class MongoQuerySchemaAdapterTest {
    private fun QueryModelSchema.capabilities(path: String): Set<QueryCapability> =
        checkNotNull(field(QueryField(path))) { "$path is unknown." }.capabilities

    private fun QueryModelSchema.physical(path: String, capability: QueryCapability = QueryCapability.PRESENCE): String =
        checkNotNull(checkNotNull(field(QueryField(path))).binding(capability)).physicalField.path

    @Test
    fun `every logical path binds to where the snapshot document stores it`() {
        val schema = MongoQueryTestSchemas.SCHEMA
        schema.physical("tenantId").assert().isEqualTo("tenant_id")
        schema.physical("aggregateId").assert().isEqualTo("aggregate_id")
        schema.physical("ownerId").assert().isEqualTo("owner_id")
        schema.physical("spaceId").assert().isEqualTo("space_id")
        schema.physical("firstEventTime").assert().isEqualTo("first_event_time")
        schema.physical("lastEventTime").assert().isEqualTo("last_event_time")
        schema.physical("version").assert().isEqualTo("version")
        schema.physical("deleted").assert().isEqualTo("deleted")
        schema.physical("state.name").assert().isEqualTo("state.name")
        schema.physical("tags.region").assert().isEqualTo("tags.region")
        checkNotNull(schema.field(QueryField("tenantId"))).let { field ->
            field.responseField.assert().isEqualTo(QueryField("tenantId"))
            field.projectionField.assert().isEqualTo(QueryField("tenant_id"))
            field.binding(
                QueryCapability.EXACT_MATCH
            )?.storageTypes.assert().isEqualTo(setOf(QueryStorageType("string")))
        }
        checkNotNull(schema.field(QueryField("version"))).binding(QueryCapability.RANGE)?.storageTypes
            .assert().isEqualTo(setOf(QueryStorageType("int")))
    }

    @Test
    fun `values get what MongoDB runs exactly`() {
        val schema = MongoQueryTestSchemas.SCHEMA
        schema.capabilities("state.amount").assert().contains(
            QueryCapability.EXACT_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.CURSOR_SORT,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        schema.capabilities("state.createdAt").assert().contains(QueryCapability.AGGREGATE_TEMPORAL)
        schema.capabilities(
            "lastEventTime"
        ).assert().contains(QueryCapability.AGGREGATE_TEMPORAL, QueryCapability.CURSOR_SORT)
        // A date string is stored as text: it only exists.
        schema.capabilities("state.issuedAt").assert().containsExactly(QueryCapability.PRESENCE)
        // MongoDB reads each type of a mixed value on its own, unlike PostgreSQL; no cursor orders it.
        schema.capabilities("state.mixed").assert().contains(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)
        schema.capabilities("state.mixed").assert().doesNotContain(QueryCapability.CURSOR_SORT)
        // An array sorts by its least or greatest element, but no cursor orders it.
        schema.capabilities("state.keywords").assert().contains(QueryCapability.SORT, QueryCapability.EXACT_MATCH)
        schema.capabilities("state.keywords").assert().doesNotContain(QueryCapability.CURSOR_SORT)
        schema.capabilities("state.lines").assert().contains(QueryCapability.ELEMENT_SCOPE)
        schema.capabilities("state.lines.parts").assert().contains(QueryCapability.ELEMENT_SCOPE)
    }

    @Test
    fun `the storage runs every aggregation natively but PREVIOUS`() {
        val schema = MongoQueryTestSchemas.SCHEMA
        schema.storage.parallelArraySort.assert().isEqualTo(SupportMode.NONE)
        schema.storage.aggregation.window(WindowFunction.PREVIOUS).assert().isEqualTo(SupportMode.RESIDUAL)
        schema.storage.aggregation.window(WindowFunction.RANK).assert().isEqualTo(SupportMode.NATIVE)
        schema.storage.aggregation.denseFill.assert().isEqualTo(SupportMode.NATIVE)
        schema.storage.aggregation.having.assert().isEqualTo(SupportMode.NATIVE)
        schema.provisional.assert().isFalse()
        schema.capabilities.assert().isEmpty()
    }

    @Test
    fun `a validator narrows what the stored types prove`() {
        val validator = Document(
            "bsonType",
            "object",
        ).append(
            "properties",
            Document("version", Document("bsonType", "string"))
                .append(
                    "state",
                    Document("bsonType", "object").append(
                        "properties",
                        Document("amount", Document("bsonType", "string"))
                            .append("quantity", Document("bsonType", listOf("int", "long")))
                            .append("keywords", Document("bsonType", "object")),
                    ),
                ),
        )
        val schema = MongoQueryTestSchemas.schema(MongoQueryTestSchemas.adapter(validator = validator))
        schema.capabilities("version").assert().containsExactly(QueryCapability.PRESENCE)
        schema.capabilities(
            "state.amount"
        ).assert().doesNotContain(QueryCapability.RANGE, QueryCapability.AGGREGATE_NUMERIC)
        schema.capabilities("state.quantity").assert().contains(QueryCapability.RANGE, QueryCapability.CURSOR_SORT)
        checkNotNull(schema.field(QueryField("state.quantity"))).binding(QueryCapability.RANGE)?.storageTypes
            .assert().isEqualTo(setOf(QueryStorageType("int"), QueryStorageType("long")))
        // A container the validator contradicts proves nothing below it.
        schema.capabilities("state.keywords").assert().isEmpty()
    }

    @Test
    fun `the validator is read from the collection's options`() {
        val schema = Document("bsonType", "object")
        Document("options", Document("validator", Document("\$jsonSchema", schema))).validatorSchema()
            .assert().isEqualTo(schema)
        Document("options", Document()).validatorSchema().assert().isNull()
    }

    @Test
    fun `a text index proves full-text search over the whole record`() {
        fun search(index: Document): Set<QueryCapability> =
            MongoQueryTestSchemas.schema(MongoQueryTestSchemas.adapter(indexes = listOf(index))).capabilities
        val text = Document("name", "text").append("key", Document("state.name", "text"))
        search(
            text
        ).assert().containsExactlyInAnyOrder(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)
        search(Document(text).append("hidden", true)).assert().isEmpty()
        search(Document(text).append("partialFilterExpression", Document("deleted", false))).assert().isEmpty()
        search(Document("name", "owner_ix").append("key", Document("tenant_id", 1))).assert().isEmpty()
    }
}
