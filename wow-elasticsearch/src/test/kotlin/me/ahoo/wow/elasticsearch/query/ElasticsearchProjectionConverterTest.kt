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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.query.ElasticsearchProjectionConverter.toSourceFilter
import me.ahoo.wow.query.dsl.projection
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import org.junit.jupiter.api.Test

class ElasticsearchProjectionConverterTest {
    private val schema = QueryModelSchema(
        model = QueryModel.SNAPSHOT,
        capabilities = emptySet(),
        fields = mapOf(
            QueryField("state") to projectionFieldSchema(QueryField("document")),
            QueryField("state.name") to projectionFieldSchema(QueryField("document.name")),
        ),
    )

    @Test
    fun `should convert projection to SourceFilter`() {
        val projection = projection {
            include("field1")
            exclude("field2")
        }

        val sourceFilter = projection.toSourceFilter(null)
        sourceFilter.includes().assert().containsExactly("field1", "field1.*")
        sourceFilter.excludes().assert().containsExactly("field2", "field2.*")
    }

    @Test
    fun `should compile included logical subtrees to source filters`() {
        val include = listOf(QueryField("state"), QueryField("state.name"))
        val projection = Projection(include = include)

        ElasticsearchProjectionConverter.convert(projection, schema).includes().assert().containsExactly(
            "document",
            "document.*",
            "document.name",
            "document.name.*",
        )
        projection.include.assert().isSameAs(include)
    }

    @Test
    fun `should compile excluded logical subtrees to source filters`() {
        ElasticsearchProjectionConverter.convert(
            Projection(exclude = listOf(QueryField("state"), QueryField("state.name"))),
            schema,
        ).excludes().assert().containsExactly(
            "document",
            "document.*",
            "document.name",
            "document.name.*",
        )
    }

    private fun projectionFieldSchema(projectionField: QueryField) = QueryFieldSchema(
        title = null,
        description = null,
        enumValues = null,
        valueTypes = emptySet(),
        nullable = true,
        required = false,
        cardinality = QueryCardinality.SINGLE,
        semanticType = null,
        dynamicChildren = false,
        bindings = emptyMap(),
        projectionField = projectionField,
        rewriteMode = QueryRewriteMode.NONE,
    )
}
