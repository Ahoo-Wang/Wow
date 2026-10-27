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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.backend.storageFault
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.UnavailableQueryStorageAdapter
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.spec.MetricSpec
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
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
        runTest {
            val backend = RecordingBackend(pages = { BackendPage(listOf(row("a"), row("b"))) })
            backend.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))!!["name"].stringValue()
                .assert().isEqualTo("a")
            backend.windows.single().assert().isEqualTo(PageWindow.Offset(0, 1, withTotal = false))
            RecordingBackend(pages = { BackendPage(emptyList()) })
                .single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema)).assert().isNull()
        }
    }

    @Test
    fun `paged asks for its offset window with the total`() {
        runTest {
            val backend = RecordingBackend(pages = { BackendPage(listOf(row("a")), 7) })
            val page = backend.paged(
                QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(3, 5)), schema)
            )
            page.total.assert().isEqualTo(7)
            page.exact.assert().isTrue()
            page.list.single()["name"].stringValue().assert().isEqualTo("a")
            backend.windows.single().assert().isEqualTo(PageWindow.Offset(10, 5, withTotal = true))
        }
    }

    @Test
    fun `paged beyond the storage offset window is rejected at admission naming the limit`() {
        runTest {
            val windowed = schema.withStorage(StorageSupport(paging = PagingSupport(maxOffsetWindow = 20)))
            val violation = checkNotNull(
                assertThrows<QueryRequestException> {
                    QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(5, 5)), windowed)
                }.violation
            )
            violation.assert().isEqualTo(
                QueryViolation.SizeOutOfRange("Storage", "page window", 25, null, 20, "pagination")
            )
            violation.code.assert().isEqualTo(QueryErrorCodes.SIZE_OUT_OF_RANGE)
            violation.message.assert().isEqualTo("Storage page window[25] must not exceed 20.")

            val backend = RecordingBackend(pages = { BackendPage(emptyList(), 0) })
            backend.paged(
                QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(4, 5)), windowed)
            ).list.assert().isEmpty()
            backend.windows.single().assert().isEqualTo(PageWindow.Offset(15, 5, withTotal = true))
        }
    }

    @Test
    fun `the offset window is unlimited by default`() {
        PagingSupport().maxOffsetWindow.assert().isNull()
        QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, pagination = Pagination(100_000, 100)), schema)
        assertThrows<IllegalArgumentException> { PagingSupport(maxOffsetWindow = 0) }
    }

    @Test
    fun `list without a limit is rejected at admission when the storage cannot stream to the end`() {
        runTest {
            val backend = RecordingBackend()
            val streaming = schema.withStorage(
                StorageSupport(paging = PagingSupport(unboundedStream = SupportMode.NONE))
            )
            assertThrows<QuerySchemaValidationException> {
                QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), streaming)
            }.violation.assert().isEqualTo(QueryViolation.StorageUnsupported("listing without a limit"))
            backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, limit = 1), streaming)).toList()
                .assert().isEmpty()
            backend.streams.assert().isEqualTo(1)
        }
    }

    @Test
    fun `list streams the backend records and the flow is cold`() {
        runTest {
            val backend = RecordingBackend(records = { flowOf(row("a"), row("b")) })
            val rows = backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema))
            backend.streams.assert().isZero()
            rows.toList().map { it["name"].stringValue() }.assert().containsExactly("a", "b")
            rows.toList().assert().hasSize(2)
            backend.streams.assert().isEqualTo(2)
        }
    }

    @Test
    fun `cursor reads one look-ahead row and encodes the last page row's native position`() {
        runTest {
            val backend = RecordingBackend(
                pages = { window ->
                    val after = (window as PageWindow.Keyset).after?.values?.single() as? Long ?: 0L
                    val rows = (after + 1..after + 3).map { row("r$it") }
                    BackendPage(rows, positions = (after + 1..after + 3).map { CursorPosition(listOf(it)) })
                },
            )
            val first = backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 2), schema))
            first.list.map { it["name"].stringValue() }.assert().containsExactly("r1", "r2")
            first.nextCursor.assert().isNotNull()
            backend.windows.last().assert().isEqualTo(PageWindow.Keyset(null, 3))

            val second = backend.cursor(
                QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 2, cursor = first.nextCursor), schema)
            )
            backend.windows.last().assert().isEqualTo(PageWindow.Keyset(CursorPosition(listOf(2L)), 3))
            second.list.map { it["name"].stringValue() }.assert().containsExactly("r3", "r4")
        }
    }

    @Test
    fun `cursor without a look-ahead row ends the pages`() {
        runTest {
            val backend = RecordingBackend(
                pages = { BackendPage(listOf(row("only")), positions = listOf(CursorPosition(listOf(1L)))) },
            )
            backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 2), schema))
                .nextCursor.assert().isNull()
        }
    }

    @Test
    fun `a look-ahead row without positions is a server fault`() {
        runTest {
            val backend = RecordingBackend(pages = { BackendPage(listOf(row("a"), row("b"))) })
            assertThrows<QueryExecutionException> {
                backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 1), schema))
            }.message.assert().isEqualTo("Backend keyset page must carry each row's position.")
        }
    }

    @Test
    fun `a token for another sort, another model or in the old format is the invalid cursor`() {
        runTest {
            val backend = RecordingBackend(
                pages = {
                    BackendPage(
                        listOf(row("a"), row("b")),
                        positions = List(2) { CursorPosition(listOf(it.toLong())) }
                    )
                },
            )
            val token = backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 1), schema))
                .nextCursor!!
            val descending = listOf(Sort(QueryField("aggregateId"), Sort.Direction.DESC))
            val other = RecordingBackend(AggregateType("other", "aggregate"))
            val legacy = Base64.getUrlEncoder().withoutPadding().encodeToString("[1]".toByteArray())
            listOf(
                backend to CursorQuery(MatchAllFilter, sort = descending, size = 1, cursor = token),
                other to CursorQuery(MatchAllFilter, size = 1, cursor = token),
                backend to CursorQuery(MatchAllFilter, size = 1, cursor = legacy),
                backend to CursorQuery(MatchAllFilter, size = 1, cursor = "not base64 !"),
            ).forEach { (target, query) ->
                val calls = target.windows.size
                assertThrows<QueryRequestException> { target.cursor(QueryAdmission.Trusted.cursor(query, schema)) }
                    .apply {
                        message.assert().isEqualTo("Invalid cursor.")
                        code.assert().isEqualTo(QueryErrorCodes.INVALID_CURSOR)
                        errorCode.assert().isEqualTo(QueryErrors.INVALID)
                        bindingErrors.single().name.assert().isEqualTo("cursor")
                    }
                target.windows.size.assert().isEqualTo(calls)
            }
        }
    }

    @Test
    fun `a position the codec cannot encode is a server fault, not an invalid cursor`() {
        runTest {
            val backend = RecordingBackend(
                pages = {
                    BackendPage(
                        listOf(row("a"), row("b")),
                        positions = List(2) { CursorPosition(listOf(Any())) }
                    )
                },
            )
            assertThrows<QueryExecutionException> {
                backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, size = 1), schema))
            }.apply {
                errorCode.assert().isEqualTo(QueryErrors.FAILED)
                message.assert().isEqualTo("Cursor position could not be encoded.")
            }
        }
    }

    @Test
    fun `native aggregation sends the query unchanged with its limit`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(row("a")) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 5,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0),
            )
            val admitted = QueryAdmission.Trusted.aggregate(query, schema)
            backend.aggregate(admitted).toList().assert().hasSize(1)
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(5))
            backend.aggregations.single().assert().isSameAs(admitted.query)
        }
    }

    @Test
    fun `a HAVING that removes every group leaves no row, never the empty summary`() {
        runTest {
            // A native HAVING removed every group: the core adds nothing in their place.
            val backend = RecordingBackend(groups = { emptyFlow() })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GTE, 1.0),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().assert().isEmpty()
            // A summary cannot carry a HAVING, so the empty summary is never a row a HAVING removed.
            assertThrows<IllegalArgumentException> { query.copy(groupBy = emptyList()) }
        }
    }

    @Test
    fun `groups without a sort ask the storage for one more, to refuse a cut no one asked for`() {
        runTest {
            val groups = flowOf(group("a", 1), group("b", 3), group("c", 5))
            val unsorted = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                limit = 2,
            )
            val backend = RecordingBackend(groups = { groups })
            assertThrows<QueryRequestException> {
                backend.aggregate(QueryAdmission.Trusted.aggregate(unsorted, schema)).toList()
            }.violation.assert().isEqualTo(QueryViolation.TooManyGroups(2))
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(3))
            // A sort makes the cut the query's own: a top-N, asked for exactly.
            val sorted = RecordingBackend(groups = { groups })
            val top = AggregationQuery(
                groupBy = unsorted.groupBy,
                metrics = unsorted.metrics,
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
                limit = 2,
            )
            sorted.aggregate(QueryAdmission.Trusted.aggregate(top, schema)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("a", "b")
            sorted.groupWindows.single().assert().isEqualTo(GroupWindow.First(2))
        }
    }

    @Test
    fun `residual HAVING asks for every group and filters them in the core`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("a", 1), group("b", 3), group("c", 5)) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
                limit = 1,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("b")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            backend.aggregations.single().having.assert().isNull()
        }
    }

    @Test
    fun `the entry budget bounds the groups a residual operator reads`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("a", 1), group("b", 1), group("c", 5)) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("name"), Sort.Direction.ASC)),
                limit = 1,
                having = HavingExpression.Condition("count", ComparisonOperator.GT, 2.0),
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(having = SupportMode.RESIDUAL))
            )
            val admitted = QueryAdmission.Trusted.aggregate(query, residual)
            assertThrows<QueryRequestException> {
                backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 2)).toList()
            }.message.assert().isEqualTo(
                "HTTP aggregation processes more than [2] groups, dense fill included, to compute HAVING, a " +
                    "metric sort or a window in the query service; narrow the filter or the period."
            )
            backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 3)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("c")
            backend.aggregate(admitted, QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 0)).toList()
                .assert().hasSize(1)
            // A natively computed aggregation reads only its limit, so the bound does not apply.
            backend.aggregate(
                QueryAdmission.Trusted.aggregate(query, schema),
                QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1)
            ).toList().assert().hasSize(1)
        }
    }

    @Test
    fun `residual top-N keeps the group order sent down and ranks by the metric in the core`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("a", 1), group("b", 3), group("c", 2)) })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 2,
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(topN = SupportMode.RESIDUAL))
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
                .map { it["name"].stringValue() }.assert().containsExactly("b", "c")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            backend.aggregations.single().sort.assert().isEmpty()
        }
    }

    @Test
    fun `a storage that orders one TERMS group by the metric takes the top N itself`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(group("b", 3), group("c", 2)) })
            val count = AggregationMetric.Count("count")
            val byCount = listOf(Sort(QueryField("count"), Sort.Direction.DESC))
            val name = AggregationGroup.Terms(QueryField("name"), "name")
            val native = schema.withStorage(
                StorageSupport(
                    aggregation = AggregationSupport(topN = SupportMode.RESIDUAL, termsTopN = setOf(MetricSpec.COUNT))
                )
            )
            val query = AggregationQuery(groupBy = listOf(name), metrics = listOf(count), sort = byCount, limit = 2)
            // The bound on residual groups does not apply: the storage reads only its limit.
            backend.aggregate(
                QueryAdmission.Trusted.aggregate(query, native),
                QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1),
            ).toList().map { it["name"].stringValue() }.assert().containsExactly("b", "c")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(2))
            backend.aggregations.single().sort.assert().isEqualTo(byCount)

            // Two groups, a HAVING, or a metric type the storage does not order by stay residual.
            val residual = listOf(
                query.copy(groupBy = listOf(name, AggregationGroup.Terms(QueryField("aggregateId"), "aggregate"))),
                query.copy(having = HavingExpression.Condition("count", ComparisonOperator.GT, 0.0)),
                query.copy(
                    metrics = listOf(
                        AggregationMetric.Numeric(
                            AggregationFunction.SUM,
                            AggregationExpression.Field(QueryField("amount")),
                            "count",
                        )
                    ),
                ),
                query.copy(groupBy = listOf(AggregationGroup.Histogram(QueryField("amount"), "name", 10.0))),
            )
            residual.forEach { shape ->
                val recording = RecordingBackend(groups = { emptyFlow() })
                recording.aggregate(QueryAdmission.Trusted.aggregate(shape, native)).toList()
                recording.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            }
        }
    }

    @Test
    fun `residual dense fill sends real buckets only and fills the gaps before the limit`() {
        runTest {
            val day = 86_400_000L
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 2), bucket(3 * day, 1)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("day"), Sort.Direction.ASC)),
                limit = 3,
            )
            val residual = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residual)).toList()
                .map { it["day"].longValue() to it["count"].longValue() }
                .assert().containsExactly(0L to 2L, day to 0L, 2 * day to 0L)
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.First(3))
            (backend.aggregations.single().groupBy.single() as AggregationGroup.DateHistogram).dense.assert().isFalse()
        }
    }

    @Test
    fun `the residual budget counts dense fill rows, so a sparse fine-grained histogram under HAVING stays bounded`() {
        runTest {
            val year = 365L * 86_400_000L
            // Two real buckets a year apart at SECOND granularity: ~3·10^7 fill rows, all discarded by HAVING.
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 1), bucket(year, 1)) })
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
            assertThrows<QueryRequestException> {
                backend.aggregate(
                    QueryAdmission.Trusted.aggregate(query, residual),
                    QueryBudget(QueryBudget.HTTP_LABEL, maxResidualGroups = 1000)
                ).toList()
            }.message!!.assert().startsWith("HTTP aggregation processes more than [1000] groups")
        }
    }

    @Test
    fun `a dense date part is filled by the core to its whole domain on every storage`() {
        runTest {
            val backend = RecordingBackend(
                groups = { flowOf(json("""{"weekday":2,"count":3}"""), json("""{"weekday":5,"count":1}""")) },
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

            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
                .map { it["weekday"].intValue() to it["count"].longValue() }
                .assert().containsExactly(7 to 0L, 6 to 0L, 5 to 1L, 4 to 0L, 3 to 0L, 2 to 3L, 1 to 0L)
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
            val native = backend.aggregations.single()
            (native.groupBy.single() as AggregationGroup.DatePart).dense.assert().isFalse()
            native.sort.assert().containsExactly(Sort(QueryField("weekday"), Sort.Direction.DESC))
        }
    }

    @Test
    fun `HAVING and a metric sort see the fill rows of a dense date part`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(json("""{"hour":9,"count":4}""")) })
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

            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
                .map { it["hour"].intValue() to it["count"].longValue() }
                .assert().containsExactly(9 to 4L, 0 to 0L, 1 to 0L)
            backend.aggregations.single().having.assert().isNull()
            backend.aggregations.single().sort.assert().isEmpty()
        }
    }

    @Test
    fun `residual dense fill makes a native HAVING residual so it sees the fill rows`() {
        runTest {
            val day = 86_400_000L
            // A native HAVING would drop the day-1 bucket before the fill, which would then refill it with count 0.
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 2), bucket(day, 1), bucket(2 * day, 3)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                having = HavingExpression.Condition("count", ComparisonOperator.GTE, 2.0),
                limit = 10,
            )
            val residualDense = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residualDense)).toList()
                .map { it["day"].longValue() to it["count"].longValue() }
                .assert().containsExactly(0L to 2L, 2 * day to 3L)
            backend.aggregations.single().having.assert().isNull()
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        }
    }

    @Test
    fun `residual dense fill makes a native top-N residual so the fill walks key order`() {
        runTest {
            val day = 86_400_000L
            val backend = RecordingBackend(groups = { flowOf(bucket(0, 2), bucket(2 * day, 5), bucket(3 * day, 1)) })
            val query = AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.DateHistogram(
                        QueryField("createdAt"),
                        "day",
                        AggregationDateUnit.DAY,
                        dense = true
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
                limit = 3,
            )
            val residualDense = schema.withStorage(
                StorageSupport(aggregation = AggregationSupport(denseFill = SupportMode.RESIDUAL)),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, residualDense)).toList()
                .map { it["day"].longValue() to it["count"].longValue() }
                .assert().containsExactly(2 * day to 5L, 0L to 2L, 3 * day to 1L)
            val native = backend.aggregations.single()
            native.sort.map { it.field.path }.assert().doesNotContain("count")
            backend.groupWindows.single().assert().isEqualTo(GroupWindow.All)
        }
    }

    @Test
    fun `the core emits the empty summary when the backend emits no row without groups`() {
        runTest {
            val backend = RecordingBackend()
            val query = AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total"
                    ),
                ),
            )
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().single().apply {
                this["count"].longValue().assert().isZero()
                this["total"].isNull.assert().isTrue()
            }
        }
    }

    @Test
    fun `a summary the backend emits is never duplicated`() {
        runTest {
            val backend = RecordingBackend(groups = { flowOf(json("""{"count":0}""")) })
            val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().assert().hasSize(1)
        }
    }

    @Test
    fun `every shape rejects a backend row that is not standard JSON`() {
        runTest {
            val nan = WowJson.mapper.createObjectNode().put("name", "a").also {
                it.putObject("state").putArray("values").add(Double.NaN)
            }
            val infinite = WowJson.mapper.createObjectNode().put("name", "a").put("amount", Float.POSITIVE_INFINITY)
            val nonStandard = listOf(
                JsonNodeFactory.instance.pojoNode(Any()),
                JsonNodeFactory.instance.missingNode(),
                JsonNodeFactory.instance.binaryNode(byteArrayOf(1)),
            ).map { node ->
                WowJson.mapper.createObjectNode().also { it.putArray("nested").addObject().set("value", node) }
            }

            (listOf(nan, infinite) + nonStandard).forEach { row ->
                val backend = RecordingBackend(
                    pages = { BackendPage(listOf(row), 1, listOf(CursorPosition(listOf(1L)))) },
                    records = { flowOf(row) },
                )
                assertThrows<QueryExecutionException> {
                    backend.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))
                }
                assertThrows<QueryExecutionException> {
                    backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
                }
                assertThrows<QueryExecutionException> {
                    backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema))
                }
                assertThrows<QueryExecutionException> {
                    backend.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema))
                }
            }
            assertThrows<QueryExecutionException> {
                RecordingBackend(records = { flowOf(nan) })
                    .list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.message.assert().isEqualTo("Query result [state.values] must be finite.")
            assertThrows<QueryExecutionException> {
                RecordingBackend(pages = { BackendPage(listOf(nonStandard.first())) })
                    .single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))
            }.message.assert().isEqualTo("Query result [nested.value] must be a standard JSON value.")
            // An empty property name renders as it always did: a name after an empty prefix starts the path again.
            val emptyKeys = WowJson.mapper.createObjectNode().also {
                it.putObject("").putObject("state").putObject("").put("value", Double.NaN)
            }
            assertThrows<QueryExecutionException> {
                RecordingBackend(records = { flowOf(emptyKeys) })
                    .list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.message.assert().isEqualTo("Query result [state..value] must be finite.")
        }
    }

    @Test
    fun `count is a server fault when the storage fails or answers a negative number`() {
        runTest {
            suspend fun count(backend: RecordingBackend) =
                backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema))

            count(RecordingBackend(counts = { 3 })).assert().isEqualTo(CountResult(3, exact = true))
            assertThrows<QueryExecutionException> { count(RecordingBackend(counts = { -1 })) }
                .message.assert().isEqualTo("Backend count must be greater than or equal to 0.")
            val driver = IllegalStateException("driver text with values")
            assertThrows<QueryExecutionException> { count(RecordingBackend(counts = { throw driver })) }.apply {
                message.assert().isEqualTo("Query storage failed.")
                cause.assert().isSameAs(driver)
                errorCode.assert().isEqualTo(QueryErrors.FAILED)
            }
        }
    }

    @Test
    fun `a storage that is unavailable is the retryable storage error, not a server fault`() {
        runTest {
            val unavailable = StorageUnavailableException("PostgreSQL is unavailable; retry later.")
            assertThrows<QueryStorageUnavailableException> {
                RecordingBackend(counts = { throw unavailable })
                    .countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema))
            }.apply {
                errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
                errorMsg.assert().isEqualTo("Query storage is unavailable; retry later.")
                generateSequence(cause) { it.cause }.last().assert().isSameAs(unavailable)
            }
            assertThrows<QueryStorageUnavailableException> {
                RecordingBackend(records = { throw unavailable })
                    .list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.errorCode.assert().isEqualTo(QueryErrors.STORAGE_UNAVAILABLE)
        }
    }

    @Test
    fun `aggregation rows with a non-finite metric fail the query`() {
        runTest {
            val backend = RecordingBackend(groups = {
                flowOf(WowJson.mapper.createObjectNode().put("name", "a").put("total", Double.POSITIVE_INFINITY))
            })
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
                metrics = listOf(
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(QueryField("amount")),
                        "total"
                    )
                ),
            )
            assertThrows<QueryExecutionException> {
                backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            }.message.assert().isEqualTo("Aggregation metric [total] must be finite.")
            val badGroup = RecordingBackend(groups = {
                flowOf(WowJson.mapper.createObjectNode().put("name", Double.NaN).put("total", 1))
            })
            assertThrows<QueryExecutionException> {
                badGroup.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList()
            }.message.assert().isEqualTo("Aggregation group [name] must be finite.")
        }
    }

    @Test
    fun `a feature the storage does not support is rejected at admission`() {
        val none = schema.withStorage(StorageSupport(aggregation = AggregationSupport(having = SupportMode.NONE)))
        val query = AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("name"), "name")),
            metrics = listOf(AggregationMetric.Count("count")),
            having = HavingExpression.Condition("count", ComparisonOperator.GT, 1.0),
        )
        assertThrows<QuerySchemaValidationException> { QueryAdmission.Trusted.aggregate(query, none) }
            .violation.assert().isEqualTo(QueryViolation.StorageUnsupported("HAVING"))
    }

    @Test
    fun `a cursor is rejected at admission when the storage cannot page by keyset`() {
        val keyset = schema.withStorage(StorageSupport(paging = PagingSupport(keyset = SupportMode.NONE)))
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), keyset)
        }.violation!!.code.assert().isEqualTo(QueryErrorCodes.STORAGE_UNSUPPORTED)
    }

    // D150: bounded counts.

    @Test
    fun `a count below or at its limit is exact and above it is the limit, not exact`() {
        runTest {
            suspend fun count(matches: Long, limit: Int?): CountResult {
                val backend = RecordingBackend(counts = { matches })
                val admitted = QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, limit), schema)
                return backend.countBounded(admitted).also {
                    backend.counted.single().query.limit.assert().isEqualTo(limit)
                }
            }
            count(4, 5).assert().isEqualTo(CountResult(4, exact = true))
            count(5, 5).assert().isEqualTo(CountResult(5, exact = true))
            count(6, 5).assert().isEqualTo(CountResult(5, exact = false))
            count(20_000, null).assert().isEqualTo(CountResult(20_000, exact = true))
            count(0, null).assert().isEqualTo(CountResult(0, exact = true))
        }
    }

    // D150: paged totals.

    @Test
    fun `an exact total asks for the total without a bound`() {
        runTest {
            val backend = RecordingBackend(pages = { BackendPage(listOf(row("a")), 50_000) })
            val query = PagedQuery(MatchAllFilter, pagination = Pagination(2, 10), total = TotalMode.EXACT)
            val page = backend.paged(QueryAdmission.Trusted.paged(query, schema), totalLimit = 10)
            page.total.assert().isEqualTo(50_000)
            page.exact.assert().isTrue()
            backend.windows.single().assert().isEqualTo(PageWindow.Offset(10, 10, withTotal = true, totalLimit = null))
        }
    }

    @Test
    fun `a bounded total above the limit is the limit and not exact`() {
        runTest {
            suspend fun paged(total: Long, limit: Int?): Pair<PageWindow, me.ahoo.wow.api.query.PagedList<ObjectNode>> {
                val backend = RecordingBackend(pages = { BackendPage(listOf(row("a")), total) })
                val query = PagedQuery(MatchAllFilter, pagination = Pagination(1, 10), total = TotalMode.BOUNDED)
                val page = backend.paged(QueryAdmission.Trusted.paged(query, schema), totalLimit = limit)
                return backend.windows.single() to page
            }
            paged(11, 10).let { (window, page) ->
                window.assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = true, totalLimit = 10))
                page.total.assert().isEqualTo(10)
                page.exact.assert().isFalse()
            }
            paged(10, 10).second.let { page ->
                page.total.assert().isEqualTo(10)
                page.exact.assert().isTrue()
            }
            paged(123_456, null).let { (window, page) ->
                window.assert().isEqualTo(PageWindow.Offset(0, 10, withTotal = true, totalLimit = null))
                page.total.assert().isEqualTo(123_456)
                page.exact.assert().isTrue()
            }
        }
    }

    @Test
    fun `no total asks the window for none and reports none`() {
        runTest {
            val backend = RecordingBackend(pages = { BackendPage(listOf(row("a"), row("b")), 99) })
            val query = PagedQuery(MatchAllFilter, pagination = Pagination(1, 2), total = TotalMode.NONE)
            val page = backend.paged(QueryAdmission.Trusted.paged(query, schema), totalLimit = 10)
            backend.windows.single().assert().isEqualTo(PageWindow.Offset(0, 2, withTotal = false))
            page.total.assert().isNull()
            page.exact.assert().isFalse()
            page.list.assert().hasSize(2)
        }
    }

    @Test
    fun `a page without the total it was asked for is a server fault`() {
        runTest {
            TotalMode.entries.filter { it != TotalMode.NONE }.forEach { mode ->
                val backend = RecordingBackend(pages = { BackendPage(listOf(row("a"))) })
                assertThrows<QueryExecutionException> {
                    backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter, total = mode), schema), 10)
                }.message.assert().isEqualTo("Backend page must carry the total it was asked for.")
            }
        }
    }

    @Test
    fun `an admitted query answers the resolution of its own field nodes only`() {
        val query = SingleQuery(AndFilter(listOf(EqualFilter(QueryField("name"), "a"), AggregateIdFilter("id"))))
        val admitted = QueryAdmission.Trusted.single(query, schema)
        val operands = (admitted.query.filter as AndFilter).operands
        val name = operands.filterIsInstance<EqualFilter>().single()
        admitted.field(name.field).logicalField.assert().isEqualTo(QueryField("name"))
        admitted.physicalPath(name.field).assert().isEqualTo("native.name")
        val id = operands.single { it !is EqualFilter }
        admitted.systemField(id).logicalField.assert().isEqualTo(QueryField("aggregateId"))
        admitted.systemPath(id).assert().isEqualTo("native.aggregateId")
        assertThrows<QueryExecutionException> { admitted.field(QueryField("name")) }
            .message.assert().isEqualTo("A [QueryField] node is not a node of this admitted query.")
        admitted.model.assert().isEqualTo(schema.model)
        admitted.provisional.assert().isFalse()
        admitted.entry.assert().isEqualTo(QueryEntry.IN_PROCESS)
        admitted.timeout.assert().isNull()
        admitted.toString().assert().contains("IN_PROCESS")
        val binding = QueryBackendBinding(RecordingBackend(), UnavailableQueryStorageAdapter("no storage"))
        binding.backend.aggregateType.assert().isEqualTo(AggregateType("context", "aggregate"))
    }

    @Test
    fun `windows and pages reject what a backend cannot answer`() {
        assertThrows<IllegalArgumentException> { PageWindow.Offset(-1, 1, withTotal = false) }
        assertThrows<IllegalArgumentException> { PageWindow.Offset(0, -1, withTotal = false) }
        assertThrows<IllegalArgumentException> { PageWindow.Offset(0, 1, withTotal = false, totalLimit = 10) }
        assertThrows<IllegalArgumentException> { PageWindow.Offset(0, 1, withTotal = true, totalLimit = 0) }
        assertThrows<IllegalArgumentException> { PageWindow.Keyset(null, 0) }
        assertThrows<IllegalArgumentException> { GroupWindow.First(0) }
        assertThrows<QueryExecutionException> { BackendPage(emptyList(), -1) }
            .message.assert().isEqualTo("Backend page total must be greater than or equal to 0.")
        assertThrows<QueryExecutionException> { BackendPage(listOf(row("a")), positions = emptyList()) }
            .message.assert().isEqualTo("Every row needs one cursor position.")
    }

    // D150: timeouts.

    @Test
    fun `every shape fails with the query timeout when the backend outlasts it`() {
        runTest {
            val timeout = Duration.ofMillis(100)
            val slow = RecordingBackend(
                pages = {
                    delay(SLOW)
                    BackendPage(listOf(row("a")), 1, listOf(CursorPosition(listOf(1L))))
                },
                records = { flow { delay(SLOW) } },
                groups = { flow { delay(SLOW) } },
                counts = {
                    delay(SLOW)
                    1
                },
            )
            val shapes = listOf<suspend () -> Any?>(
                {
                    slow.single(
                        QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(timeout)
                    )
                },
                { slow.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema).withTimeout(timeout)) },
                {
                    slow.cursor(
                        QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema).withTimeout(timeout)
                    )
                },
                {
                    slow.countBounded(
                        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema).withTimeout(timeout)
                    )
                },
                {
                    slow.list(
                        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(timeout)
                    ).toList()
                },
                {
                    val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
                    slow.aggregate(QueryAdmission.Trusted.aggregate(query, schema).withTimeout(timeout)).toList()
                },
            )
            shapes.forEach { shape ->
                assertThrows<QueryTimeoutException> { shape() }.apply {
                    this.timeout.assert().isEqualTo(timeout)
                    errorCode.assert().isEqualTo(QueryErrors.TIMEOUT)
                    errorMsg.assert().isEqualTo("Query exceeded its execution timeout of 100 ms.")
                }
            }
        }
    }

    @Test
    fun `a backend within the timeout answers as without one`() {
        runTest {
            val timeout = Duration.ofSeconds(1)
            val backend = RecordingBackend(
                pages = {
                    delay(10)
                    BackendPage(emptyList(), 0)
                },
                records = { flowOf(row("a")) },
                counts = { 2 },
            )
            backend.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(timeout))
                .assert().isNull()
            backend.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema).withTimeout(timeout))
                .total.assert().isEqualTo(0)
            backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(timeout)).toList()
                .assert().hasSize(1)
            backend.countBounded(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema).withTimeout(timeout))
                .assert().isEqualTo(CountResult(2, exact = true))
            val admitted = QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema)
            admitted.withTimeout(null).assert().isSameAs(admitted)
            admitted.withTimeout(timeout).timeout.assert().isEqualTo(timeout)
        }
    }

    @Test
    fun `an enclosing timeout of the caller stays the caller's cancellation`() {
        runTest {
            val timeout = Duration.ofSeconds(1)
            val slow = RecordingBackend(
                pages = {
                    delay(SLOW)
                    BackendPage(emptyList(), 0)
                },
                records = { flow { delay(SLOW) } },
            )
            assertThrows<TimeoutCancellationException> {
                withTimeout(50) {
                    slow.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(timeout))
                }
            }
            assertThrows<TimeoutCancellationException> {
                withTimeout(50) {
                    slow.list(
                        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(timeout)
                    ).toList()
                }
            }
            assertThrows<TimeoutCancellationException> {
                withTimeout(50) { slow.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema)) }
            }
        }
    }

    // Storage failures.

    @Test
    fun `a storage failure of every shape is reported as the storage failure, its cause kept server-side`() {
        runTest {
            val driver = IllegalStateException("driver text with values")
            val failing = RecordingBackend(
                pages = { fail(driver) },
                records = { flow { fail(driver) } },
                groups = { flow { fail(driver) } },
                counts = { fail(driver) },
            )
            val timeout = Duration.ofSeconds(1)
            val shapes = listOf<suspend (Duration?) -> Any?>(
                { failing.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema).withTimeout(it)) },
                { failing.paged(QueryAdmission.Trusted.paged(PagedQuery(MatchAllFilter), schema).withTimeout(it)) },
                { failing.cursor(QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter), schema).withTimeout(it)) },
                {
                    failing.countBounded(
                        QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema).withTimeout(it)
                    )
                },
                {
                    failing.list(
                        QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema).withTimeout(it)
                    ).toList()
                },
                {
                    val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
                    failing.aggregate(QueryAdmission.Trusted.aggregate(query, schema).withTimeout(it)).toList()
                },
            )
            listOf(null, timeout).forEach { limit ->
                shapes.forEach { shape ->
                    assertThrows<QueryExecutionException> { shape(limit) }.apply {
                        message.assert().isEqualTo("Query storage failed.")
                        // Stack trace recovery (assertions on) may copy the error across a timeout scope.
                        generateSequence(cause) { it.cause }.last().assert().isSameAs(driver)
                    }
                }
            }
        }
    }

    @Test
    fun `a query error a backend throws reaches the caller as it is`() {
        runTest {
            val rejection = QueryViolation.InvalidCursor.rejection()
            val failing = RecordingBackend(pages = { throw rejection }, records = { flow { throw rejection } })
            assertThrows<RuntimeException> {
                failing.single(QueryAdmission.Trusted.single(SingleQuery(MatchAllFilter), schema))
            }.assert().isSameAs(rejection)
            assertThrows<RuntimeException> {
                failing.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)).toList()
            }.assert().isSameAs(rejection)
            storageFault(rejection).assert().isSameAs(rejection)
            storageFault(IllegalStateException()).assert().isInstanceOf(QueryExecutionException::class.java)
        }
    }

    private class RecordingBackend(
        override val aggregateType: AggregateType = AggregateType("context", "aggregate"),
        private val pages: suspend (PageWindow) -> BackendPage = { BackendPage(emptyList(), 0, emptyList()) },
        private val groups: () -> Flow<ObjectNode> = { emptyFlow() },
        private val records: () -> Flow<ObjectNode> = { emptyFlow() },
        private val counts: suspend () -> Long = { 0 },
    ) : QueryBackend {
        val windows = mutableListOf<PageWindow>()
        val groupWindows = mutableListOf<GroupWindow>()
        val aggregations = mutableListOf<AggregationQuery>()
        val counted = mutableListOf<AdmittedQuery<CountQuery>>()
        var streams = 0
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun stream(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> = flow {
            streams++
            emitAll(records())
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            windows += window
            return pages(window)
        }

        override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
            counted += query
            return counts()
        }

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            aggregations += query.query
            groupWindows += window
            emitAll(groups())
        }
    }

    private companion object {
        const val SLOW = 10_000L

        fun fail(error: Throwable): Nothing = throw error

        fun row(name: String): ObjectNode = WowJson.mapper.createObjectNode().put("name", name)

        fun json(text: String): ObjectNode = WowJson.mapper.readTree(text) as ObjectNode

        fun group(name: String, count: Long): ObjectNode = json("""{"name":"$name","count":$count}""")

        fun bucket(day: Long, count: Long): ObjectNode = json("""{"day":$day,"count":$count}""")

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
