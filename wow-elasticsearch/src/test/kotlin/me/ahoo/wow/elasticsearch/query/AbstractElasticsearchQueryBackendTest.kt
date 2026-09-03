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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders.matchAll
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeResponse
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.CountResponse
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import co.elastic.clients.elasticsearch.indices.GetMappingResponse
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendFactory
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration

class AbstractElasticsearchQueryBackendTest {
    private val elasticsearchClient = mockk<ReactiveElasticsearchClient>()
    private val filterConverter = mockk<AbstractElasticsearchFilterConverter> {
        every { convert(any<me.ahoo.wow.api.query.FilterExpression>()) } returns matchAll { it }
    }
    private val queryBackend = TestElasticsearchQueryBackend(elasticsearchClient, filterConverter)
    private val schema = QueryModelSchema(
        model = QueryModel.SNAPSHOT,
        capabilities = emptySet(),
        fields = mapOf(
            QueryField("state") to projectionFieldSchema(QueryField("document")),
            QueryField("version") to sortFieldSchema(QueryField("version")),
            QueryField("aggregateId") to sortFieldSchema(QueryField("aggregateId")),
        ),
    )

    @Test
    fun `nested standard json should become independent object node without losing large numbers`() {
        val decimal = BigDecimal("1E+10000")
        val integer = BigInteger("123456789012345678901234567890")
        val embeddedNode = JsonNodeFactory.instance.objectNode()
            .set("values", JsonNodeFactory.instance.arrayNode().add("text").add(1))
        val source = mapOf(
            "aggregateId" to "id",
            "state" to mapOf("amount" to 10.5),
            "items" to listOf(mapOf("name" to "item")),
            "array" to arrayOf<Any>(true, integer),
            "byte" to 1.toByte(),
            "short" to 2.toShort(),
            "float" to 1.25f,
            "floatNode" to JsonNodeFactory.instance.numberNode(2.5f),
            "decimal" to decimal,
            "node" to embeddedNode,
            "nullable" to null,
        )

        val first = source.toObjectNode()
        val second = source.toObjectNode()

        first.assert().isNotSameAs(second)
        first.path("state").path("amount").doubleValue().assert().isEqualTo(10.5)
        first.path("items").path(0).path("name").asString().assert().isEqualTo("item")
        first.path("array").path(0).booleanValue().assert().isTrue()
        first.path("array").path(1).bigIntegerValue().assert().isEqualTo(integer)
        first.path("byte").intValue().assert().isEqualTo(1)
        first.path("short").intValue().assert().isEqualTo(2)
        first.path("float").floatValue().assert().isEqualTo(1.25f)
        first.path("floatNode").floatValue().assert().isEqualTo(2.5f)
        first.path("decimal").decimalValue().compareTo(decimal).assert().isZero()
        first.path("node").path("values").path(0).asString().assert().isEqualTo("text")
        first.path("nullable").isNull.assert().isTrue()
    }

    @Test
    fun `arbitrary pojo source value should fail`() {
        assertThrows<IllegalArgumentException> {
            mapOf("value" to Any()).toObjectNode()
        }
    }

    @Test
    fun `non string source map key should fail`() {
        assertThrows<IllegalArgumentException> {
            mapOf<Any, Any>(1 to "value").toObjectNode()
        }
    }

    @Test
    fun `primitive array source value should fail`() {
        listOf<Any>(byteArrayOf(1, 2), intArrayOf(1, 2)).forEach { value ->
            assertThrows<IllegalArgumentException> {
                mapOf("value" to value).toObjectNode()
            }
        }
    }

    @Test
    fun `non standard json nodes should fail`() {
        listOf(
            JsonNodeFactory.instance.pojoNode(Any()),
            JsonNodeFactory.instance.missingNode(),
            JsonNodeFactory.instance.binaryNode(byteArrayOf(1)),
        ).forEach { node ->
            assertThrows<IllegalArgumentException> {
                mapOf("nested" to listOf(mapOf("value" to node))).toObjectNode()
            }
        }
    }

