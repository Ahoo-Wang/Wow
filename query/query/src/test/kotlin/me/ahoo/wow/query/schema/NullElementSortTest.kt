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
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A storage that does not see a `null` element refuses a sort by an array that may hold one, and says so. */
class NullElementSortTest {
    /** Items the schema lets be `null`: a scalar's default. */
    private val nullableItems = arrayFixture(
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))
    )

    private fun schema(storage: StorageSupport): QueryModelSchema {
        val bound = boundSchemaFixture(
            objectFixture(
                "aggregateId" to scalarFixture(),
                "tenantId" to scalarFixture(),
                "tags" to nullableItems,
                "codes" to arrayFixture(scalarFixture()),
                "name" to scalarFixture(),
            ),
        )
        return QueryModelSchema(bound.model, bound.capabilities, bound.definition, bound.bindings, storage = storage)
    }

    private val blind = schema(StorageSupport(nullElementSort = SupportMode.NONE))

    @Test
    fun `only an array whose items may be null may hold a null element`() {
        nullableItems.mayHoldNullElement().assert().isTrue()
        arrayFixture(scalarFixture()).mayHoldNullElement().assert().isFalse()
        scalarFixture().mayHoldNullElement().assert().isFalse()
        QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(QueryValueSchema(QueryValueKind.NULL), nullableItems)
        )
            .mayHoldNullElement().assert().isTrue()
    }

    @Test
    fun `a storage blind to a null element sorts every other value`() {
        blind.storage.sorts(nullableItems).assert().isFalse()
        blind.storage.sorts(arrayFixture(scalarFixture())).assert().isTrue()
        blind.storage.sorts(scalarFixture()).assert().isTrue()
        StorageSupport.NATIVE.sorts(nullableItems).assert().isTrue()
        assertThrows<IllegalArgumentException> { StorageSupport(nullElementSort = SupportMode.RESIDUAL) }
        StorageSupport(nullElementSort = SupportMode.NONE).assert().isNotEqualTo(StorageSupport.NATIVE)
        blind.storage.toString().assert().contains("nullElementSort=NONE")
    }

    @Test
    fun `the descriptor offers no sort the admission refuses`() {
        fun sort(schema: QueryModelSchema) = schema.describe(null, null)
            .fields.associate { it.path to it.sort }
        val blindSorts = sort(blind)
        blindSorts.getValue("tags").paged.assert().isFalse()
        blindSorts.getValue("tags").cursor.assert().isFalse()
        blindSorts.getValue("tags").indexed.assert().isNull()
        blindSorts.getValue("codes").paged.assert().isTrue()
        sort(schema(StorageSupport.NATIVE)).getValue("tags").paged.assert().isTrue()
    }

    @Test
    fun `admission refuses the sort naming the field and why`() {
        val tags = QueryField("tags")
        val list = ListQuery(MatchAllFilter, sort = listOf(Sort(tags, Sort.Direction.ASC)))
        val refused = assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.list(list, blind) }
        val violation = refused.violation as QueryViolation.StorageUnsupported
        violation.field.assert().isEqualTo(tags)
        violation.feature.assert().contains("null element")
        val cursor = CursorQuery(MatchAllFilter, sort = listOf(Sort(tags, Sort.Direction.DESC)), size = 1)
        assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.cursor(cursor, blind) }
        QueryAdmission.Trusted.list(
            ListQuery(MatchAllFilter, sort = listOf(Sort(QueryField("codes"), Sort.Direction.ASC))),
            blind
        )
            .query.sort.assert().isNotEmpty()
        QueryAdmission.Trusted.list(list, schema(StorageSupport.NATIVE)).query.sort.assert().isNotEmpty()
    }
}
