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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryService
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryService
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.CursorTokenCodec
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchIndicesClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.Base64

class AbstractElasticsearchQueryServiceTest {
    private val elasticsearchClient = mockk<ReactiveElasticsearchClient>()
    private val filterConverter = mockk<AbstractElasticsearchFilterConverter> {
        every { convert(any<me.ahoo.wow.api.query.FilterExpression>()) } returns matchAll { it }
    }
    private val tokenCodec = CursorTokenCodec.fromBase64Url(
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
    )
    private val queryService = TestElasticsearchQueryService(elasticsearchClient, filterConverter, tokenCodec)

    @Test
    fun `dynamic list should not track exact total hits`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
            searchResponse(total = null)
        )

        val result = queryService.dynamicList(
            ListQuery(
                filter = MatchAllFilter,
                projection = Projection(include = listOf("field")),
                sort = listOf(Sort("field", Sort.Direction.ASC)),
                limit = DEFAULT_SEARCH_BATCH_SIZE,
            )
        ).collectList().block()!!

        request.captured.trackTotalHits()!!.enabled().assert().isFalse()
        request.captured.size().assert().isEqualTo(DEFAULT_SEARCH_BATCH_SIZE)
        request.captured.source()!!.filter().includes().assert().containsExactly("field")
        request.captured.sort().assert().hasSize(1)
        result.assert().hasSize(1)
        verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `dynamic list without limit should use pit and stable sort`() {
        val openRequest = slot<OpenPointInTimeRequest>()
        val searchRequest = slot<SearchRequest>()
        val closeRequest = slot<ClosePointInTimeRequest>()
        every { elasticsearchClient.openPointInTime(capture(openRequest)) } returns Mono.just(openPointInTimeResponse())
        every { elasticsearchClient.search(capture(searchRequest), Map::class.java) } returns Mono.just(
            searchResponse(total = null, pitId = "pit-2")
        )
        every { elasticsearchClient.closePointInTime(capture(closeRequest)) } returns Mono.just(
            closePointInTimeResponse()
        )

        val result = queryService.dynamicList(ListQuery(MatchAllFilter)).collectList().block()!!

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
        every { elasticsearchClient.search(capture(searchRequest), Map::class.java) } returns Mono.just(
            searchResponse(total = null, pitId = "pit-2")
        )
        every { elasticsearchClient.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(
            closePointInTimeResponse()
        )

        queryService.dynamicList(
            ListQuery(
                filter = MatchAllFilter,
                projection = Projection(include = listOf("field")),
                sort = listOf(
                    Sort("_score", Sort.Direction.DESC),
                    Sort("field", Sort.Direction.ASC),
                ),
                limit = DEFAULT_SEARCH_BATCH_SIZE + 1,
            )
        ).collectList().block()

        searchRequest.captured.size().assert().isEqualTo(DEFAULT_SEARCH_BATCH_SIZE)
        searchRequest.captured.source()!!.filter().includes().assert().containsExactly("field")
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
        every { elasticsearchClient.search(capture(searchRequest), Map::class.java) } returns Mono.just(
            searchResponse(total = null, pitId = "pit-2")
        )
        every { elasticsearchClient.closePointInTime(any<ClosePointInTimeRequest>()) } returns Mono.just(
            closePointInTimeResponse()
        )

        ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient = elasticsearchClient,
            queryBatchSize = 3,
            queryKeepAlive = Duration.ofMinutes(5),
        )
            .create<Any>(MOCK_AGGREGATE_METADATA)
            .dynamicList(ListQuery(MatchAllFilter, limit = 4))
            .collectList()
            .block()

        openRequest.captured.keepAlive().time().assert().isEqualTo("5m")
        searchRequest.captured.size().assert().isEqualTo(3)
        searchRequest.captured.pit()!!.keepAlive()!!.time().assert().isEqualTo("5m")
    }

    @Test
    fun `default resolution hooks should preserve fields`() {
        val request = slot<SearchRequest>()
        val convertedFilter = slot<FilterExpression>()
        every { filterConverter.convert(capture(convertedFilter)) } returns matchAll { it }
        every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
            searchResponse(total = null),
        )
        val filter = filterExpression { "logicalField" eq "value" }

        queryService.dynamicList(
            ListQuery(
                filter = filter,
                sort = listOf(Sort("logicalField", Sort.Direction.ASC)),
                limit = 1,
            ),
        ).collectList().block()

        convertedFilter.captured.assert().isEqualTo(filter)
        request.captured.sort().single().field().field().assert().isEqualTo("logicalField")
    }

    @Test
    fun `dynamic list should reject negative limit before searching`() {
        assertThrows<IllegalArgumentException> {
            queryService.dynamicList(ListQuery(MatchAllFilter, limit = -1))
        }

        verify(exactly = 0) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
        verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `dynamic cursor should use search after without total from or pit`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
            searchResponseWithSortValues(1L to "id-1", 2L to "id-2", 3L to "id-3"),
        )

        val query = CursorQuery(
            MatchAllFilter,
            sort = listOf(Sort("version", Sort.Direction.ASC)),
            size = 2,
        )
        val page = queryService.dynamicCursor(query).block()!!

        request.captured.size().assert().isEqualTo(3)
        request.captured.from().assert().isNull()
        request.captured.trackTotalHits()!!.enabled().assert().isFalse()
        request.captured.pit().assert().isNull()
        request.captured.searchAfter().assert().isEmpty()
        request.captured.sort().map { it.field().field() }.assert().containsExactly("version", "id")
        page.list.assert().hasSize(2)
        val cursorValues = ElasticsearchCursorCodec.decode(tokenCodec, page.nextCursor!!, expectedSize = 2)
        cursorValues.assert().hasSize(2)
        cursorValues[0].longValue().assert().isEqualTo(2L)
        cursorValues[1].stringValue().assert().isEqualTo("id-2")
        verify(exactly = 0) { elasticsearchClient.openPointInTime(any<OpenPointInTimeRequest>()) }
    }

    @Test
    fun `next cursor request should pass decoded search after and omit cursor on last page`() {
        val requests = mutableListOf<SearchRequest>()
        every { elasticsearchClient.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(searchResponseWithSortValues(1L to "id-1", 2L to "id-2", 3L to "id-3")),
            Mono.just(searchResponseWithSortValues(3L to "id-3")),
        )
        val query = CursorQuery(
            MatchAllFilter,
            sort = listOf(Sort("version", Sort.Direction.ASC)),
            size = 2,
        )

        val first = queryService.dynamicCursor(query).block()!!
        val second = queryService.dynamicCursor(query.copy(cursor = first.nextCursor)).block()!!

        requests[1].searchAfter()[0].longValue().assert().isEqualTo(2L)
        requests[1].searchAfter()[1].stringValue().assert().isEqualTo("id-2")
        second.list.assert().hasSize(1)
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `search after should preserve every supported scalar kind including numeric temporal values`() {
        val requests = mutableListOf<SearchRequest>()
        val rawValue = "masked-sort-value-should-never-appear"
        val returned = listOf(
            FieldValue.NULL,
            FieldValue.of(true),
            FieldValue.of(rawValue),
            FieldValue.of(1_725_000_000_123L),
            FieldValue.of(1.25),
            FieldValue.of("id-1"),
        )
        every { elasticsearchClient.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(
                searchResponseWithSortLists(
                    returned,
                    returned.mapIndexed { index, value ->
                        if (index == returned.lastIndex) FieldValue.of("id-2") else value
                    }
                )
            ),
            Mono.just(searchResponseWithSortLists(returned)),
        )
        val query = CursorQuery(
            MatchAllFilter,
            sort = listOf("nullable", "flag", "secret", "date", "decimal").map {
                Sort(it, Sort.Direction.ASC)
            },
            size = 1,
        )

        val first = queryService.dynamicCursor(query).block()!!
        queryService.dynamicCursor(query.copy(cursor = first.nextCursor)).block()

        requests[1].searchAfter().let { values ->
            values[0].isNull.assert().isTrue()
            values[1].booleanValue().assert().isTrue()
            values[2].stringValue().assert().isEqualTo(rawValue)
            values[3].longValue().assert().isEqualTo(1_725_000_000_123L)
            values[4].doubleValue().assert().isEqualTo(1.25)
            values[5].stringValue().assert().isEqualTo("id-1")
        }
        first.nextCursor!!.contains(rawValue).assert().isFalse()
        Base64.getUrlDecoder().decode(first.nextCursor).toString(Charsets.ISO_8859_1)
            .contains(rawValue).assert().isFalse()
    }

    @Test
    fun `cursor hit should require complete sort values`() {
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            searchResponse(total = null),
        )

        val error = assertThrows<IllegalArgumentException> {
            queryService.dynamicCursor(CursorQuery(MatchAllFilter, size = 1)).block()
        }

        error.message.assert().isEqualTo("Invalid cursor.")
    }

    @Test
    fun `Elasticsearch cursor payload should reject malformed arity unsupported and excessive values`() {
        assertThrows<IllegalArgumentException> {
            ElasticsearchCursorCodec.decode(
                tokenCodec,
                tokenCodec.encode("not-json".toByteArray()),
                expectedSize = 1,
            )
        }
        val oneValue = ElasticsearchCursorCodec.encode(tokenCodec, listOf(FieldValue.of(1L)))
        assertThrows<IllegalArgumentException> {
            ElasticsearchCursorCodec.decode(tokenCodec, oneValue, expectedSize = 2)
        }
        val objectValue = tokenCodec.encode(JsonSerializer.writeValueAsBytes(listOf(mapOf("nested" to true))))
        assertThrows<IllegalArgumentException> {
            ElasticsearchCursorCodec.decode(tokenCodec, objectValue, expectedSize = 1)
        }
        assertThrows<IllegalArgumentException> {
            ElasticsearchCursorCodec.encode(tokenCodec, List(33) { FieldValue.of(it.toLong()) })
        }
    }

    @Test
    fun `typed cursor should reuse one dynamic search`() {
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            searchResponseWithSortValues(1L to "id-1"),
        )

        val page = queryService.cursor(CursorQuery(MatchAllFilter, size = 1)).block()!!

        page.list.assert().hasSize(1)
        verify(exactly = 1) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `cursor should remain unsupported without an encryption codec even on the first page`() {
        TestElasticsearchQueryService(elasticsearchClient, filterConverter, cursorTokenCodec = null)
            .cursor(CursorQuery(MatchAllFilter))
            .test()
            .expectErrorMatches {
                it is UnsupportedOperationException && it.message == "Cursor query is not supported."
            }
            .verify()
        verify(exactly = 0) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `missing cursor encryption codec should not affect paged queries`() {
        every { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
            searchResponse(total = 1),
        )
        val unconfigured = TestElasticsearchQueryService(
            elasticsearchClient,
            filterConverter,
            cursorTokenCodec = null,
        )

        unconfigured.dynamicPaged(PagedQuery(MatchAllFilter)).block()!!.total.assert().isEqualTo(1)

        verify(exactly = 1) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
    }

    @Test
    fun `Elasticsearch factory should accept cursor codec injection without changing its default`() {
        ElasticsearchSnapshotQueryServiceFactory(
            elasticsearchClient = elasticsearchClient,
            cursorTokenCodec = tokenCodec,
        ).assert().isNotNull()
        ElasticsearchSnapshotQueryServiceFactory(elasticsearchClient)
            .create<Any>(MOCK_AGGREGATE_METADATA)
            .cursor(CursorQuery(MatchAllFilter))
            .test()
            .expectError(UnsupportedOperationException::class.java)
            .verify()

        val legacyParameters = arrayOf(
            ReactiveElasticsearchClient::class.java,
            Int::class.javaPrimitiveType,
            Duration::class.java,
            ElasticsearchIndexMappingResolver::class.java,
            List::class.java,
            me.ahoo.wow.query.schema.QuerySchemaValidationMode::class.java,
        )
        ElasticsearchSnapshotQueryServiceFactory::class.java.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(legacyParameters)
        }.assert().isTrue()
        ElasticsearchEventStreamQueryServiceFactory::class.java.constructors.any { constructor ->
            constructor.parameterTypes.contentEquals(legacyParameters)
        }.assert().isTrue()
    }

    @Test
    fun `Elasticsearch services should retain pre-cursor constructor overloads`() {
        listOf(
            ElasticsearchSnapshotQueryService::class.java,
            ElasticsearchEventStreamQueryService::class.java,
        ).forEach { serviceType ->
            serviceType.constructors.any { constructor -> constructor.parameterCount == 7 }
                .assert().isTrue()
        }
    }

    @Test
    fun `built in cursor services should use their record id and validate cursor structure`() {
        val schemaProvider = unavailableSchemaProvider()
        val snapshotService = ElasticsearchSnapshotQueryService<Any>(
            MOCK_AGGREGATE_METADATA,
            elasticsearchClient,
            schemaProvider = schemaProvider,
            cursorTokenCodec = tokenCodec,
        )
        val eventStreamService = ElasticsearchEventStreamQueryService(
            MOCK_AGGREGATE_METADATA,
            elasticsearchClient,
            schemaProvider = schemaProvider,
            cursorTokenCodec = tokenCodec,
        )
        val requests = mutableListOf<SearchRequest>()
        every { elasticsearchClient.search(capture(requests), Map::class.java) } returnsMany listOf(
            Mono.just(searchResponseWithSortValues()),
            Mono.just(searchResponseWithSortValues()),
        )

        snapshotService.dynamicCursor(CursorQuery(MatchAllFilter, size = 1)).block()
        eventStreamService.dynamicCursor(CursorQuery(MatchAllFilter, size = 1)).block()

        requests[0].sort().single().field().field().assert().isEqualTo(MessageRecords.AGGREGATE_ID)
        requests[1].sort().single().field().field().assert().isEqualTo(MessageRecords.ID)
        val invalidCursor = ElasticsearchCursorCodec.encode(
            tokenCodec,
            listOf(FieldValue.of("one"), FieldValue.of("two")),
        )
        listOf(snapshotService, eventStreamService).forEach { service ->
            val error = assertThrows<IllegalArgumentException> {
                service.dynamicCursor(CursorQuery(MatchAllFilter, size = 1, cursor = invalidCursor)).block()
            }
            error.message.assert().isEqualTo("Invalid cursor.")
        }
    }

    @Test
    fun `dynamic paged should track exact total hits`() {
        val request = slot<SearchRequest>()
        every { elasticsearchClient.search(capture(request), Map::class.java) } returns Mono.just(
            searchResponse(total = 42)
        )

        val result = queryService.dynamicPaged(PagedQuery(MatchAllFilter)).block()!!

        request.captured.trackTotalHits()!!.enabled().assert().isTrue()
        request.captured.index().assert().containsExactly("test-index")
        request.captured.pit().assert().isNull()
        result.total.assert().isEqualTo(42)
        result.list.assert().hasSize(1)
        result.list.single()["field"].assert().isEqualTo("value")
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

        val result = queryService.count(MatchAllFilter).block()!!

        request.captured.index().assert().containsExactly("test-index")
        result.assert().isEqualTo(42)
        verify(exactly = 0) { elasticsearchClient.search(any<SearchRequest>(), Map::class.java) }
    }

    private fun searchResponse(total: Long?, pitId: String? = null): SearchResponse<Map<*, *>> {
        return SearchResponse.of<Map<*, *>> {
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
                            .source(mutableMapOf<String, Any?>("field" to "value"))
                    }.hits { hit -> hit.index("test-index").id("2") }
                }
            if (pitId != null) {
                it.pitId(pitId)
            }
            it
        }
    }

    private fun searchResponseWithSortValues(
        vararg values: Pair<Long, String>,
    ): SearchResponse<Map<*, *>> = searchResponseWithSortLists(
        *values.map { (version, id) -> listOf(FieldValue.of(version), FieldValue.of(id)) }.toTypedArray(),
    )

    private fun searchResponseWithSortLists(
        vararg values: List<FieldValue>,
    ): SearchResponse<Map<*, *>> = SearchResponse.of { response ->
        response.took(1).timedOut(false)
            .shards { it.failed(0).successful(1).total(1) }
            .hits { hits ->
                hits.hits(
                    values.mapIndexed { index, sortValues ->
                        co.elastic.clients.elasticsearch.core.search.Hit.of<Map<*, *>> { hit ->
                            hit.index("test-index").id((index + 1).toString())
                                .source(mutableMapOf<String, Any?>("id" to (index + 1).toString()))
                                .sort(sortValues)
                        }
                    },
                )
            }
    }

    private fun unavailableSchemaProvider(): QueryModelSchemaProvider = mockk {
        every { schema() } returns Mono.error(QuerySchemaUnavailableException("Unavailable."))
    }

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

    private open class TestElasticsearchQueryService(
        override val elasticsearchClient: ReactiveElasticsearchClient,
        override val filterConverter: AbstractElasticsearchFilterConverter,
        override val cursorTokenCodec: CursorTokenCodec?,
    ) : AbstractElasticsearchQueryService<DynamicDocument>() {
        override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("test", "aggregate")
        override val indexName: String = "test-index"
        override val cursorUniqueField: String = "id"

        override fun toTypedResult(document: DynamicDocument): DynamicDocument = document
    }
}
