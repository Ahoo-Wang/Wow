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

package me.ahoo.wow.query.tck
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.chunks
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.AMOUNT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.MAX_LIST
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.OTHER_TENANT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.T0
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.TENANT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.id
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The keyset cases of the snapshot contract: cursors and streams. They read only [SnapshotQueryFixtures]'s fixtures and
 * hooks; see [SnapshotQueryFixtures] for the chain of families it is part of.
 */
abstract class SnapshotKeysetCases internal constructor() : SnapshotRecordCases() {
    // Cursors

    @Test
    fun `cursor traverses tied records once each in sort order`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.DESC))
        val pages = mutableListOf<List<String>>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = sort, size = 2, cursor = cursor)
            val page = fixture.backend.cursor(QueryAdmission.Trusted.cursor(query, fixture.schema))
            pages += page.list.map { it.id() }
            cursor = page.nextCursor
        } while (cursor != null && pages.size < MAX_LIST)
        // Tied records follow the sort's last direction: descending, so by the key descending.
        pages.assert().containsExactly(listOf("o2", "o1"), listOf("o4", "o3"), listOf("o5"))
        Unit
    }

    @Test
    fun `cursor supports descending multi field sort`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(QueryField("state.quantity"), Sort.Direction.DESC), Sort(AMOUNT, Sort.Direction.DESC))
        val first = fixture.backend.cursor(
            QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 3), fixture.schema)
        )
        first.list.map { it.id() }.assert().containsExactly("o5", "o4", "o3")
        val second = fixture.backend.cursor(
            QueryAdmission.Trusted.cursor(
                CursorQuery(MatchAllFilter, sort = sort, size = 3, cursor = first.nextCursor),
                fixture.schema
            ),
        )
        second.list.map { it.id() }.assert().containsExactly("o2", "o1")
        second.nextCursor.assert().isNull()
        Unit
    }

    @Test
    fun `records tied on a sort follow its last direction, in a list, a page and a cursor`() = blocking {
        // One tenant: t1, t2 and t3 tie on their status, PAID, above n1's NEW.
        val paid = """{"name":"Tied","status":"PAID","amount":1,"quantity":1,"createdAt":$T0}"""
        val fixture = given(
            listOf(
                snapshot("t2", paid),
                snapshot("n1", """{"name":"New","status":"NEW","amount":1,"quantity":1,"createdAt":$T0}"""),
                snapshot("t1", paid),
                snapshot("t3", paid),
            ),
        )
        val status = QueryField("state.status")
        fun pages(sort: List<Sort>): List<String> = (1..2).flatMap { index ->
            val query =
                PagedQuery(MatchAllFilter, sort = sort, pagination = Pagination(index, 2), total = TotalMode.NONE)
            blocking { fixture.backend.paged(QueryAdmission.Trusted.paged(query, fixture.schema)).list.map { it.id() } }
        }
        fun cursor(sort: List<Sort>): List<String> {
            val seen = mutableListOf<String>()
            var next: String? = null
            do {
                val query = CursorQuery(MatchAllFilter, sort = sort, size = 2, cursor = next)
                val page = blocking { fixture.backend.cursor(QueryAdmission.Trusted.cursor(query, fixture.schema)) }
                seen += page.list.map { it.id() }
                next = page.nextCursor
            } while (next != null && seen.size < MAX_LIST)
            return seen
        }
        val descending = listOf(Sort(status, Sort.Direction.DESC))
        val ascending = listOf(Sort(status, Sort.Direction.ASC))
        // Descending, the key descends too: the newest-first order an index on (status, key) gives read backward.
        fixture.ids(MatchAllFilter, descending).assert().containsExactly("t3", "t2", "t1", "n1")
        pages(descending).assert().containsExactly("t3", "t2", "t1", "n1")
        // Ascending, it ascends; with no sort at all, the key alone ascends.
        fixture.ids(MatchAllFilter, ascending).assert().containsExactly("n1", "t1", "t2", "t3")
        pages(ascending).assert().containsExactly("n1", "t1", "t2", "t3")
        fixture.ids(MatchAllFilter).assert().containsExactly("n1", "t1", "t2", "t3")
        if (fixture.schema.storage.paging.keyset != SupportMode.NONE) {
            cursor(descending).assert().containsExactly("t3", "t2", "t1", "n1")
            cursor(ascending).assert().containsExactly("n1", "t1", "t2", "t3")
        }
        Unit
    }

    @Test
    fun `a list, a page and a cursor break ties by tenant before aggregate id`() = blocking {
        // The same aggregate id in two tenants, read across them (trusted, unscoped); `aaa` sorts first by id alone.
        val shared = """{"name":"Shared","status":"PAID","amount":1,"quantity":1,"createdAt":$T0}"""
        val fixture = given(
            listOf("dup", "solo", "aaa").map { snapshot(it, shared) } + snapshot("dup", shared, tenantId = OTHER_TENANT),
        )
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.ASC))
        val byKey = listOf(OTHER_TENANT to "dup", TENANT to "aaa", TENANT to "dup", TENANT to "solo")
        fun ObjectNode.key() = path("tenantId").stringValue() to id()
        fixture.list(ListQuery(MatchAllFilter, sort = sort)).map { it.key() }.assert().isEqualTo(byKey)
        val paged = PagedQuery(MatchAllFilter, sort = sort, total = TotalMode.NONE)
        fixture.backend.paged(QueryAdmission.Trusted.paged(paged, fixture.schema)).list.map { it.key() }.assert()
            .isEqualTo(byKey)
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val seen = mutableListOf<Pair<String, String>>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = sort, size = 1, cursor = cursor)
            val page = fixture.backend.cursor(QueryAdmission.Trusted.cursor(query, fixture.schema))
            seen += page.list.map { it.key() }
            cursor = page.nextCursor
        } while (cursor != null && seen.size < MAX_LIST)
        seen.assert().isEqualTo(byKey)
        Unit
    }

    @Test
    fun `cursor over decimals walks each record once, a value no double holds included`() = blocking {
        val fixture = given(
            listOf(
                snapshot("d1", """{"amount":1.00000000000000000001}"""),
                snapshot("d2", """{"amount":1}"""),
                snapshot("d3", """{"amount":2}"""),
            ),
        )
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = sort, size = 1, cursor = cursor)
            val page = fixture.backend.cursor(QueryAdmission.Trusted.cursor(query, fixture.schema))
            seen += page.list.map { it.id() }
            cursor = page.nextCursor
        } while (cursor != null && seen.size < MAX_LIST)
        // A storage that orders by a double ties d1 with d2; none repeats or skips a record.
        seen.assert().hasSize(3).containsExactlyInAnyOrder("d1", "d2", "d3").endsWith("d3")
        Unit
    }

    @Test
    fun `cursor returns an empty terminal page`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val page = fixture.backend.cursor(
            QueryAdmission.Trusted.cursor(CursorQuery(AggregateIdFilter("none"), size = 2), fixture.schema),
        )
        page.list.assert().isEmpty()
        page.nextCursor.assert().isNull()
        Unit
    }

    @Test
    fun `cursor token issued for another sort is the invalid cursor`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val byAmount = CursorQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.ASC)), size = 1)
        val token = checkNotNull(
            fixture.backend.cursor(QueryAdmission.Trusted.cursor(byAmount, fixture.schema)).nextCursor
        )
        val byName = CursorQuery(
            MatchAllFilter,
            sort = listOf(Sort(QueryField("state.name"), Sort.Direction.ASC)),
            size = 1,
            cursor = token
        )
        val error = runCatching { fixture.backend.cursor(QueryAdmission.Trusted.cursor(byName, fixture.schema)) }
            .exceptionOrNull()
        (error as QueryRequestException).violation.assert().isEqualTo(QueryViolation.InvalidCursor)
        Unit
    }

    // Streams

    @Test
    fun `a stream reads keyset chunks, each record with its position, and resumes strictly after one`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.DESC))
        fun chunks(size: Int, after: String? = null, limit: Int? = null) = fixture.backend.chunks(
            QueryAdmission.Trusted.cursor(
                CursorQuery(MatchAllFilter, sort = sort, size = size, cursor = after),
                fixture.schema
            ),
            limit,
        )
        val all = chunks(2).toList()
        all.map { it.record.id() }.assert().containsExactly("o2", "o1", "o4", "o3", "o5")
        all.map { it.position }.assert().doesNotHaveDuplicates()
        // A position is the cursor's own token: a cursor page ends on it.
        fixture.backend.cursor(
            QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 2), fixture.schema),
        ).nextCursor.assert().isEqualTo(all[1].position)
        // Resumed after any record, across a chunk boundary or within one.
        all.indices.forEach { index ->
            chunks(2, all[index].position).toList().map { it.record.id() }.assert()
                .isEqualTo(all.drop(index + 1).map { it.record.id() })
        }
        chunks(2, limit = 3).toList().map { it.record.id() }.assert().containsExactly("o2", "o1", "o4")
        chunks(5).toList().assert().hasSize(5)
        Unit
    }

    @Test
    fun `a stream sees a record written after its position, between two chunks`() = blocking {
        val fixture = given(orders())
        assumeSupported(fixture.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))
        val read = fixture.backend.chunks(
            QueryAdmission.Trusted.cursor(CursorQuery(MatchAllFilter, sort = sort, size = 2), fixture.schema),
        ).map { it.record.id() }.onEach { id ->
            if (id == "o2") {
                store(
                    listOf(
                        snapshot(
                            "early",
                            """{"name":"Early","status":"NEW","amount":1,"quantity":1,"createdAt":$T0}"""
                        ),
                        snapshot("late", """{"name":"Late","status":"NEW","amount":45,"quantity":1,"createdAt":$T0}"""),
                    ),
                )
            }
        }.toList()
        // No snapshot isolation: the later record is read, the one before the position is not; none twice.
        read.assert().containsExactly("o1", "o2", "o3", "o4", "late", "o5")
        Unit
    }
}
