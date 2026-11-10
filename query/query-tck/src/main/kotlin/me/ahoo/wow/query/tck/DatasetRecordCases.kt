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
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.chunks
import me.ahoo.wow.query.backend.countBounded
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.AMOUNT
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.CITY
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.CREATED_AT
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.DATES
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.ID
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.MAX_LIST
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.MICROS
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.SEMANTIC
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.SEMANTIC_FIELDS
import me.ahoo.wow.query.tck.DatasetQueryBackendSpec.Companion.TENANT_COLUMN
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * The record cases of the dataset contract: the semantics matrix, records, the tenant column, single, offset and
 * keyset pages, counts, streams, NOR and expressions. They read only [DatasetQueryFixtures]'s fixtures and hooks;
 * see [DatasetQueryFixtures] for the chain of families it is part of.
 */
abstract class DatasetRecordCases internal constructor() : DatasetQueryFixtures() {
    @TestFactory
    fun `filter semantics matrix`(): List<DynamicTest> {
        val semantic = blocking { target(SEMANTIC) }
        return FilterSemantics.CASES.map { case ->
            DynamicTest.dynamicTest(case.id) {
                Assumptions.assumeFalse(case.id in semanticDivergences) {
                    "Known divergence from the canonical semantics: ${case.id}."
                }
                val probes = checkNotNull(SemanticProbe.ALL[case.shape])
                val byId = probes.associateBy(QueryBackendSpec::probeId)
                val filter = AndFilter(
                    listOf(
                        IdsFilter(probes.map(QueryBackendSpec::probeId)),
                        case.filter(QueryField(SEMANTIC_FIELDS.getValue(case.shape))),
                    ),
                )
                blocking { semantic.list(ListQuery(filter, limit = MAX_LIST)) }
                    .map { checkNotNull(byId[it.path(ID).stringValue()]) { "Unexpected record [$it]." }.name }
                    .toSet().assert().describedAs(case.id).isEqualTo(case.matches)
            }
        }
    }

    @Test
    fun `records sort, project and limit`() = blocking {
        val rows = orders.list(
            ListQuery(
                EqualFilter(TENANT_COLUMN, "t1"),
                projection = Projection(include = listOf(QueryField(ID), AMOUNT)),
                sort = listOf(Sort(AMOUNT, Sort.Direction.DESC)),
                limit = 3,
            ),
        )
        rows.map { it.path(ID).stringValue() }.assert().containsExactly("o3", "o2", "o1")
        rows.forEach { it.propertyNames().assert().containsExactlyInAnyOrder(ID, "amount") }
        rows.first().get("amount").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.00"))
        Unit
    }

    @Test
    fun `the tenant filter reads the dataset's tenant column`() = blocking {
        orders.count(TenantIdFilter("t2")).assert().isEqualTo(1L)
        orders.count(TenantIdFilter("t1")).assert().isEqualTo(5L)
        Unit
    }

