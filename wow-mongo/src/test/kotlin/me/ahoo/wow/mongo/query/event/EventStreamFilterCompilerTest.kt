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

package me.ahoo.wow.mongo.query.event

import com.mongodb.client.model.Filters
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.mongo.Documents
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

class EventStreamFilterCompilerTest {
    private val schema = QueryModelSchema(
        model = QueryModel.EVENT_STREAM,
        capabilities = emptySet(),
        fields = mapOf(
            binding(MessageRecords.ID, Documents.ID_FIELD),
            binding(MessageRecords.AGGREGATE_ID, MessageRecords.AGGREGATE_ID),
        ),
    )

    private fun compile(filter: me.ahoo.wow.api.query.FilterExpression) =
        EventStreamFilterCompiler.compile(filter, schema)

    @Test
    fun `match all filter should include deleted event streams`() {
        compile(MatchAllFilter).toBsonDocument().assert()
            .isEqualTo(Filters.empty().toBsonDocument())
    }

    @Test
    fun `event metadata filters should preserve event fields`() {
        compile(IdFilter("id-1")).toBsonDocument().assert()
            .isEqualTo(Filters.eq(Documents.ID_FIELD, "id-1").toBsonDocument())
        compile(AggregateIdFilter("aggregate-1")).toBsonDocument().assert()
            .isEqualTo(Filters.eq(MessageRecords.AGGREGATE_ID, "aggregate-1").toBsonDocument())
    }

    @Test
    fun `event plural metadata filters should preserve event fields`() {
        compile(IdsFilter(listOf("id-1", "id-2"))).toBsonDocument().assert()
            .isEqualTo(Filters.`in`(Documents.ID_FIELD, "id-1", "id-2").toBsonDocument())
        compile(
            AggregateIdsFilter(listOf("aggregate-1", "aggregate-2")),
        ).toBsonDocument().assert().isEqualTo(
            Filters.`in`(MessageRecords.AGGREGATE_ID, "aggregate-1", "aggregate-2").toBsonDocument(),
        )
    }

    @Test
    fun `mapped fields should be preserved in logical filters`() {
        compile(
            AndFilter(
                listOf(
                    EqualFilter(QueryField(MessageRecords.ID), JsonSerializer.valueToTree("event-id")),
                    EqualFilter(QueryField("body.name"), JsonSerializer.valueToTree("event-name")),
                ),
            ),
        ).toBsonDocument().assert().isEqualTo(
            Filters.and(
                Filters.eq(Documents.ID_FIELD, "event-id"),
                Filters.eq("body.name", "event-name"),
            ).toBsonDocument(),
        )
    }

    @Test
    fun `element predicate fields should remain relative`() {
        compile(
            ElementMatchFilter(
                QueryField("body"),
                EqualFilter(QueryField(MessageRecords.ID), JsonSerializer.valueToTree("event-body-id")),
            ),
        ).toBsonDocument().assert().isEqualTo(
            Filters.elemMatch(
                "body",
                Filters.eq(MessageRecords.ID, "event-body-id"),
            ).toBsonDocument(),
        )
    }

    private fun binding(logicalPath: String, physicalPath: String): Pair<QueryField, QueryFieldSchema> {
        val logical = QueryField(logicalPath)
        return logical to QueryFieldSchema(
            title = null,
            description = null,
            enumValues = null,
            valueTypes = setOf(QueryValueType.STRING),
            nullable = false,
            required = true,
            cardinality = QueryCardinality.SINGLE,
            semanticType = null,
            dynamicChildren = false,
            bindings = mapOf(
                QueryCapability.EXACT_MATCH to QueryFieldBinding(
                    logical,
                    QueryField(physicalPath),
                    QueryStorageType("test"),
                ),
            ),
            rewriteMode = QueryRewriteMode.NONE,
        )
    }
}
