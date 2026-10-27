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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.spi.json.query.SnapshotFields
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class SystemQuerySchemaSourceTest {
    @Test
    fun `unsupported query model should be rejected`() {
        assertThrows<QuerySchemaValidationException> {
            SystemQuerySchemaSource.declaration(QueryModel("OTHER"))
        }.errorCode.assert().isEqualTo(QueryErrors.FAILED)
        assertThrows<QuerySchemaValidationException> {
            SystemQuerySchemaSource.declaration(QueryModel.DATASET)
        }
    }

    @Test
    fun `the system source has the lowest priority and refreshes what it loads`() {
        runTest {
            val context = QuerySchemaContext(AggregateType("sales", "order"), QueryModel.SNAPSHOT)
            SystemQuerySchemaSource.priority.assert().isEqualTo(Int.MIN_VALUE)
            SystemQuerySchemaSource.refresh(context).assert().isEqualTo(SystemQuerySchemaSource.load(context))
        }
    }

    @Test
    fun `snapshot system fields should reject forced mutable map mutation`() {
        val declaration = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
        val state = QueryField("state")
        val original = declaration.fields.getValue(state)

        @Suppress("UNCHECKED_CAST")
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
        runTest {
            val order = QuerySchemaContext(AggregateType("sales", "order"), QueryModel.SNAPSHOT)
            val cart = QuerySchemaContext(AggregateType("sales", "cart"), QueryModel.SNAPSHOT)

            SystemQuerySchemaSource.load(order).single()
                .assert().isEqualTo(SystemQuerySchemaSource.load(cart).single())
        }
    }

    @Test
    fun `snapshot system declaration should match the snapshot record`() {
        val fields = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT).fields

        fields.keys.map(QueryField::path).assert().containsExactly(
            SnapshotFields.TENANT_ID,
            SnapshotFields.AGGREGATE_ID,
            SnapshotFields.VERSION,
            SnapshotFields.OWNER_ID,
            SnapshotFields.SPACE_ID,
            SnapshotFields.DELETED,
            SnapshotFields.TAGS,
            SnapshotFields.FIRST_EVENT_TIME,
            SnapshotFields.LAST_EVENT_TIME,
            SnapshotFields.OPERATOR,
            SnapshotFields.FIRST_OPERATOR,
            SnapshotFields.SCHEDULES,
            SnapshotFields.STATE,
        )
        fields.forEach { (field, declaration) ->
            declaration.required.assert().describedAs(field.path).isEqualTo(DeclarationValue.Set(true))
            val nullable = field.path in setOf(
                SnapshotFields.OWNER_ID,
                SnapshotFields.SPACE_ID,
                SnapshotFields.OPERATOR,
                SnapshotFields.FIRST_OPERATOR,
            )
            declaration.nullable.assert().describedAs(field.path).isEqualTo(DeclarationValue.Set(nullable))
        }
    }

    @Test
    fun `snapshot state shape remains unknown until model declarations arrive`() {
        val state = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .fields.getValue(QueryField("state"))

        state.valueTypes.assert().isEqualTo(DeclarationValue.Unset)
        state.kind.assert().isEqualTo(DeclarationValue.Unset)
        state.title.assert().isEqualTo(DeclarationValue.Unset)
        state.description.assert().isEqualTo(DeclarationValue.Unset)
        state.additionalProperties.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `snapshot tags should be a dynamic single object`() {
        val tags = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)
            .fields.getValue(QueryField("tags"))

        tags.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
        tags.items.assert().isEqualTo(DeclarationValue.Unset)
        tags.required.assert().isEqualTo(DeclarationValue.Set(true))
        tags.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        tags.additionalProperties.valueOr(null)!!.items.valueOr(null)!!.valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
    }

    @Test
    fun `snapshot system time fields should be millisecond epochs`() {
        val fields = SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT).fields
        val epoch = DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS))

        listOf("firstEventTime", "lastEventTime").forEach { field ->
            fields.getValue(QueryField(field)).semanticType.assert().isEqualTo(epoch)
            fields.getValue(QueryField(field)).valueTypes
                .assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        }
    }
}
