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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.AMOUNT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.DAY
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.OTHER_TENANT
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.T0
import me.ahoo.wow.query.tck.QueryBackendSpec.Companion.id
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The record cases of the snapshot contract: sort, list, single and projection, offset pages and counts. They read only
 * [SnapshotQueryFixtures]'s fixtures and hooks; see [SnapshotQueryFixtures] for the chain of families it is part of.
 */
abstract class SnapshotRecordCases internal constructor() : SnapshotFilterCases() {
    // Sort, list, single and projection

    @Test
    fun `list sorts by several fields in either direction and honors the limit`() = blocking {
        val fixture = given(orders())
        val sort = listOf(Sort(QueryField("state.status"), Sort.Direction.ASC), Sort(AMOUNT, Sort.Direction.DESC))
        fixture.list(ListQuery(MatchAllFilter, sort = sort, limit = 3)).map { it.id() }
            .assert().containsExactly("o5", "o4", "o3")
        fixture.ids(MatchAllFilter, listOf(Sort(AMOUNT, Sort.Direction.DESC)))
            .assert().containsExactly("o5", "o4", "o3", "o2", "o1")
        Unit
    }

    @Test
    fun `list without a limit streams every record`() = blocking {
        val fixture = given(orders())
        assumeSupported(
            fixture.schema.storage.paging.unboundedStream,
            "storage.paging.unboundedStream",
            "unbounded streams"
        )
        fixture.list(ListQuery(MatchAllFilter)).assert().hasSize(5)
        Unit
    }

