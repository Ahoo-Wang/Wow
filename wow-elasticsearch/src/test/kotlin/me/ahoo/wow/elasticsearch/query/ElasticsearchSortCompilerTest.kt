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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.dsl.sort
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchSortCompilerTest {
    private val schema = nativeSchema(
        model = QueryModel.EVENT_STREAM,
        capabilities = emptySet(),
        fields = mapOf(
            QueryField("name") to sortFieldSchema(QueryField("body.name")),
            QueryField("identity") to sortFieldSchema(QueryField("id")),
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
    fun `should resolve logical sort fields with the same missing order as cursor`() {
        val actual = ElasticsearchSortCompiler.compile(
            sort {
                "name".asc()
                "name".desc()
            },
            schema,
        ).map { it.field() }

        actual.map { it.field() }.assert().containsExactly("body.name", "body.name")
        actual.map { it.order() }.assert().containsExactly(SortOrder.Asc, SortOrder.Desc)
        actual.map { requireNotNull(it.missing()).stringValue() }.assert().containsExactly("_first", "_last")
        actual.forEach { requireNotNull(it.nested()).path().assert().isEqualTo("body") }
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

    @Test
    fun `cursor sort should use its own physical binding`() {
        val logical = QueryField("rank")
        val schema = nativeSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                logical to fieldSchema(
                    QueryCapability.SORT to QueryField("ordinary.rank"),
                    QueryCapability.CURSOR_SORT to QueryField("cursor.rank"),
                ),
            ),
        )

        ElasticsearchSortCompiler.compileCursor(listOf(Sort(logical, Sort.Direction.ASC)), schema)
            .single().field().field().assert().isEqualTo("cursor.rank")
    }

    @Test
    fun `cursor sort should reject missing capability special metadata and duplicate physical fields`() {
        val unsupported = QueryField("unsupported")
        val special = QueryField("special")
        val first = QueryField("first")
        val second = QueryField("second")
        val schema = nativeSchema(
            model = QueryModel.SNAPSHOT,
            capabilities = emptySet(),
            fields = mapOf(
                unsupported to fieldSchema(QueryCapability.SORT to QueryField("ordinary")),
                special to fieldSchema(QueryCapability.CURSOR_SORT to QueryField("_score")),
                first to fieldSchema(QueryCapability.CURSOR_SORT to QueryField("shared")),
                second to fieldSchema(QueryCapability.CURSOR_SORT to QueryField("shared")),
            ),
        )

        listOf(
            listOf(Sort(unsupported, Sort.Direction.ASC)),
            listOf(Sort(special, Sort.Direction.ASC)),
            listOf(Sort(first, Sort.Direction.ASC), Sort(second, Sort.Direction.DESC)),
        ).forEach { sort ->
            assertThrows<QuerySchemaValidationException> {
                ElasticsearchSortCompiler.compileCursor(sort, schema)
            }
        }
    }

    private fun sortFieldSchema(physical: QueryField) = nativeBindings(
        physical,
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
    )

    private fun fieldSchema(vararg bindings: Pair<QueryCapability, QueryField>) =
        me.ahoo.wow.query.schema.QueryValueBindings(
            bindings.associate { (capability, physical) ->
                capability to me.ahoo.wow.query.schema.QueryFieldBindingTemplate(physical.path.testPath(), null)
            }
        )
}
