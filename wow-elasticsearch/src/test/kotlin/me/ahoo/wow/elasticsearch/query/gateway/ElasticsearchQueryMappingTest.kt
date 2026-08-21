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

package me.ahoo.wow.elasticsearch.query.gateway

import co.elastic.clients.elasticsearch._types.mapping.Property
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryValueKind
import org.junit.jupiter.api.Test

class ElasticsearchQueryMappingTest {
    @Test
    fun `should reject physical types that contradict the logical schema`() {
        property { it.keyword { type -> type } }.compatibleWith(field(QueryValueKind.INTEGER)).assert().isFalse()
        property { it.long_ { type -> type } }.compatibleWith(field(QueryValueKind.INTEGER)).assert().isTrue()
        property { it.integer { type -> type } }.compatibleWith(field(QueryValueKind.INTEGER)).assert().isTrue()
        property { it.double_ { type -> type } }.compatibleWith(field(QueryValueKind.DECIMAL)).assert().isTrue()
        property { it.boolean_ { type -> type } }.compatibleWith(field(QueryValueKind.DECIMAL)).assert().isFalse()
        property { it.text { type -> type } }.compatibleWith(field(QueryValueKind.STRING)).assert().isTrue()
        property { it.date { type -> type } }.compatibleWith(field(QueryValueKind.TIME)).assert().isTrue()
        property { it.binary { type -> type } }.compatibleWith(field(QueryValueKind.BINARY)).assert().isTrue()
        property { it.nested { type -> type } }.compatibleWith(field(QueryValueKind.OBJECT)).assert().isTrue()
        property { it.flattened { type -> type } }.compatibleWith(field(QueryValueKind.MAP)).assert().isTrue()
    }

    private fun field(kind: QueryValueKind): QueryFieldSchema = QueryFieldSchema(
        LogicalField("state.value"),
        kind,
        nullable = false,
        queryable = kind != QueryValueKind.OBJECT && kind != QueryValueKind.MAP,
        operators = if (kind == QueryValueKind.OBJECT || kind == QueryValueKind.MAP) {
            emptySet()
        } else {
            QueryFieldSchema.defaultOperators(kind, me.ahoo.wow.query.schema.QueryCollectionKind.NONE)
        }
    )

    private fun property(block: (Property.Builder) -> Unit): Property = Property.of { builder ->
        block(builder)
        builder
    }
}
