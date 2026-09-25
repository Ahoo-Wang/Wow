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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import java.util.Base64
import java.util.concurrent.TimeUnit

class BackendQueriesTest {
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "name" to scalarFixture(),
            "amount" to scalarFixture(QueryValueType.INTEGER),
            "createdAt" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch(TimeUnit.MILLISECONDS)),
        ),
    )

    @Test
    fun `single asks for the first record without a total`() {
        val backend = RecordingBackend(pages = { BackendPage(listOf(row("a"), row("b"))) })
        backend.single(QueryAdmission.single(SingleQuery(MatchAllFilter), schema)).test()
            .assertNext { it["name"].stringValue().assert().isEqualTo("a") }
            .verifyComplete()
        backend.windows.single().assert().isEqualTo(PageWindow.Offset(0, 1, withTotal = false))
    }

    @Test
    fun `paged asks for its offset window with the total`() {
        val backend = RecordingBackend(pages = { BackendPage(listOf(row("a")), 7) })
        backend.paged(QueryAdmission.paged(PagedQuery(MatchAllFilter, pagination = Pagination(3, 5)), schema)).test()
            .assertNext { page ->
                page.total.assert().isEqualTo(7)
                page.list.single()["name"].stringValue().assert().isEqualTo("a")
            }.verifyComplete()
        backend.windows.single().assert().isEqualTo(PageWindow.Offset(10, 5, withTotal = true))
    }

    @Test
    fun `list without a limit is rejected when the storage cannot stream to the end`() {
        val backend = RecordingBackend()
        val streaming = schema.withStorage(StorageSupport(paging = PagingSupport(unboundedStream = SupportMode.NONE)))
        backend.list(QueryAdmission.list(ListQuery(MatchAllFilter, limit = 0), streaming)).test()
            .expectError(QuerySchemaValidationException::class.java).verify()
        backend.list(QueryAdmission.list(ListQuery(MatchAllFilter, limit = 1), streaming)).test().verifyComplete()
        backend.streams.assert().isEqualTo(1)
    }

    @Test
    fun `cursor reads one look-ahead row and encodes the last page row's native position`() {
        val backend = RecordingBackend(
            pages = { window ->
                val after = (window as PageWindow.Keyset).after?.values?.single() as? Long ?: 0L
                val rows = (after + 1..after + 3).map { row("r$it") }
                BackendPage(rows, positions = (after + 1..after + 3).map { CursorPosition(listOf(it)) })
            },
        )
        val first = backend.cursor(QueryAdmission.cursor(CursorQuery(MatchAllFilter, size = 2), schema)).block()!!
        first.list.map { it["name"].stringValue() }.assert().containsExactly("r1", "r2")
        first.nextCursor.assert().isNotNull()
        backend.windows.last().assert().isEqualTo(PageWindow.Keyset(null, 3))

        val second = backend.cursor(
            QueryAdmission.cursor(CursorQuery(MatchAllFilter, size = 2, cursor = first.nextCursor), schema)
        ).block()!!
        backend.windows.last().assert().isEqualTo(PageWindow.Keyset(CursorPosition(listOf(2L)), 3))
        second.list.map { it["name"].stringValue() }.assert().containsExactly("r3", "r4")
    }

    @Test
    fun `cursor without a look-ahead row ends the pages`() {
        val backend = RecordingBackend(
            pages = { BackendPage(listOf(row("only")), positions = listOf(CursorPosition(listOf(1L)))) },
        )
        backend.cursor(QueryAdmission.cursor(CursorQuery(MatchAllFilter, size = 2), schema)).test()
            .assertNext { it.nextCursor.assert().isNull() }
            .verifyComplete()
    }

    @Test
    fun `a token for another sort, another model or in the old format is the invalid cursor`() {
        val backend = RecordingBackend(
            pages = {
                BackendPage(
                    listOf(row("a"), row("b")),
                    positions = List(2) { CursorPosition(listOf(it.toLong())) }
                )
            },
        )
        val token = backend.cursor(QueryAdmission.cursor(CursorQuery(MatchAllFilter, size = 1), schema))
            .block()!!.nextCursor!!
        val descending = listOf(Sort(QueryField("aggregateId"), Sort.Direction.DESC))
        val other = RecordingBackend(MaterializedNamedAggregate("other", "aggregate"))
        val legacy = Base64.getUrlEncoder().withoutPadding().encodeToString("[1]".toByteArray())
        listOf(
            backend to CursorQuery(MatchAllFilter, sort = descending, size = 1, cursor = token),
            other to CursorQuery(MatchAllFilter, size = 1, cursor = token),
            backend to CursorQuery(MatchAllFilter, size = 1, cursor = legacy),
            backend to CursorQuery(MatchAllFilter, size = 1, cursor = "not base64 !"),
        ).forEach { (target, query) ->
            val calls = target.windows.size
            assertThrows<IllegalArgumentException> { target.cursor(QueryAdmission.cursor(query, schema)).block() }
                .message.assert().isEqualTo("Invalid cursor.")
            target.windows.size.assert().isEqualTo(calls)
        }
    }

    @Test
    fun `native aggregation sends the query unchanged with its limit`() {
        val backend = RecordingBackend(groups = { Flux.just(row("a")) })
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
            limit = 5,
            having = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0),
        )
        val admitted = QueryAdmission.aggregate(query, schema)
        backend.aggregate(admitted).collectList().block()!!.assert().hasSize(1)
        backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(5))
        backend.aggregations.single().assert().isSameAs(admitted.query)
    }

    @Test
    fun `residual HAVING asks for every group and filters them in the core`() {
        val backend = RecordingBackend(groups = { Flux.just(group("a", 1), group("b", 3), group("c", 5)) })
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
            having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
        )
        val residual = schema.withStorage(
            StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
        )
        backend.aggregate(QueryAdmission.aggregate(query, residual)).collectList().block()!!
            .map { it["name"].stringValue() }.assert().containsExactly("b")
        backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        backend.aggregations.single().having.assert().isNull()
    }

    @Test
    fun `the entry budget bounds the groups a residual operator reads`() {
        val backend = RecordingBackend(groups = { Flux.just(group("a", 1), group("b", 1), group("c", 5)) })
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 1,
            having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
        )
        val residual = schema.withStorage(
            StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
        )
        val admitted = QueryAdmission.aggregate(query, residual)
        backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 2)).test()
            .expectErrorMessage(
                "HTTP aggregation processes more than [2] groups, dense fill included, to compute HAVING or a " +
                    "metric sort in the query service; narrow the filter or the period."
            )
            .verify()
        backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 3)).collectList().block()!!
            .map { it["name"].stringValue() }.assert().containsExactly("c")
        backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 0)).collectList()
            .block()!!.assert().hasSize(1)
        // A natively computed aggregation reads only its limit, so the bound does not apply.
        backend.aggregate(
            QueryAdmission.aggregate(query, schema),
            QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1)
        )
            .collectList().block()
    }

    @Test
    fun `residual top-N keeps the group order sent down and ranks by the metric in the core`() {
        val backend = RecordingBackend(groups = { Flux.just(group("a", 1), group("b", 3), group("c", 2)) })
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
            limit = 2,
        )
        val residual = schema.withStorage(StorageSupport(aggregation = AggregationSupport(topN = SupportMode.RESIDUAL)))
        backend.aggregate(QueryAdmission.aggregate(query, residual)).collectList().block()!!
            .map { it["name"].stringValue() }.assert().containsExactly("b", "c")
        backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        backend.aggregations.single().sort.assert().isEmpty()
    }

    @Test
    fun `residual dense fill sends real buckets only and fills the gaps before the limit`() {
        val day = 86_400_000L
        val backend = RecordingBackend(groups = { Flux.just(bucket(0, 2), bucket(3 * day, 1)) })
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.DateHistogram(QueryField("createdAt"), "day", AggregationDateUnit.DAY, dense = true),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            limit = 3,
        )
        val residual = schema.withStorage(
            StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
        )
        backend.aggregate(QueryAdmission.aggregate(query, residual)).collectList().block()!!
            .map { it["day"].longValue() to it["count"].longValue() }
            .assert().containsExactly(0L to 2L, day to 0L, 2 * day to 0L)
        backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(3))
        (backend.aggregations.single().groupBy.single() as AggregationGroup.DateHistogram).dense.assert().isFalse()
    }

    @Test
    fun `the residual budget counts dense fill rows, so a sparse fine-grained histogram under HAVING stays bounded`() {
        val year = 365L * 86_400_000L
        // Two real buckets a year apart at SECOND granularity: ~3·10^7 fill rows, all discarded by HAVING.
        val backend = RecordingBackend(groups = { Flux.just(bucket(0, 1), bucket(year, 1)) })
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.DateHistogram(
                    QueryField("createdAt"),
                    "day",
                    AggregationDateUnit.SECOND,
                    dense = true
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            having = HavingExpression.Condition("count", ComparisonOperator.GT, 5.0),
            limit = 10,
        )
        val residual = schema.withStorage(
            StorageSupport(
                aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL, having = SupportMode.RESIDUAL)
            ),
        )
        backend.aggregate(
            QueryAdmission.aggregate(query, residual),
            QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1000)
        )
            .test()
            .expectErrorMatches { it.message!!.startsWith("HTTP aggregation processes more than [1000] groups") }
            .verify(java.time.Duration.ofSeconds(5))
    }

    @Test
    fun `a dense date part is filled by the core to its whole domain on every storage`() {
        val backend = RecordingBackend(
            groups = {
                Flux.just(
                    """{"weekday":2,"count":3}""".toJsonNode(),
                    """{"weekday":5,"count":1}""".toJsonNode()
                )
            },
        )
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.DatePart(
                    QueryField("createdAt"),
                    "weekday",
                    AggregationDatePart.DAY_OF_WEEK,
                    timeZone = "Asia/Shanghai",
                    dense = true,
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("weekday"), Sort.Direction.DESC)),
        )

        backend.aggregate(QueryAdmission.aggregate(query, schema)).collectList().block()!!
            .map { it["weekday"].intValue() to it["count"].longValue() }
            .assert().containsExactly(7 to 0L, 6 to 0L, 5 to 1L, 4 to 0L, 3 to 0L, 2 to 3L, 1 to 0L)
        backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        val native = backend.aggregations.single()
        (native.groupBy.single() as AggregationGroup.DatePart).dense.assert().isFalse()
        native.sort.assert().containsExactly(Sort(QueryField("weekday"), Sort.Direction.DESC))
    }

    @Test
    fun `HAVING and a metric sort see the fill rows of a dense date part`() {
        val backend = RecordingBackend(groups = { Flux.just("""{"hour":9,"count":4}""".toJsonNode()) })
        val query = AggregationQuery(
            groupBy = listOf(
                AggregationGroup.DatePart(
                    QueryField("createdAt"),
                    "hour",
                    AggregationDatePart.HOUR_OF_DAY,
                    dense = true
                ),
            ),
            metrics = listOf(AggregationMetric.Count("count")),
            sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
            limit = 3,
            having = HavingExpression.Condition("count", ComparisonOperator.LT, 5.0),
        )

        backend.aggregate(QueryAdmission.aggregate(query, schema)).collectList().block()!!
            .map { it["hour"].intValue() to it["count"].longValue() }
            .assert().containsExactly(9 to 4L, 0 to 0L, 1 to 0L)
        backend.aggregations.single().having.assert().isNull()
        backend.aggregations.single().sort.assert().isEmpty()
    }

    @Test
    fun `a feature the storage does not support is rejected before the backend runs`() {
        val backend = RecordingBackend()
        val none = schema.withStorage(StorageSupport(aggregation = AggregationSupport(having = SupportMode.NONE)))
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
            metrics = listOf(AggregationMetric.Count("count")),
            having = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0),
        )
        backend.aggregate(QueryAdmission.aggregate(query, none)).test()
            .expectError(QuerySchemaValidationException::class.java).verify()
        backend.aggregations.assert().isEmpty()
    }

    private class RecordingBackend(
        override val namedAggregate: NamedAggregate = MaterializedNamedAggregate("context", "aggregate"),
        private val pages: (PageWindow) -> BackendPage = { BackendPage(emptyList(), 0, emptyList()) },
        private val groups: () -> Flux<ObjectNode> = { Flux.empty() },
    ) : QueryBackend {
        val windows = mutableListOf<PageWindow>()
        val groupWindows = mutableListOf<GroupWindow>()
        val aggregations = mutableListOf<AggregationQuery>()
        var streams = 0
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.defer {
            streams++
            Flux.empty()
        }

        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> =
            Mono.fromSupplier {
                windows += window
                pages(window)
            }

        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0)

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
            Flux.defer {
                aggregations += query.query
                groupWindows += window
                groups()
            }
    }

    private companion object {
        fun row(name: String): ObjectNode = JsonSerializer.createObjectNode().put("name", name)

        fun group(name: String, count: Long): ObjectNode = """{"name":"$name","count":$count}""".toJsonNode()

        fun bucket(day: Long, count: Long): ObjectNode = """{"day":$day,"count":$count}""".toJsonNode()

        fun QueryModelSchema.withStorage(storage: StorageSupport) =
            QueryModelSchema(
                model,
                capabilities,
                definition,
                bindings,
                fullProjectionAvailable,
                approximateMetrics,
                storage
            )
    }
}