    @Test
    fun `a single record is the first in sort order, or none`() = blocking {
        val first = orders.backend.single(
            QueryAdmission.Trusted.single(
                SingleQuery(EqualFilter(TENANT_COLUMN, "t1"), sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))),
                orders.schema,
            ),
        )
        first?.path(ID)?.stringValue().assert().isEqualTo("o4")
        orders.backend.single(QueryAdmission.Trusted.single(SingleQuery(EqualFilter(CITY, "Nowhere")), orders.schema))
            .assert().isNull()
        Unit
    }

    @Test
    fun `offset pages carry the total`() = blocking {
        val query = PagedQuery(
            MatchAllFilter,
            sort = listOf(Sort(QueryField(ID), Sort.Direction.ASC)),
            pagination = Pagination(2, 2),
            total = TotalMode.EXACT,
        )
        val page = orders.backend.paged(QueryAdmission.Trusted.paged(query, orders.schema))
        page.total.assert().isEqualTo(6L)
        page.list.map { it.path(ID).stringValue() }.assert().containsExactly("o3", "o4")
        Unit
    }

    @Test
    fun `offset pages over a sort with ties page every record once, ties in identity order`() = blocking {
        val all = orders.list(ListQuery(EqualFilter(TENANT_COLUMN, "t1"), limit = MAX_LIST))
            .map { it.path(ID).stringValue() }.sorted()
        val paged = (1..3).flatMap { index ->
            val query = PagedQuery(
                EqualFilter(TENANT_COLUMN, "t1"),
                // Every record ties on its tenant.
                sort = listOf(Sort(TENANT_COLUMN, Sort.Direction.ASC)),
                pagination = Pagination(index, 2),
                total = TotalMode.NONE,
            )
            orders.backend.paged(
                QueryAdmission.Trusted.paged(query, orders.schema)
            ).list.map { it.path(ID).stringValue() }
        }
        paged.assert().isEqualTo(all)
        orders.list(
            ListQuery(
                EqualFilter(TENANT_COLUMN, "t1"),
                sort = listOf(Sort(TENANT_COLUMN, Sort.Direction.ASC)),
                limit = 2
            ),
        ).map { it.path(ID).stringValue() }.assert().isEqualTo(all.take(2))
        Unit
    }

    @Test
    fun `counts are bounded by their limit`() = blocking {
        val count = orders.backend.countBounded(
            QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, 3), orders.schema)
        )
        count.assert().isEqualTo(CountResult(3, exact = false))
        Unit
    }

    @Test
    fun `rows tied on a sort follow its last direction, in a list, a page and a cursor`() = blocking {
        // Tenant t1: o1 and o2 tie on Hangzhou, o3 and o4 on Beijing; o5 is alone in Shanghai.
        val t1 = EqualFilter(TENANT_COLUMN, "t1")
        fun list(sort: List<Sort>) =
            blocking { orders.list(ListQuery(t1, sort = sort, limit = MAX_LIST)).map { it.path(ID).stringValue() } }
        fun pages(sort: List<Sort>) = (1..3).flatMap { index ->
            val query = PagedQuery(t1, sort = sort, pagination = Pagination(index, 2), total = TotalMode.NONE)
            blocking {
                orders.backend.paged(QueryAdmission.Trusted.paged(query, orders.schema)).list
                    .map { it.path(ID).stringValue() }
            }
        }
        val descending = listOf(Sort(CITY, Sort.Direction.DESC))
        val ascending = listOf(Sort(CITY, Sort.Direction.ASC))
        // Descending, the identity descends too; ascending, it ascends.
        list(descending).assert().containsExactly("o5", "o2", "o1", "o4", "o3")
        pages(descending).assert().containsExactly("o5", "o2", "o1", "o4", "o3")
        list(ascending).assert().containsExactly("o3", "o4", "o1", "o2", "o5")
        pages(ascending).assert().containsExactly("o3", "o4", "o1", "o2", "o5")
        assumeSupported(orders.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        walk(descending.single()).assert().containsExactly("o5", "o6", "o2", "o1", "o4", "o3")
        Unit
    }

    @Test
    fun `keyset pages walk every record once in sort order`() = blocking {
        assumeSupported(orders.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        walk(Sort(AMOUNT, Sort.Direction.DESC)).assert().containsExactly("o6", "o3", "o2", "o1", "o5", "o4")
        walk(Sort(CREATED_AT, Sort.Direction.ASC)).assert().containsExactly("o1", "o6", "o2", "o3", "o4", "o5")
        walk(Sort(CITY, Sort.Direction.ASC)).assert().containsExactly("o3", "o4", "o1", "o2", "o6", "o5")
        Unit
    }

    @Test
    fun `a date sorts by time, instants a millisecond apart included`() = blocking {
        val dates = target(DATES)
        val ascending = Sort(CREATED_AT, Sort.Direction.ASC)
        // By text, `…00.001Z` and `…00.010Z` would come before `…00Z`.
        dates.list(ListQuery(MatchAllFilter, sort = listOf(ascending))).map { it.path(ID).stringValue() }
            .assert().containsExactly("d2", "d1", "d3")
        assumeSupported(dates.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        walk(ascending, dates, size = 1).assert().containsExactly("d2", "d1", "d3")
        walk(Sort(CREATED_AT, Sort.Direction.DESC), dates, size = 1).assert().containsExactly("d3", "d1", "d2")
        Unit
    }

    @Test
    fun `a stream reads keyset chunks with positions and resumes strictly after one`() = blocking {
        assumeSupported(orders.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(AMOUNT, Sort.Direction.DESC))
        fun chunks(after: String? = null) = orders.backend.chunks(
            QueryAdmission.Trusted.cursor(
                CursorQuery(MatchAllFilter, sort = sort, size = 2, cursor = after),
                orders.schema
            ),
        )
        val all = chunks().toList()
        all.map { it.record.path(ID).stringValue() }.assert().isEqualTo(walk(sort.single()))
        all.indices.forEach { index ->
            chunks(
                all[index].position
            ).toList().map { it.position }.assert().isEqualTo(all.drop(index + 1).map { it.position })
        }
        Unit
    }

    @Test
    fun `a cursor over instants a microsecond apart walks each record once, in time order`() = blocking {
        val micros = target(MICROS)
        assumeSupported(micros.schema.storage.paging.keyset, "storage.paging.keyset", "keyset pages")
        val sort = listOf(Sort(CREATED_AT, Sort.Direction.ASC))
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = sort, size = 1, cursor = cursor)
            val page = micros.backend.cursor(QueryAdmission.Trusted.cursor(query, micros.schema))
            ids += page.list.map { it.path(ID).stringValue() }
            cursor = page.nextCursor
        } while (cursor != null && ids.size <= MAX_LIST)
        ids.assert().containsExactly("u2", "u1", "u3")
        Unit
    }

    @Test
    fun `NOR holds for a record whose condition has no value`() = blocking {
        val discount = QueryField("discount")
        val noTenth = NorFilter(listOf(EqualFilter(discount, BigDecimal("0.10"))))
        val byId = listOf(Sort(QueryField(ID), Sort.Direction.ASC))
        orders.list(ListQuery(AndFilter(listOf(EqualFilter(TENANT_COLUMN, "t1"), noTenth)), sort = byId))
            .map { it.path(ID).stringValue() }.assert().containsExactly("o1", "o3", "o4", "o5")
        Unit
    }

    @Test
    fun `an expression past the double range has no value, so no comparison matches it`() = blocking {
        fun times(factor: Double) = AggregationExpression.Binary(
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(AMOUNT),
            AggregationExpression.Constant(factor),
        )
        fun positive(factor: Double) = AndFilter(
            listOf(EqualFilter(TENANT_COLUMN, "t1"), ExpressionFilter(times(factor), ComparisonOperator.GT, 0.0)),
        )
        orders.count(positive(1e300)).assert().isEqualTo(5L)
        orders.count(positive(Double.MAX_VALUE)).assert().isEqualTo(0L)
        Unit
    }
}
