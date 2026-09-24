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
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuerySchemaBindingsTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "name" to scalarFixture(),
            "orders" to arrayFixture(objectFixture("price" to scalarFixture(QueryValueType.INTEGER))),
        )
    )

    @Test
    fun `scoped physical field stays inside its physical element container`() {
        schema.scopedPhysicalField(
            QueryField("price"),
            QueryCapability.RANGE,
            QueryField("orders"),
            QueryField("native.orders"),
        ).assert().isEqualTo(QueryField("native.orders.price"))
        schema.scopedPhysicalField(QueryField("name"), QueryCapability.EXACT_MATCH, null, null)
            .assert().isEqualTo(QueryField("native.name"))
        assertThrows<QuerySchemaValidationException> {
            schema.scopedPhysicalField(
                QueryField("price"),
                QueryCapability.RANGE,
                QueryField("orders"),
                QueryField("other")
            )
        }
    }

    @Test
    fun `physical cursor sort maps each field and rejects shared physical fields`() {
        val sort = listOf(Sort(QueryField("name"), Sort.Direction.DESC))
        schema.physicalCursorSort(sort).assert().containsExactly(Sort(QueryField("native.name"), Sort.Direction.DESC))

        val base = boundSchemaFixture(objectFixture("name" to scalarFixture(), "alias" to scalarFixture()))
        val shared = QueryPathTemplate(listOf(QueryPathSegment.Property("native"), QueryPathSegment.Property("name")))
        val aliased = QueryModelSchema(
            base.model,
            base.capabilities,
            base.definition,
            base.bindings.mapValues { (path, bindings) ->
                if (path.segments == listOf(QueryPathSegment.Property("alias"))) {
                    QueryValueBindings(
                        bindings.bindings.mapValues { QueryFieldBindingTemplate(shared, null) },
                        shared,
                        path
                    )
                } else {
                    bindings
                }
            },
        )
        assertThrows<QuerySchemaValidationException> {
            aliased.physicalCursorSort(
                listOf(Sort(QueryField("name"), Sort.Direction.ASC), Sort(QueryField("alias"), Sort.Direction.ASC))
            )
        }
    }

    @Test
    fun `array branches are found through nested unions`() {
        val array = arrayFixture(scalarFixture())
        array.hasArrayBranch().assert().isTrue()
        scalarFixture().hasArrayBranch().assert().isFalse()
        val nested = QueryValueSchema(
            QueryValueKind.UNION,
            alternatives = listOf(
                scalarFixture(),
                QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(scalarFixture(QueryValueType.INTEGER), array)
                ),
            ),
        )
        nested.hasArrayBranch().assert().isTrue()
    }
}
