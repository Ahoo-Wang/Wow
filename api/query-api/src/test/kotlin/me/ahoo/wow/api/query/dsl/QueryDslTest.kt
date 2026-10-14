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

package me.ahoo.wow.api.query.dsl

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.SnapshotProperties
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import org.junit.jupiter.api.Test

class QueryDslTest {
    private val name = QueryField("state.name")
    private val paid = EqualFilter(QueryField("state.status"), "PAID")
    private val byName = listOf(Sort(name, Sort.Direction.ASC))

    @Test
    fun `every kind defaults to every record`() {
        singleQuery { }.assert().isEqualTo(SingleQuery(MatchAllFilter))
        listQuery { }.assert().isEqualTo(ListQuery(MatchAllFilter))
        pagedQuery { }.assert().isEqualTo(PagedQuery(MatchAllFilter))
        cursorQuery { }.assert().isEqualTo(CursorQuery(MatchAllFilter))
        countQuery { }.assert().isEqualTo(CountQuery(MatchAllFilter))
    }

    @Test
    fun `a list query`() {
        listQuery {
            filter { "state.status" eq "PAID" }
            projection { include("state.name") }
            sort { "state.name".asc() }
            limit(50)
        }.assert().isEqualTo(ListQuery(paid, Projection(include = listOf(name)), byName, 50))
    }

    @Test
    fun `a paged query`() {
        pagedQuery {
            filter(paid)
            projection(Projection(exclude = listOf(name)))
            sort(byName)
            pagination {
                index(2)
                size(20)
            }
            total(TotalMode.EXACT)
        }.assert().isEqualTo(
            PagedQuery(paid, Projection(exclude = listOf(name)), byName, Pagination(2, 20), TotalMode.EXACT),
        )
        pagedQuery { pagination(Pagination(3, 5)) }.pagination.assert().isEqualTo(Pagination(3, 5))
    }

    @Test
    fun `a single query`() {
        singleQuery {
            filter { "state.status" eq "PAID" }
            sort { SnapshotProperties.VERSION.desc() }
        }.assert().isEqualTo(SingleQuery(paid, sort = listOf(Sort(QueryField("version"), Sort.Direction.DESC))))
    }

    @Test
    fun `a cursor query`() {
        cursorQuery {
            filter { "state.status" eq "PAID" }
            sort { "state.name".asc() }
            size(25)
            cursor("next")
        }.assert().isEqualTo(CursorQuery(paid, sort = byName, size = 25, cursor = "next"))
    }

    @Test
    fun `a count query`() {
        countQuery {
            filter { "state.status" eq "PAID" }
            limit(1000)
        }.assert().isEqualTo(CountQuery(paid, 1000))
        countQuery { filter(paid) }.assert().isEqualTo(CountQuery(paid))
    }

    @Test
    fun `sort, projection and pagination stand alone`() {
        sort {
            nested("state")
            "name".asc()
            QueryField("state.price").desc()
            sort(Sort(QueryField("version"), Sort.Direction.ASC))
        }.assert().containsExactly(
            Sort(name, Sort.Direction.ASC),
            Sort(QueryField("state.price"), Sort.Direction.DESC),
            Sort(QueryField("version"), Sort.Direction.ASC),
        )
        sort { QueryField("lastEventTime").asc() }.assert()
            .containsExactly(Sort(QueryField("lastEventTime"), Sort.Direction.ASC))
        projection {
            nested(QueryField("state"))
            include("name", "state.price")
            include(QueryField("address"))
            exclude("secret")
            exclude(QueryField("state.token"))
        }.assert().isEqualTo(
            Projection(
                include = listOf(name, QueryField("state.price"), QueryField("state.address")),
                exclude = listOf(QueryField("state.secret"), QueryField("state.token")),
            ),
        )
        pagination { index(4) }.assert().isEqualTo(Pagination(4, Pagination.DEFAULT.size))
    }

    @Test
    fun `the system fields of a snapshot`() {
        SnapshotProperties.OPERATOR__ID.assert().isEqualTo(QueryField("operator.id"))
        SnapshotProperties.FIRST_OPERATOR__CROSS_SCOPE.assert().isEqualTo(QueryField("firstOperator.crossScope"))
        SnapshotProperties.tag("region").assert().isEqualTo(QueryField("tags.region"))
        SnapshotProperties.STATE.assert().isEqualTo(QueryField("state"))
    }
}
