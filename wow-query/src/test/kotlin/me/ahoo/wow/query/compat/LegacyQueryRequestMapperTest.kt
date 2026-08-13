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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPageSpec
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LegacyQueryRequestMapperTest {
    private val mapper = LegacyQueryRequestMapper.create(
        QueryTarget("sales.order".toNamedAggregate(), QueryDocumentKind.SNAPSHOT)
    )

    @Test
    fun `maps all include and exclude to projected dynamic typed shape`() {
        val fixtures = listOf(
            Projection.ALL to QueryProjection.All,
            Projection(include = listOf("state.name", "aggregateId")) to QueryProjection.Include(
                linkedSetOf(LogicalField("state.name"), LogicalField("aggregateId"))
            ),
            Projection(exclude = listOf("state.secret")) to QueryProjection.Exclude(
                setOf(LogicalField("state.secret"))
            )
        )

        fixtures.forEach { (legacy, expectedProjection) ->
            mapper.dynamicShape(legacy).assert().isEqualTo(
                QueryResultShape.Typed(DynamicDocument::class.java, expectedProjection)
            )
        }
    }

    @Test
    fun `rejects mixed include and exclude with stable normalization error`() {
        val error = assertThrows<QueryException> {
            mapper.dynamicShape(Projection(include = listOf("state.name"), exclude = listOf("state.secret")))
        }

        error.code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        error.stage.assert().isEqualTo(QueryStage.NORMALIZE)
        error.reason.assert().isEqualTo(QueryErrorReason.INVALID_REQUEST)
    }

    @Test
    fun `maps every legacy request dimension without evaluating relative time`() {
        val condition = Condition.today("eventTime")
        val projection = Projection(include = listOf("eventTime"))
        val sort = listOf(Sort("eventTime", Sort.Direction.DESC))

        val single = mapper.single(SingleQuery(condition, projection, sort))
        val list = mapper.list(ListQuery(condition, projection, sort, limit = 7))
        val page = mapper.page(PagedQuery(condition, projection, sort, Pagination(index = 3, size = 7)))
        val count = mapper.count(condition)

        listOf(single.expression, list.expression, page.expression, count.expression).forEach { expression ->
            expression.assert().isInstanceOf(me.ahoo.wow.api.query.expression.RelativeTimeExpression::class.java)
        }
        single.resultShape.assert().isEqualTo(mapper.dynamicShape(projection))
        list.limit.assert().isEqualTo(7)
        page.page.assert().isEqualTo(QueryPageSpec(3, 7))
        listOf(single.sort, list.sort, page.sort).forEach { actual ->
            actual.assert().isEqualTo(listOf(QuerySort(LogicalField("eventTime"), QuerySortDirection.DESC)))
        }
        listOf(single.target, list.target, page.target, count.target).forEach { it.assert().isEqualTo(mapper.target) }
    }
}
