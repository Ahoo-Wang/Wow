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

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregateIdFilter
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
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.PagingSupport
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.UnavailableQueryStorageAdapter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

internal class BackendQueriesTest : BackendQueriesFixtures() {
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
}
