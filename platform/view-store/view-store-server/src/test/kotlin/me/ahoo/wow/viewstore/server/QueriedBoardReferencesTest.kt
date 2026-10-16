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

package me.ahoo.wow.viewstore.server

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * The shared-dashboard check of a claim reads every dashboard, a keyset page at a time, and only their panels
 * (review A3-18): a reference on the last page of many is found.
 */
@OptIn(InternalWowApi::class)
class QueriedBoardReferencesTest {
    private val asked = mutableListOf<ICursorQuery>()

    /** [total] dashboards, the last one referencing `v-1`, served [QueriedBoardReferences.PAGE] at a time. */
    private fun dashboards(total: Int) = object : QueryGateway {
        override val aggregateType = AggregateType(ViewStoreContext.NAME, ViewStoreContext.VIEW)
        override val entryPolicy = QueryEntryPolicy.DEFAULT

        override suspend fun cursor(query: ICursorQuery, caller: QueryCaller): CursorPage<ObjectNode> {
            asked += query
            val from = query.cursor?.toInt() ?: 0
            val until = minOf(total, from + query.size)
            val list = (from until until).map { board(it, referencing = if (it == total - 1) "v-1" else "v-other") }
            return CursorPage(list, until.takeIf { it < total }?.toString())
        }

        override suspend fun single(query: ISingleQuery, caller: QueryCaller): ObjectNode? = error("not read")
        override fun list(query: IListQuery, caller: QueryCaller): Flow<ObjectNode> = error("not read")
        override suspend fun paged(query: IPagedQuery, caller: QueryCaller): PagedList<ObjectNode> = error("not read")
        override suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult = error("not read")
        override fun aggregate(query: AggregationQuery, caller: QueryCaller): Flow<ObjectNode> = error("not read")
        override suspend fun describe(entry: QueryEntry, defaultListSize: Int?): QueryModelDescriptor =
            error("not read")
    }

    private fun board(index: Int, referencing: String): ObjectNode {
        val record = WowJson.mapper.createObjectNode().put("aggregateId", "b-$index")
        val state = record.putObject("state").put("title", "Board $index")
        state.putObject("config").putArray("panels").addObject().put("instanceId", referencing)
        return record
    }

    @Test
    fun `a reference past the first page should be found, every page read for its panels alone`(): Unit =
        runBlocking {
            val total = QueriedBoardReferences.PAGE * 2 + 5
            val references = QueriedBoardReferences { HttpQueries(listOf(dashboards(total))) }
            val found = references.referencingBoards("t", "app", "v-1")
            found.map { it.id }.assert().containsExactly("b-${total - 1}")
            found.single().title.assert().isEqualTo("Board ${total - 1}")
            asked.size.assert().isEqualTo(3)
            asked.map { it.size }.toSet().assert().containsExactly(QueriedBoardReferences.PAGE)
            asked.first().projection.include.map { it.path }.assert()
                .containsExactly("aggregateId", "state.title", "state.config")
            references.referencingBoards("t", "app", "v-none").assert().isEmpty()
        }

    @Test
    fun `a node without snapshot queries should know of no reference`(): Unit = runBlocking {
        QueriedBoardReferences { HttpQueries.NONE }.referencingBoards("t", "app", "v-1").assert().isEmpty()
    }
}