    @Test
    fun `non finite source numbers should fail`() {
        listOf<Any>(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            JsonNodeFactory.instance.numberNode(Double.NaN),
            JsonNodeFactory.instance.numberNode(Float.POSITIVE_INFINITY),
        ).forEach { value ->
            assertThrows<IllegalArgumentException> {
                mapOf("value" to value).toObjectNode()
            }
        }
    }

    @Test
    fun `deeply nested pojo node should fail`() {
        assertThrows<IllegalArgumentException> {
            mapOf("nested" to listOf(mapOf("value" to JsonNodeFactory.instance.pojoNode(Any())))).toObjectNode()
        }
    }

    @Test
    fun `query source should deserialize directly to object node`() {
        val sourceType = slot<Class<ObjectNode>>()
        every { elasticsearchClient.search(any<SearchRequest>(), capture(sourceType)) } returns Mono.just(
            emptyObjectNodeSearchResponse(),
        )

        queryBackend.list(resolved(ListQuery(MatchAllFilter, limit = 1))).collectList().block()

        sourceType.captured.assert().isEqualTo(ObjectNode::class.java)
    }

    @Test
    fun `dynamic list should not track exact total hits`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), ObjectNode::class.java) } returns Mono.just(
            searchResponse(total = null)
        )

        val result = queryBackend.list(
            resolved(
                ListQuery(
                    filter = MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("field"))),
                    sort = listOf(Sort(QueryField("field"), Sort.Direction.ASC)),
                    limit = DEFAULT_SEARCH_BATCH_SIZE,
                ),
            ),
        ).collectList().block()!!

        request.captured.trackTotalHits()!!.enabled().assert().isFalse()
        request.captured.size().assert().isEqualTo(DEFAULT_SEARCH_BATCH_SIZE)
        request.captured.source()!!.filter().includes().assert().containsExactly("field", "field.*")
        request.captured.sort().assert().hasSize(1)
        result.assert().hasSize(1)
        verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `list should compile projection with the resolved schema`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), ObjectNode::class.java) } returns Mono.just(
            emptyObjectNodeSearchResponse(),
        )

        queryBackend.list(
            resolved(
                ListQuery(
                    MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("state"))),
                    limit = 1,
                ),
            ),
        ).collectList().block()

        request.captured.source()!!.filter().includes().assert().containsExactly(
            "document",
            "document.*",
        )
    }

    @Test
    fun `dynamic list without limit should use pit and stable sort`() {
        val openRequest = slot<OpenPointInTimeRequest>()
        val searchRequest = slot<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        every { elasticsearchClient.openPointInTime(capture(openRequest)) } returns Mono.just(openPointInTimeResponse())
        every { elasticsearchClient.search(capture(searchRequest), ObjectNode::class.java) } returns Mono.just(
            searchResponse(total = null, pitId = "pit-2")
        )
        every { elasticsearchClient.closePointInTime(capture(closeRequest)) } returns Mono.just(
            closePointInTimeResponse()
        )

        val result = queryBackend.list(resolved(ListQuery(MatchAllFilter))).collectList().block()!!

        result.assert().hasSize(1)
        openRequest.captured.index().assert().containsExactly("test-index")
        openRequest.captured.keepAlive().time().assert().isEqualTo("1m")
        searchRequest.captured.index().assert().isEmpty()
        searchRequest.captured.pit()!!.id().assert().isEqualTo("pit-1")
        searchRequest.captured.pit()!!.keepAlive()!!.time().assert().isEqualTo("1m")
        searchRequest.captured.sort().assert().hasSize(1)
        searchRequest.captured.sort().single().field().field().assert().isEqualTo("_shard_doc")
        closeRequest.captured.id().assert().isEqualTo("pit-2")
    }

    @Test
    fun `dynamic list above result window should preserve query options and append tiebreaker`() {
        val searchRequest = slot<SearchRequest>()
        every { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(
            openPointInTimeResponse()
        )
        every { elasticsearchClient.search(capture(searchRequest), ObjectNode::class.java) } returns Mono.just(
            searchResponse(total = null, pitId = "pit-2")
        )
        every { elasticsearchClient.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(
            closePointInTimeResponse()
        )

        queryBackend.list(
            resolved(
                ListQuery(
                    filter = MatchAllFilter,
                    projection = Projection(include = listOf(QueryField("field"))),
                    sort = listOf(
                        Sort(QueryField("_score"), Sort.Direction.DESC),
                        Sort(QueryField("field"), Sort.Direction.ASC),
                    ),
                    limit = DEFAULT_SEARCH_BATCH_SIZE + 1,
                ),
            ),
        ).collectList().block()

        searchRequest.captured.size().assert().isEqualTo(DEFAULT_SEARCH_BATCH_SIZE)
        searchRequest.captured.source()!!.filter().includes().assert().containsExactly("field", "field.*")
        searchRequest.captured.sort().map { it.field().field() }.assert()
            .containsExactly("_score", "field", "_shard_doc")
    }

    @Test
    fun `configured snapshot factory should propagate pager settings`() {
        val openRequest = slot<OpenPointInTimeRequest>()
        val searchRequest = slot<SearchRequest>()
        val indicesClient = mockk<ReactiveElasticsearchIndicesClient>()
        every { elasticsearchClient.indices() } returns indicesClient
        every { indicesClient.getMapping(any<GetMappingRequest>()) } returns Mono.just(emptyMappingResponse())
        every { elasticsearchClient.openPointInTime(capture(openRequest)) } returns Mono.just(openPointInTimeResponse())
        every { elasticsearchClient.search(capture(searchRequest), ObjectNode::class.java) } returns Mono.just(
            searchResponse(total = null, pitId = "pit-2")
        )
        every { elasticsearchClient.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(
            closePointInTimeResponse()
        )

        ElasticsearchSnapshotQueryBackendFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = 3,
            queryKeepAlive = Duration.ofMinutes(5),
        )
            .create(MOCK_AGGREGATE_METADATA)
            .let { binding ->
                val query = ListQuery(MatchAllFilter, limit = 4)
                val schema = binding.schemaProvider.schema().block()!!
                binding.backend.list(
                    ResolvedQuery(
                        schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE),
                        schema,
                    ),
                )
            }
            .collectList()
            .block()

        openRequest.captured.keepAlive().time().assert().isEqualTo("5m")
        searchRequest.captured.size().assert().isEqualTo(3)
        searchRequest.captured.pit()!!.keepAlive()!!.time().assert().isEqualTo("5m")
    }

    @Test
    fun `resolved list should preserve fields`() {
        val request = slot<SearchRequest>()
        val convertedFilter = slot<FilterExpression>()
        every { filterConverter.convert(capture(convertedFilter)) } returns matchAll { it }
        every { elasticsearchClient.search(capture(request), ObjectNode::class.java) } returns Mono.just(
            searchResponse(total = null),
        )
        val filter = filterExpression { "logicalField" eq "value" }

        queryBackend.list(
            resolved(
                ListQuery(
                    filter = filter,
                    sort = listOf(Sort(QueryField("logicalField"), Sort.Direction.ASC)),
                    limit = 1,
                ),
            ),
        ).collectList().block()

        convertedFilter.captured.assert().isEqualTo(filter)
        request.captured.sort().single().field().field().assert().isEqualTo("logicalField")
    }

    @Test
    fun `dynamic list should reject negative limit before searching`() {
        assertThrows<IllegalArgumentException> {
            queryBackend.list(resolved(ListQuery(MatchAllFilter, limit = -1)))
        }

        verify(exactly = 0) { elasticsearchClient.search(any<SearchRequest>(), ObjectNode::class.java) }
        verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `dynamic paged should track exact total hits`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), ObjectNode::class.java) } returns Mono.just(
            searchResponse(total = 42)
        )

        val result = queryBackend.paged(resolved(PagedQuery(MatchAllFilter))).block()!!

        request.captured.trackTotalHits()!!.enabled().assert().isTrue()
        request.captured.index().assert().containsExactly("test-index")
        request.captured.pit().assert().isNull()
        result.total.assert().isEqualTo(42)
        result.list.assert().hasSize(1)
        result.list.single().path("field").asString().assert().isEqualTo("value")
    }

    @Test
    fun `cursor should use search after without total from pit or shard doc`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), ObjectNode::class.java) } returns Mono.just(
            cursorSearchResponse(cursorHit("id-1", 1L), cursorHit("id-2", 2L)),
        )

        val page = queryBackend.cursor(
            resolved(
                CursorQuery(
                    MatchAllFilter,
                    sort = listOf(Sort(QueryField("version"), Sort.Direction.DESC)),
                    size = 1,
                ),
            ),
        ).block()!!

        request.captured.size().assert().isEqualTo(2)
        request.captured.from().assert().isNull()
        request.captured.trackTotalHits()!!.enabled().assert().isFalse()
        request.captured.pit().assert().isNull()
        request.captured.searchAfter().assert().isEmpty()
        request.captured.sort().map { it.field().field() }.assert().containsExactly("version", "aggregateId")
        request.captured.sort().map { it.field().missing()!!.stringValue() }.assert()
            .containsExactly("_last", "_first")
        page.list.map { it.path("id").asString() }.assert().containsExactly("id-1")
        page.nextCursor.assert().isNotNull()
        val nextValues = ElasticsearchCursorCodec.decode(page.nextCursor!!, 2)
        nextValues[0].longValue().assert().isEqualTo(1L)
        nextValues[1].stringValue().assert().isEqualTo("id-1")
        verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `cursor continuation should search after last returned hit and omit terminal cursor`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), ObjectNode::class.java) } returns Mono.just(
            cursorSearchResponse(cursorHit("id-2", 2L)),
        )
        val cursor = ElasticsearchCursorCodec.encode(listOf(FieldValue.of(1L), FieldValue.of("id-1")))

        val page = queryBackend.cursor(
            resolved(
                CursorQuery(
                    MatchAllFilter,
                    sort = listOf(Sort(QueryField("version"), Sort.Direction.ASC)),
                    size = 1,
                    cursor = cursor,
                ),
            ),
        ).block()!!

        request.captured.searchAfter()[0].longValue().assert().isEqualTo(1L)
        request.captured.searchAfter()[1].stringValue().assert().isEqualTo("id-1")
        page.list.map { it.path("id").asString() }.assert().containsExactly("id-2")
        page.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should reject last returned hit sort arity`() {
        assertInvalidCursorResponse(
            "id-1" to listOf(FieldValue.of(1L)),
            cursorHit("id-2", 2L),
        )
    }

    @Test
    fun `cursor should reject terminal hit sort arity`() {
        assertInvalidCursorResponse("id-1" to listOf(FieldValue.of(1L)))
    }

    @Test
    fun `cursor should reject lookahead hit sort arity`() {
        assertInvalidCursorResponse(
            cursorHit("id-1", 1L),
            "id-2" to listOf(FieldValue.of(2L)),
        )
    }

    @Test
    fun `count should use count api`() {
        val request = slot<CountRequest>()
        every { elasticsearchClient.count(capture(request)) } returns Mono.just(
            CountResponse.of {
                it.count(42)
                    .shards { shards -> shards.failed(0).successful(1).total(1) }
            }
        )

        val result = queryBackend.count(resolved(MatchAllFilter)).block()!!

        request.captured.index().assert().containsExactly("test-index")
        result.assert().isEqualTo(42)
        verify(exactly = 0) { elasticsearchClient.search(any<SearchRequest>(), ObjectNode::class.java) }
    }

    private fun searchResponse(total: Long?, pitId: String? = null): SearchResponse<ObjectNode> {
        return SearchResponse.of<ObjectNode> {
            it.took(1)
                .timedOut(false)
                .shards { shards -> shards.failed(0).successful(1).total(1) }
                .hits { hits ->
                    if (total != null) {
                        hits.total { totalHits -> totalHits.relation(TotalHitsRelation.Eq).value(total) }
                    }
                    hits.hits { hit ->
                        hit.index("test-index")
                            .id("1")
                            .source(JsonNodeFactory.instance.objectNode().put("field", "value"))
                    }.hits { hit -> hit.index("test-index").id("2") }
                }
            if (pitId != null) {
                it.pitId(pitId)
            }
            it
        }
    }

    private fun cursorHit(id: String, version: Long): Pair<String, List<FieldValue>> =
        id to listOf(FieldValue.of(version), FieldValue.of(id))

    private fun assertInvalidCursorResponse(vararg hits: Pair<String, List<FieldValue>>) {
        every { elasticsearchClient.search(any<SearchRequest>(), ObjectNode::class.java) } returns Mono.just(
            cursorSearchResponse(*hits),
        )

        val error = assertThrows<IllegalArgumentException> {
            queryBackend.cursor(
                resolved(
                    CursorQuery(
                        MatchAllFilter,
                        sort = listOf(Sort(QueryField("version"), Sort.Direction.ASC)),
                        size = 1,
                    ),
                ),
            ).block()
        }

        error.message.assert().isEqualTo("Invalid cursor.")
    }

    private fun cursorSearchResponse(
        vararg hits: Pair<String, List<FieldValue>>,
    ): SearchResponse<ObjectNode> = SearchResponse.of<ObjectNode> {
        it.took(1)
            .timedOut(false)
            .shards { shards -> shards.failed(0).successful(1).total(1) }
            .hits { metadata ->
                hits.forEach { (id, sort) ->
                    metadata.hits { hit ->
                        hit.index("test-index")
                            .id(id)
                            .source(JsonNodeFactory.instance.objectNode().put("id", id))
                            .sort(sort)
                    }
                }
                metadata
            }
    }

    private fun resolved(query: IListQuery): ResolvedQuery<IListQuery> =
        ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema)

    private fun resolved(query: IPagedQuery): ResolvedQuery<IPagedQuery> =
        ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema)

    private fun resolved(query: ICursorQuery): ResolvedQuery<ICursorQuery> =
        ResolvedQuery(schema.resolve(query).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema)

    private fun resolved(filter: FilterExpression): ResolvedQuery<FilterExpression> =
        ResolvedQuery(schema.resolve(filter).requireAccepted(QuerySchemaValidationMode.COMPATIBLE), schema)

    private fun emptyObjectNodeSearchResponse(): SearchResponse<ObjectNode> = SearchResponse.of<ObjectNode> {
        it.took(1)
            .timedOut(false)
            .shards { shards -> shards.failed(0).successful(1).total(1) }
            .hits { hits -> hits.hits(emptyList()) }
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

    private fun sortFieldSchema(field: QueryField) = projectionFieldSchema(field).copy(
        bindings = mapOf(QueryCapability.SORT to QueryFieldBinding(field, field, null)),
    )

    private fun openPointInTimeResponse(): OpenPointInTimeResponse {
        return OpenPointInTimeResponse.of {
            it.id("pit-1")
                .shards { shards -> shards.failed(0).successful(1).total(1) }
        }
    }

    private fun closePointInTimeResponse(): ClosePointInTimeResponse {
        return ClosePointInTimeResponse.of { it.succeeded(true).numFreed(1) }
    }

    private fun emptyMappingResponse(): GetMappingResponse =
        GetMappingResponse.of { response ->
            response.mappings(
                "wow.test.aggregate.snapshot",
                IndexMappingRecord.of { record -> record.mappings(TypeMapping.of { it }) },
            )
        }

    private open class TestElasticsearchQueryBackend(
        override val elasticsearchClient: ReactiveElasticsearchClient,
        override val filterConverter: AbstractElasticsearchFilterConverter,
    ) : AbstractElasticsearchQueryBackend() {
        override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val indexName: String = "test-index"
    }
}
