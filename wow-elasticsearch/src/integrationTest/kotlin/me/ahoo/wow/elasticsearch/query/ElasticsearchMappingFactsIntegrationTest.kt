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

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.ids
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.query.schema.ElasticsearchQuerySchemaAdapter
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.StringReader

class ElasticsearchMappingFactsIntegrationTest {
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    private val string = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING))

    @Test
    fun `nullable nested carriers retain native element matches`() {
        val item = QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf("code" to string))
        val items = QueryValueSchema(QueryValueKind.ARRAY, items = QueryValueSchema(
            QueryValueKind.UNION, alternatives = listOf(item, QueryValueSchema(QueryValueKind.NULL)),
        ))
        withIndex("""{"properties":{"items":{"type":"nested","properties":{"code":{"type":"keyword"}}}}}""",
            mapOf("items" to items), """{"items":[null,{"code":"A"}]}""") { backend, schema ->
            val filter = ElementMatchFilter(QueryField("items"), EqualFilter(QueryField("code"), JsonNodeFactory.instance.stringNode("A")))
            validateQuery(filter, schema)
            backend.count(filter, schema).block().assert().isEqualTo(1L)
        }
    }

    @Test
    fun `source disabled keeps count and summaries but rejects reads`() {
        withIndex("""{"_source":{"enabled":false},"properties":{"code":{"type":"keyword"}}}""",
            mapOf("code" to string), """{"code":"abc"}""") { backend, schema ->
            val native = backend.elasticsearchClient.search({ it.index(backend.indexName) }, ObjectNode::class.java).block()!!
            native.hits().hits().assert().hasSize(1)
            native.hits().hits().single().source().assert().isNull()
            backend.count(MatchAllFilter, schema).block().assert().isEqualTo(1L)
            backend.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("total"))), schema)
                .single().block()!!.path("total").longValue().assert().isEqualTo(1L)
            assertThrows<QuerySchemaValidationException> { backend.list(ListQuery(MatchAllFilter), schema).collectList().block() }
            assertThrows<QuerySchemaValidationException> { backend.list(ListQuery(MatchAllFilter,
                projection = Projection(include = listOf(QueryField("code"))), limit = 1), schema).collectList().block() }
        }
    }

    @Test
    fun `source pruning preserves available alias and multifield projection`() {
        withIndex("""{"_source":{"excludes":["obj.secret"]},"properties":{"obj":{"properties":{"code":{"type":"keyword","fields":{"exact":{"type":"keyword"}}},"secret":{"type":"keyword"}}},"alias":{"type":"alias","path":"obj.code"}}}""",
            mapOf("obj" to QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf("code" to string, "secret" to string)),
                "alias" to string), """{"obj":{"code":"abc","secret":"hidden"}}""") { backend, schema ->
            val node = backend.list(ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("alias"))), limit = 1), schema).single().block()!!
            node.path("obj").path("code").asString().assert().isEqualTo("abc")
            node.path("obj").has("secret").assert().isFalse()
            assertThrows<QuerySchemaValidationException> { backend.list(ListQuery(MatchAllFilter,
                projection = Projection(include = listOf(QueryField("obj"))), limit = 1), schema).collectList().block() }
        }
    }

    @Test
    fun `disabled root and object indexing cannot be queried but retain source`() {
        listOf(
            """{"enabled":false,"properties":{"obj":{"properties":{"code":{"type":"keyword"}}}}}""",
            """{"properties":{"obj":{"enabled":false,"properties":{"code":{"type":"keyword"}}}}}""",
        ).forEach { mapping ->
            withIndex(mapping, mapOf("obj" to QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf("code" to string))),
                """{"obj":{"code":"A"}}""") { backend, schema ->
                backend.elasticsearchClient.count { request -> request.index(backend.indexName).query(Query.of { it.term { it.field("obj.code").value("A") } }) }
                    .block()!!.count().assert().isZero()
                backend.list(ListQuery(MatchAllFilter, limit = 1), schema).single().block()!!.path("obj").path("code").asString().assert().isEqualTo("A")
                assertThrows<QuerySchemaValidationException> {
                    backend.count(EqualFilter(QueryField("obj.code"), JsonNodeFactory.instance.stringNode("A")), schema).block()
                }
            }
        }
    }

    @Test
    fun `lossless sibling preserves case sensitive prefix despite native normalizer`() {
        withIndex("""{"properties":{"code":{"type":"keyword","normalizer":"lowercase","fields":{"exact":{"type":"keyword"}}},"normalized":{"type":"keyword","normalizer":"lowercase"}}}""",
            mapOf("code" to string, "normalized" to string), """{"code":"abc","normalized":"abc"}""") { backend, schema ->
            backend.elasticsearchClient.count { request -> request.index(backend.indexName).query(Query.of { it.prefix { it.field("code").value("A").caseInsensitive(false) } }) }
                .block()!!.count().assert().isEqualTo(1L)
            backend.count(StartsWithFilter(QueryField("code"), "A"), schema).block().assert().isEqualTo(0L)
            backend.count(StartsWithFilter(QueryField("code"), "a"), schema).block().assert().isEqualTo(1L)
            assertThrows<QuerySchemaValidationException> { backend.count(StartsWithFilter(QueryField("normalized"), "A"), schema).block() }
        }
    }

    private fun withIndex(mapping: String, properties: Map<String, QueryValueSchema>, document: String,
                          verify: (AbstractElasticsearchQueryBackend, QueryModelSchema) -> Unit) {
        val client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        val name = "wow.${elasticsearch.index("facts")}.snapshot"
        client.indices().create { it.index(name).mappings(TypeMapping.of { it.withJson(StringReader(mapping)) }) }.block()
        try {
            client.index { it.index(name).id("1").document(JsonSerializer.readValue(document, ObjectNode::class.java)).refresh(Refresh.True) }.block()
            val schema = ElasticsearchQuerySchemaAdapter(name, ElasticsearchIndexMappingResolver(client))
                .resolve(LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = properties))).block()!!
            val backend = object : AbstractElasticsearchQueryBackend() {
                override val elasticsearchClient: ReactiveElasticsearchClient = client
                override val indexName: String = name
                override val namedAggregate = me.ahoo.wow.modeling.MaterializedNamedAggregate("test", "mapping-facts")
                override val filterCompiler = object : AbstractElasticsearchFilterCompiler() {
                    override fun aggregateIdEqual(value: String): Query = ids { it.values(value) }
                    override fun aggregateIdIn(values: List<String>): Query = ids { it.values(values) }
                }
            }
            verify(backend, schema)
        } finally {
            client.indices().delete { it.index(name) }.block()
        }
    }
}
