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
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class SystemQuerySchemaSourceTest {
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

        fields.keys.map(LogicalField::value).toSet().assert().isEqualTo(
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
            .fields.getValue(LogicalField("state"))

        state.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
        state.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.SINGLE))
        state.title.assert().isEqualTo(DeclarationValue.Unset)
        state.description.assert().isEqualTo(DeclarationValue.Unset)
        state.dynamicChildren.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `snapshot system time fields should be millisecond epochs`() {
        val fields = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT).fields
        val epoch = DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS))

        listOf("firstEventTime", "eventTime", "snapshotTime").forEach { field ->
            fields.getValue(LogicalField(field)).semanticType.assert().isEqualTo(epoch)
            fields.getValue(LogicalField(field)).valueTypes
                .assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        }
    }
}
