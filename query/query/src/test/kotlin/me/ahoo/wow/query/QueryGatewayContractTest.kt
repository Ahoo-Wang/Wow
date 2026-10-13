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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The gateway answers the records a backend returns as JSON trees, unchanged: 9.x materialized typed snapshots and
 * event streams here; V10 returns [ObjectNode] rows only, and has no event stream model yet.
 */
class QueryGatewayContractTest {
    private val gateway = DefaultQueryGateway(
        AGGREGATE,
        SnapshotBackend,
        FixedQueryModelSchemaProvider(gatewaySchema()),
    )

    @Test
    fun `the snapshot gateway answers the raw backend rows`() {
        runTest {
            gateway.single(SingleQuery(MatchAllFilter), QueryCaller.IN_PROCESS).assert().isEqualTo(snapshotNode())
            gateway.list(ListQuery(MatchAllFilter), QueryCaller.IN_PROCESS).toList()
                .assert().containsExactly(snapshotNode(), snapshotNode())
            gateway.paged(PagedQuery(MatchAllFilter), QueryCaller.IN_PROCESS).let { page ->
                page.total.assert().isEqualTo(2)
                page.exact.assert().isTrue()
                page.list.assert().containsExactly(snapshotNode(), snapshotNode())
            }
            gateway.cursor(CursorQuery(MatchAllFilter, size = 1), QueryCaller.IN_PROCESS).let { page ->
                page.nextCursor.assert().isNotNull()
                page.list.single().assert().isEqualTo(snapshotNode())
            }
            gateway.count(CountQuery(MatchAllFilter), QueryCaller.IN_PROCESS).assert().isEqualTo(CountResult(2, true))
        }
    }

    @Test
    fun `the next cursor continues after the last row of the page`() {
        runTest {
            val first = gateway.cursor(CursorQuery(MatchAllFilter, size = 1), QueryCaller.IN_PROCESS)
            gateway.cursor(CursorQuery(MatchAllFilter, size = 1, cursor = first.nextCursor), QueryCaller.IN_PROCESS)
                .list.single().assert().isEqualTo(snapshotNode())
            SnapshotBackend.after.assert().isEqualTo(CursorPosition(listOf("first")))
        }
    }

    @Test
    fun `the snapshot gateway answers the raw aggregation rows`() {
        runTest {
            val query = AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(QueryField("tenantId"), "tenant")),
                metrics = listOf(AggregationMetric.Count("count")),
                sort = listOf(Sort(QueryField("count"), Sort.Direction.DESC)),
            )
            gateway.aggregate(query, QueryCaller.IN_PROCESS).toList().map { it.toString() }
                .assert().containsExactly("""{"tenant":"tenant","count":2}""")
        }
    }

    private object SnapshotBackend : QueryBackend {
        override val aggregateType: AggregateType = AGGREGATE
        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        @Volatile
        var after: CursorPosition? = null

        override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = flow {
            emit(snapshotNode())
            emit(snapshotNode())
        }

        override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
            if (window is PageWindow.Keyset) after = window.after
            return twoRows()
        }

        override suspend fun count(query: AdmittedQuery<ICountQuery>): Long = 2

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
            emit(WowJson.mapper.createObjectNode().put("tenant", "tenant").put("count", 2))
        }
    }

    private companion object {
        val AGGREGATE = AggregateType("context", "aggregate")

        /** Two rows with positions: one page of one row plus the look-ahead, so a next cursor exists. */
        fun twoRows(): BackendPage = BackendPage(
            listOf(snapshotNode(), snapshotNode()),
            2,
            listOf(CursorPosition(listOf("first")), CursorPosition(listOf("second"))),
        )

        fun snapshotNode(): ObjectNode = WowJson.mapper.readTree(
            """
            {"tenantId":"tenant","aggregateId":"aggregate","version":1,"ownerId":null,"spaceId":null,
             "deleted":false,"tags":{},"firstEventTime":1,"lastEventTime":1,"state":{"value":"raw"}}
            """.trimIndent()
        ) as ObjectNode
    }
}