    @Test
    fun `single returns the first record in sort order, or none`() = blocking {
        val fixture = given(orders())
        val first = fixture.backend.single(
            QueryAdmission.Trusted.single(
                SingleQuery(MatchAllFilter, sort = listOf(Sort(AMOUNT, Sort.Direction.DESC))),
                fixture.schema
            ),
        )
        first?.id().assert().isEqualTo("o5")
        fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("none")), fixture.schema))
            .assert().isNull()
        Unit
    }

    @Test
    fun `single exposes the snapshot record shape`() = blocking {
        val fixture = given(orders())
        val record = checkNotNull(
            fixture.backend.single(QueryAdmission.Trusted.single(SingleQuery(AggregateIdFilter("o3")), fixture.schema)),
        )
        record.path("tenantId").stringValue().assert().isEqualTo(OTHER_TENANT)
        record.path("aggregateId").stringValue().assert().isEqualTo("o3")
        record.path("version").intValue().assert().isEqualTo(3)
        record.path("ownerId").stringValue().assert().isEqualTo("owner-1")
        record.path("spaceId").stringValue().assert().isEqualTo("space-1")
        record.path("deleted").booleanValue().assert().isFalse()
        record.path("firstEventTime").longValue().assert().isEqualTo(T0 - DAY)
        record.path("lastEventTime").longValue().assert().isEqualTo(T0 + 2 * DAY)
        record.path("state").path("name").stringValue().assert().isEqualTo("Gamma")
        Unit
    }

    @Test
    fun `projection preserves scalar and object subtree semantics`() = blocking {
        val fixture = given(orders())
        suspend fun query(projection: Projection): ObjectNode =
            fixture.list(ListQuery(AggregateIdFilter("o1"), projection = projection, limit = 1)).single()

        query(Projection(include = listOf(QueryField("state")))).path("state").let { state ->
            state.has("name").assert().isTrue()
            state.has("lines").assert().isTrue()
        }
        query(Projection(include = listOf(QueryField("state.name")))).path("state").let { state ->
            state.has("name").assert().isTrue()
            state.has("lines").assert().isFalse()
        }
        query(Projection(exclude = listOf(QueryField("state")))).has("state").assert().isFalse()
        query(Projection(exclude = listOf(QueryField("state.name")))).path("state").let { state ->
            state.has("name").assert().isFalse()
            state.has("lines").assert().isTrue()
        }
        Unit
    }

    @Test
    fun `projection of a leaf under an array of arrays keeps the nesting`() = blocking {
        val fixture = given(
            listOf(snapshot("g1", """{"name":"G","grid":[[{"city":"x","zip":"1"},{"city":"y"}],[{"city":"z"}]]}""")),
        )
        val projected = fixture.list(
            ListQuery(
                AggregateIdFilter("g1"),
                projection = Projection(include = listOf(QueryField("state.grid.city"))),
                limit = 1,
            ),
        ).single()
        projected.path("state").path("grid").toString()
            .assert().isEqualTo("""[[{"city":"x"},{"city":"y"}],[{"city":"z"}]]""")
        Unit
    }

    @Test
    fun `projection keeps an element's position when it lacks the projected leaf`() = blocking {
        val fixture = given(
            listOf(snapshot("p1", """{"lines":[{"sku":"A","price":1},{"price":2},{"sku":"C","price":3}]}""")),
        )
        val projected = fixture.list(
            ListQuery(
                AggregateIdFilter("p1"),
                projection = Projection(include = listOf(QueryField("state.lines.sku"))),
                limit = 1,
            ),
        ).single()
        projected.path("state").path("lines").toString().assert().isEqualTo("""[{"sku":"A"},{},{"sku":"C"}]""")
        Unit
    }

    // Pages and counts

    @Test
    fun `paged returns its window with the exact total`() = blocking {
        val fixture = given(orders())
        val query = PagedQuery(
            MatchAllFilter,
            sort = listOf(Sort(AMOUNT, Sort.Direction.ASC)),
            pagination = Pagination(2, 2),
            total = TotalMode.EXACT,
        )
        val page = fixture.backend.paged(QueryAdmission.Trusted.paged(query, fixture.schema))
        page.list.map { it.id() }.assert().containsExactly("o3", "o4")
        page.total.assert().isEqualTo(5L)
        page.exact.assert().isTrue()
        Unit
    }

    @Test
    fun `paged bounded total stops at its limit and says it is not exact`() = blocking {
        val fixture = given(orders())
        val query = PagedQuery(
            MatchAllFilter,
            sort = listOf(Sort(AMOUNT, Sort.Direction.ASC)),
            pagination = Pagination(1, 2)
        )
        val admitted = QueryAdmission.Trusted.paged(query, fixture.schema)
        fixture.backend.paged(admitted, totalLimit = 3).let { page ->
            page.list.map { it.id() }.assert().containsExactly("o1", "o2")
            page.total.assert().isEqualTo(3L)
            page.exact.assert().isFalse()
        }
        fixture.backend.paged(admitted, totalLimit = 5).let { page ->
            page.total.assert().isEqualTo(5L)
            page.exact.assert().isTrue()
        }
        fixture.backend.paged(admitted, totalLimit = 10).let { page ->
            page.total.assert().isEqualTo(5L)
            page.exact.assert().isTrue()
        }
        Unit
    }

    @Test
    fun `paged without a total counts nothing`() = blocking {
        val fixture = given(orders())
        val query = PagedQuery(MatchAllFilter, pagination = Pagination(1, 10), total = TotalMode.NONE)
        val page = fixture.backend.paged(QueryAdmission.Trusted.paged(query, fixture.schema))
        page.list.assert().hasSize(5)
        page.total.assert().isNull()
        page.exact.assert().isFalse()
        Unit
    }

    @Test
    fun `count is exact below its limit and bounded above it`() = blocking {
        val fixture = given(orders())
        suspend fun count(filter: FilterExpression, limit: Int?): CountResult =
            fixture.backend.countBounded(QueryAdmission.Trusted.count(CountQuery(filter, limit), fixture.schema))

        count(MatchAllFilter, 3).assert().isEqualTo(CountResult(3, exact = false))
        count(MatchAllFilter, 5).assert().isEqualTo(CountResult(5, exact = true))
        count(MatchAllFilter, 10).assert().isEqualTo(CountResult(5, exact = true))
        count(MatchAllFilter, null).assert().isEqualTo(CountResult(5, exact = true))
        count(EqualFilter(QueryField("state.status"), "PAID"), 1).assert().isEqualTo(CountResult(1, exact = false))
        count(EqualFilter(QueryField("state.status"), "NONE"), 1).assert().isEqualTo(CountResult(0, exact = true))
        Unit
    }
}
