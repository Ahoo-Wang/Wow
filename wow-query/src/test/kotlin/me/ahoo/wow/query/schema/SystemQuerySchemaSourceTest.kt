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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.TimeUnit

class SystemQuerySchemaSourceTest {
    @Test
    fun `unsupported query model should be rejected`() {
        assertThrows<QuerySchemaValidationException> {
            SystemQuerySchemaSource.declaration(QueryModel("OTHER"))
        }
    }

    @Test
    fun `snapshot system fields should reject forced mutable map mutation`() {
        val declaration = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
        val state = QueryField("state")
        val original = declaration.fields.getValue(state)
        val mutableFields = declaration.fields as MutableMap<QueryField, QueryFieldDeclaration>

        try {
            assertThrows<UnsupportedOperationException> {
                mutableFields.remove(state)
            }
        } finally {
            if (state !in mutableFields) {
                mutableFields[state] = original
            }
        }

        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .assert().isSameAs(declaration)
        declaration.fields.assert().containsKey(state)
    }

    @Test
    fun `all snapshot aggregate contexts should receive equal system declarations`() {
        val order = QuerySchemaContext(MaterializedNamedAggregate("sales", "Order"), QueryModel.SNAPSHOT)
        val cart = QuerySchemaContext(MaterializedNamedAggregate("sales", "Cart"), QueryModel.SNAPSHOT)

        SystemQuerySchemaSource.load(order).blockFirst()
            .assert().isEqualTo(SystemQuerySchemaSource.load(cart).blockFirst())
    }

    @Test
    fun `snapshot system declaration should match serialized fields`() {
        val fields = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT).fields

        fields.keys.map(QueryField::path).toSet().assert().isEqualTo(
            setOf(
                "contextName",
                "aggregateName",
                "aggregateId",
                "tenantId",
                "ownerId",
                "spaceId",
                "version",
                "eventId",
                "firstOperator",
                "operator",
                "firstEventTime",
                "eventTime",
                "state",
                "tags",
                "deleted",
                "snapshotTime",
            ),
        )
        fields.values.forEach { field ->
            field.required.assert().isEqualTo(DeclarationValue.Set(true))
            field.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        }
    }

    @Test
    fun `snapshot state should be a single object while display leaves remain unset`() {
        val state = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .fields.getValue(QueryField("state"))

        state.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
        state.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.SINGLE))
        state.title.assert().isEqualTo(DeclarationValue.Unset)
        state.description.assert().isEqualTo(DeclarationValue.Unset)
        state.dynamicChildren.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `snapshot tags should be a dynamic single object`() {
        val tags = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .fields.getValue(QueryField("tags"))

        tags.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
        tags.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.SINGLE))
        tags.required.assert().isEqualTo(DeclarationValue.Set(true))
        tags.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        tags.dynamicChildren.assert().isEqualTo(DeclarationValue.Set(true))
    }

    @Test
    fun `snapshot system time fields should be millisecond epochs`() {
        val fields = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT).fields
        val epoch = DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS))

        listOf("firstEventTime", "eventTime", "snapshotTime").forEach { field ->
            fields.getValue(QueryField(field)).semanticType.assert().isEqualTo(epoch)
            fields.getValue(QueryField(field)).valueTypes
                .assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        }
    }

    @Test
    fun `event stream system declaration should match serialized fields`() {
        val fields = SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM).fields
        val serialized = generateEventStream(
            MaterializedNamedAggregate("sales", "Order").aggregateId("order-1"),
            eventCount = 1,
        ).toJsonNode<ObjectNode>()
        val serializedFields = serialized.properties().mapTo(mutableSetOf()) { it.key }
        val event = serialized[MessageRecords.BODY][0] as ObjectNode
        serializedFields += event.properties().map { "${MessageRecords.BODY}.${it.key}" }

        fields.keys.map(QueryField::path).toSet().assert().isEqualTo(serializedFields)
        fields.values.forEach { field ->
            field.required.assert().isEqualTo(DeclarationValue.Set(true))
            field.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        }
        fields.getValue(QueryField("body")).cardinality.assert()
            .isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
        fields.getValue(QueryField("body.body")).dynamicChildren.assert()
            .isEqualTo(DeclarationValue.Set(false))
        fields.getValue(QueryField("header")).dynamicChildren.assert()
            .isEqualTo(DeclarationValue.Set(true))
        fields.getValue(QueryField("createTime")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)))
    }
}
