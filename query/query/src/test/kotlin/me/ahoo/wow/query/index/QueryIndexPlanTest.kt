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

package me.ahoo.wow.query.index

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.gatewaySchema
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryIndexPlanTest {
    private val key = QueryIndexPlan.Key(
        QueryField("state.status"),
        QueryField("state.status"),
        setOf(QueryStorageType.of("text")),
        QueryCardinality.SINGLE,
    )

    private val schema = gatewaySchema(
        fields = mapOf(
            QueryField("state.status") to QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
            QueryField("state.tags") to QueryValueSchema(
                QueryValueKind.ARRAY,
                items = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING)),
            ),
        ),
    )

    @Test
    fun `the core should plan each declared key where the storage keeps it`() {
        val plan = QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("ix_status", listOf("state.status"))))
        plan.logical.assert().isSameAs(schema.definition)
        plan.indexes.assert().containsExactly(
            QueryIndexPlan.Index(
                "ix_status",
                listOf(
                    QueryIndexPlan.Key(
                        QueryField("state.status"),
                        QueryField("state.status"),
                        null,
                        QueryCardinality.SINGLE
                    )
                ),
            ),
        )
        QueryIndexPlan.of(schema, emptyList()).indexes.assert().isEmpty()
    }

    @Test
    fun `the core should refuse a key the model does not know or the storage cannot index, naming index and key`() {
        assertThrows<IllegalArgumentException> {
            QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("ix_none", listOf("state.none"))))
        }.message.assert().isEqualTo("Index [ix_none] has the key [state.none], which the query model does not know.")
        assertThrows<IllegalArgumentException> {
            QueryIndexPlan.of(schema, listOf(QueryIndexDeclaration("ix_tags", listOf("state.tags"))))
        }.message.assert()
            .isEqualTo("Index [ix_tags] has the key [state.tags], which the storage neither matches, ranges nor sorts.")
    }

    @Test
    fun `a planned index and its keys should be values`() {
        val same = QueryIndexPlan.Key(
            QueryField("state.status"),
            QueryField("state.status"),
            setOf(QueryStorageType.of("text")),
            QueryCardinality.SINGLE
        )
        key.assert().isEqualTo(
            same
        ).isNotEqualTo(QueryIndexPlan.Key(QueryField("state.a"), QueryField("state.a"), null, null))
        key.hashCode().assert().isEqualTo(same.hashCode())
        key.toString().assert().contains("field=state.status", "cardinality=SINGLE")
        val index = QueryIndexPlan.Index("ix", listOf(key))
        index.assert().isEqualTo(
            QueryIndexPlan.Index("ix", listOf(same))
        ).isNotEqualTo(QueryIndexPlan.Index("iy", listOf(key)))
        index.hashCode().assert().isEqualTo(QueryIndexPlan.Index("ix", listOf(same)).hashCode())
        index.toString().assert().startsWith("Index(name=ix, keys=[Key(")
    }
}
