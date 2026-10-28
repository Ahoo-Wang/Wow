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

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.query.read.QueryNotServedException
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.ViewStoreContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/**
 * The shared-dashboard check of a claim reads every dashboard, a keyset page at a time, and only their panels
 * (review A3-18): a reference on the last page of many is found.
 */
class QueriedBoardReferencesTest {
    private val asked = mutableListOf<CursorQuery>()

    private val view = ViewStoreContract.View.TYPE

    /** [total] dashboards, the last one referencing `v-1`, served [QueriedBoardReferences.PAGE] at a time. */
    private fun dashboards(total: Int): QueryApi {
        val rows = mockk<RowQueries<ObjectNode>> {
            coEvery { cursor(any()) } answers {
                val query = firstArg<CursorQuery>()
                asked += query
                val from = query.cursor?.toInt() ?: 0
                val until = minOf(total, from + query.size)
                val list = (from until until).map { board(it, referencing = if (it == total - 1) "v-1" else "v-other") }
                CursorPage.of(list, until.takeIf { it < total }?.toString())
            }
        }
        return mockk { every { trusted().snapshots(view).dynamic(ObjectNode::class.java) } returns rows }
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
            val api = dashboards(total)
            val references = QueriedBoardReferences { api }
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
        val unserved = mockk<QueryApi> {
            every { trusted().snapshots(view) } throws QueryNotServedException(view, QueryModel.SNAPSHOT)
        }
        QueriedBoardReferences { unserved }.referencingBoards("t", "app", "v-1").assert().isEmpty()
    }

    @Test
    fun `a query the node got wrong should fail, not pass for a node without queries`(): Unit = runBlocking {
        val wrong = mockk<QueryApi> { every { trusted().snapshots(view) } throws IllegalArgumentException("wrong") }
        assertThrows<IllegalArgumentException> { QueriedBoardReferences { wrong }.referencingBoards("t", "app", "v-1") }
    }
}
