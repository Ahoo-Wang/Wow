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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.ElementMatchExpression
import me.ahoo.wow.api.query.expression.FullTextExpression
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.NativeExpression
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.RelativeTimeOperation
import me.ahoo.wow.api.query.expression.StringComparisonMode
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.query.schema.QueryBackendFieldPath
import me.ahoo.wow.query.schema.QueryBackendId
import me.ahoo.wow.query.schema.QueryCapabilityBinding
import me.ahoo.wow.query.schema.QueryFieldUsage
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchQueryPlanCompilerTest {
    private val title = PortableQueryDataset.TITLE
    private val schema = PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT).let { source ->
        val titleSchema = source.fields.getValue(title)
        source.withField(
            titleSchema.copy(
                sortable = true,
                bindings = setOf(
                    QueryCapabilityBinding(ES_BACKEND, QueryFieldUsage.EXACT, QueryBackendFieldPath("title.keyword")),
                    QueryCapabilityBinding(ES_BACKEND, QueryFieldUsage.SEARCH, QueryBackendFieldPath("title.search")),
                    QueryCapabilityBinding(ES_BACKEND, QueryFieldUsage.SORT, QueryBackendFieldPath("title.keyword")),
                ),
            ),
        )
    }
    private val binding = ElasticsearchQueryFieldBinding.bind(schema)
    private val compiler = ElasticsearchQueryPlanCompiler(binding, ElasticsearchNativeQueryTemplateRegistry())

    @Test
    fun `relative time is rejected before native template compilation`() {
        var nativeCalls = 0
        val guardedCompiler = ElasticsearchQueryPlanCompiler(
            binding,
            ElasticsearchNativeQueryTemplateRegistry(
                mapOf(
                    "must-not-run" to ElasticsearchNativeQueryTemplate {
                        nativeCalls++
                        Query.of { query -> query.matchAll { it } }
                    }
                )
            )
        )

        assertThrows<IllegalStateException> {
            guardedCompiler.query(
                RelativeTimeExpression(
                    PortableQueryDataset.CREATED_AT.value,
                    RelativeTimeOperation.TODAY
                )
            )
        }
        nativeCalls.assert().isZero()
    }

    @Test
    fun `null uses nearest object presence metadata`() {
        val query = compiler.query(predicate("profile.city", PortableOperator.NULL))

        assertTerm(query, "profile.__wow_query.null", "city")
    }

    @Test
    fun `nested null uses presence metadata inside the same element`() {
        val query = compiler.query(
            ElementMatchExpression(
                PortableQueryDataset.ITEMS,
                predicate("sku", PortableOperator.NULL),
            ),
        )

        query.isNested.assert().isTrue()
        query.nested().path().assert().isEqualTo("items")
        assertTerm(query.nested().query(), "items.__wow_query.null", "sku")
    }

    @Test
    fun `sparse deep leaf derives source parent metadata and keeps custom exact binding`() {
        val sparse = LogicalField("profile.address.city")
        val sparseSchema = schema.withField(
            me.ahoo.wow.query.schema.QueryFieldSchema(
                sparse,
                me.ahoo.wow.query.schema.QueryFieldValueKind.STRING,
                nullable = true,
                bindings = setOf(
                    QueryCapabilityBinding(
                        ES_BACKEND,
                        QueryFieldUsage.EXACT,
                        QueryBackendFieldPath("profile.address.city.exact"),
                    ),
                ),
            ),
        )
        val sparseCompiler = ElasticsearchQueryPlanCompiler(
            ElasticsearchQueryFieldBinding.bind(sparseSchema),
            ElasticsearchNativeQueryTemplateRegistry(),
        )

        assertTerm(
            sparseCompiler.query(predicate(sparse.value, PortableOperator.NULL)),
            "profile.address.__wow_query.null",
            "city",
        )
        assertTerm(
            sparseCompiler.query(
                PredicateExpression(sparse, PortableOperator.EQ, listOf(QueryValue.StringValue("杭州"))),
            ),
            "profile.address.city.exact",
            "杭州",
        )
    }

    @Test
    fun `explicit source-aligned nested binding remains expressible`() {
        val itemsSchema = schema.fields.getValue(PortableQueryDataset.ITEMS).copy(
            bindings = setOf(
                QueryCapabilityBinding(ES_BACKEND, QueryFieldUsage.NESTED, QueryBackendFieldPath("items")),
            ),
        )
        val itemSkuSchema = schema.fields.getValue(PortableQueryDataset.ITEM_SKU).copy(
            bindings = setOf(
                QueryCapabilityBinding(ES_BACKEND, QueryFieldUsage.EXACT, QueryBackendFieldPath("items.sku.exact")),
            ),
        )
        val customCompiler = ElasticsearchQueryPlanCompiler(
            ElasticsearchQueryFieldBinding.bind(schema.withField(itemsSchema).withField(itemSkuSchema)),
            ElasticsearchNativeQueryTemplateRegistry(),
        )

        val query = customCompiler.query(
            ElementMatchExpression(
                PortableQueryDataset.ITEMS,
                PredicateExpression(
                    LogicalField("sku"),
                    PortableOperator.EQ,
                    listOf(QueryValue.StringValue("A")),
                ),
            ),
        )

        query.nested().path().assert().isEqualTo("items")
        assertTerm(query.nested().query(), "items.sku.exact", "A")
    }

    @Test
    fun `not equal requires presence and excludes exact value`() {
        val query = compiler.query(
            PredicateExpression(title, PortableOperator.NE, listOf(QueryValue.StringValue("alpha"))),
        )

        val bool = query.bool()
        bool.filter().size.assert().isEqualTo(1)
        assertTerm(bool.filter().single(), "__wow_query.present", "title")
        bool.mustNot().size.assert().isEqualTo(1)
        assertTerm(bool.mustNot().single(), "title.keyword", "alpha")
    }

    @Test
    fun `exists false excludes presence term instead of using native exists`() {
        val query = compiler.query(
            PredicateExpression(
                PortableQueryDataset.OPTIONAL_TEXT,
                PortableOperator.EXISTS,
                listOf(QueryValue.BooleanValue(false)),
            ),
        )

        query.bool().mustNot().single().let { absent ->
            assertTerm(absent, "__wow_query.present", "optionalText")
            absent.isExists.assert().isFalse()
        }
    }

    @Test
    fun `literal string operations escape wildcard syntax and preserve comparison mode`() {
        val value = QueryValue.StringValue("a*b?c\\d")
        val sensitive = compiler.query(
            PredicateExpression(title, PortableOperator.CONTAINS, listOf(value), StringComparisonMode.CASE_SENSITIVE),
        ).wildcard()
        val insensitive = compiler.query(
            PredicateExpression(
                title,
                PortableOperator.ENDS_WITH,
                listOf(value),
                StringComparisonMode.CASE_INSENSITIVE,
            ),
        ).wildcard()

        sensitive.field().assert().isEqualTo("title.keyword")
        sensitive.value().assert().isEqualTo("*a\\*b\\?c\\\\d*")
        sensitive.caseInsensitive().assert().isFalse()
        insensitive.value().assert().isEqualTo("*a\\*b\\?c\\\\d")
        insensitive.caseInsensitive().assert().isTrue()
    }

    @Test
    fun `full text uses only declared search bindings`() {
        val query = compiler.query(
            FullTextExpression(
                QueryCapabilityId(ElasticsearchQueryBackendFactory.FULL_TEXT_CAPABILITY),
                "search words",
                setOf(title),
            ),
        )

        query.multiMatch().fields().assert().isEqualTo(listOf("title.search"))
        query.multiMatch().query().assert().isEqualTo("search words")
    }

    @Test
    fun `native query is built only by registered typed template`() {
        val registry = ElasticsearchNativeQueryTemplateRegistry(
            mapOf(
                "by-rank" to ElasticsearchNativeQueryTemplate { parameters ->
                    Query.of { query ->
                        query.term { term ->
                            term.field("rank").value((parameters.getValue("rank") as QueryValue.IntegerValue).value)
                        }
                    }
                },
            ),
        )
        val nativeCompiler = ElasticsearchQueryPlanCompiler(binding, registry)

        val query = nativeCompiler.query(
            NativeExpression(
                capabilityId = QueryCapabilityId(ElasticsearchQueryBackendFactory.NATIVE_CAPABILITY),
                backendId = ElasticsearchQueryBackendFactory.BACKEND_ID,
                templateId = "by-rank",
                parameters = mapOf("rank" to QueryValue.IntegerValue(2)),
                declaredFields = setOf(PortableQueryDataset.RANK),
            ),
        )

        query.term().field().assert().isEqualTo("rank")
        query.term().value().longValue().assert().isEqualTo(2L)
    }

    @Test
    fun `native backend mismatch fails before query construction`() {
        val error = assertThrows<QueryException> {
            compiler.query(
                NativeExpression(
                    capabilityId = QueryCapabilityId(ElasticsearchQueryBackendFactory.NATIVE_CAPABILITY),
                    backendId = "mongo",
                    templateId = "missing",
                    parameters = emptyMap(),
                    declaredFields = setOf(title),
                ),
            )
        }

        error.code.assert().isEqualTo(QueryErrorCode.UNSUPPORTED_CAPABILITY)
    }

    private fun predicate(field: String, operator: PortableOperator): PredicateExpression = PredicateExpression(
        LogicalField(field),
        operator,
        emptyList(),
    )

    private fun assertTerm(query: Query, field: String, value: String) {
        query.isTerm.assert().isTrue()
        query.term().field().assert().isEqualTo(field)
        query.term().value().isString.assert().isTrue()
        query.term().value().stringValue().assert().isEqualTo(value)
    }

    private companion object {
        val ES_BACKEND = QueryBackendId("elasticsearch")
    }
}
