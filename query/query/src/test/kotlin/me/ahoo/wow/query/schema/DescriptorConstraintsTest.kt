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
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.descriptor.ConstraintDescriptor
import org.junit.jupiter.api.Test

/** The combination rules a storage's support adds to the descriptor. */
class DescriptorConstraintsTest {
    private fun schema(storage: StorageSupport): QueryModelSchema {
        val bound = boundSchemaFixture(
            objectFixture(
                "aggregateId" to scalarFixture(),
                "tags" to arrayFixture(scalarFixture()),
                "codes" to arrayFixture(scalarFixture()),
                "note" to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
                "name" to scalarFixture(),
            ),
        )
        return QueryModelSchema(
            bound.model,
            bound.capabilities,
            bound.definition,
            bound.bindings,
            storage = storage,
        )
    }

    @Test
    fun `a storage's limits become constraints`() {
        val constraints = schema(
            StorageSupport(
                parallelArraySort = SupportMode.NONE,
                absentValues = AbsentValues.AS_MISSING,
                arrayEquality = SupportMode.NONE,
            ),
        ).describe(null, null).constraints.associateBy { it.type }
        constraints.getValue(ConstraintDescriptor.PARALLEL_ARRAY_SORT).fields.assert().containsExactly("codes", "tags")
        constraints.getValue(ConstraintDescriptor.NULL_OR_EMPTY_AS_MISSING).fields.assert()
            .containsExactly("codes", "note", "tags")
        constraints.assert().containsKey(ConstraintDescriptor.ARRAY_EQUALITY)
    }

    @Test
    fun `a storage without those limits adds none`() {
        schema(StorageSupport.NATIVE).describe(null, null).constraints.map { it.type }.assert()
            .doesNotContain(
                ConstraintDescriptor.PARALLEL_ARRAY_SORT,
                ConstraintDescriptor.NULL_OR_EMPTY_AS_MISSING,
                ConstraintDescriptor.ARRAY_EQUALITY,
            )
    }

    @Test
    fun `a custom model without a profile describes no identity, roles or default scope`() {
        val bound = boundSchemaFixture(
            objectFixture("tenantId" to scalarFixture(), "value" to scalarFixture(QueryValueType.INTEGER)),
            model = QueryModel("CUSTOM"),
        )
        val descriptor = bound.describe(QueryBudget("HTTP", maxListSize = 0, maxPageSize = 0), 10)
        descriptor.record.run {
            identity.assert().isEmpty()
            defaultScope.assert().isNull()
            paging.assert().doesNotContain(me.ahoo.wow.query.descriptor.PagingMode.CURSOR)
        }
        descriptor.fields.associateBy { it.path }.run {
            getValue("tenantId").role.assert().isEqualTo("TENANT_ID")
            getValue("value").role.assert().isNull()
        }
        descriptor.limits.maxListSize.assert().isNull()
        descriptor.limits.defaultListSize.assert().isNull()
        descriptor.analysis.firstLastOrderBy.assert().isNull()
    }
}
