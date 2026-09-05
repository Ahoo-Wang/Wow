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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.SortOrder
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.dsl.sort
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

class ElasticsearchSortCompilerTest {
    private val schema = QueryModelSchema(
        model = QueryModel.EVENT_STREAM,
        capabilities = emptySet(),
        fields = mapOf(
            QueryField("name") to sortFieldSchema(QueryField("document.name"), QueryField("body.name")),
            QueryField("identity") to sortFieldSchema(QueryField("document.identity"), QueryField("id")),
        ),
    )

    @Test
    fun `should compile Sort to SortOptions`() {
        val sort = sort {
            "field1".asc()
            "field2".desc()
        }

        val actual = ElasticsearchSortCompiler.compilePhysical(sort)

        actual.first().let {
            it.field().field().assert().isEqualTo("field1")
            it.field().order().assert().isEqualTo(SortOrder.Asc)
            it.field().missing().assert().isNull()
        }
        actual.last().let {
            it.field().field().assert().isEqualTo("field2")
            it.field().order().assert().isEqualTo(SortOrder.Desc)
            it.field().missing().assert().isNull()
        }
    }

    @Test
    fun `should compile empty Sort to empty SortOptions`() {
        val sort = emptyList<Sort>()

        val actual = ElasticsearchSortCompiler.compilePhysical(sort)

        actual.isEmpty().assert().isTrue()
        ElasticsearchSortCompiler.compile(sort, schema).assert().isEmpty()
    }

    @Test
    fun `should resolve logical sort fields without setting ordinary missing order`() {
        val actual = ElasticsearchSortCompiler.compile(
            sort {
                "name".asc()
                "name".desc()
            },
            schema,
        ).map { it.field() }

        actual.map { it.field() }.assert().containsExactly("body.name", "body.name")
        actual.map { it.order() }.assert().containsExactly(SortOrder.Asc, SortOrder.Desc)
        actual.forEach {
            requireNotNull(it.nested()).path().assert().isEqualTo("body")
            it.missing().assert().isNull()
        }
    }

    @Test
    fun `event cursor should preserve physical sorts nested context and missing order`() {
        val actual = ElasticsearchSortCompiler.compileCursor(
            sort {
                "name".asc()
                "identity".desc()
            },
            schema,
        ).map { it.field() }
        actual.map { it.field() }.assert().containsExactly("body.name", "id")
        actual.map { it.order() }.assert().containsExactly(SortOrder.Asc, SortOrder.Desc)
        actual.map { requireNotNull(it.missing()).stringValue() }.assert().containsExactly("_first", "_last")
        requireNotNull(actual.first().nested()).path().assert().isEqualTo("body")
        actual.last().nested().assert().isNull()
    }

    @Test
    fun `should add nested context to event body sort`() {
        val actual = ElasticsearchSortCompiler.compilePhysical(
            sort { "${MessageRecords.BODY}.name".asc() },
        ).single().field()

        requireNotNull(actual.nested()).path().assert().isEqualTo(MessageRecords.BODY)
        actual.missing().assert().isNull()
    }

    private fun sortFieldSchema(resolved: QueryField, physical: QueryField) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = emptySet(),
        nullable = true,
        required = false,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = false,
        bindings = mapOf(QueryCapability.SORT to QueryFieldBinding(resolved, physical, null)),
        rewriteMode = QueryRewriteMode.REQUIRED,
    )
}
