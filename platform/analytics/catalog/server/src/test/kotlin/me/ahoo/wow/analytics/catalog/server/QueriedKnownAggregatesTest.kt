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

package me.ahoo.wow.analytics.catalog.server

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** The catalog knows an aggregate some Wow source is of, read from the sources' snapshots as trusted code. */
class QueriedKnownAggregatesTest {
    private val source = AggregateType(AnalyticsCatalogContext.NAME, AnalyticsCatalogContext.SOURCE)

    @Test
    fun `an aggregate should be known when a source of it is found`(): Unit = runBlocking {
        val asked = mutableListOf<SingleQuery>()
        val rows = mockk<RowQueries<ObjectNode>> {
            coEvery { single(any()) } answers {
                asked += firstArg<SingleQuery>()
                if (asked.size == 1) mockk() else null
            }
        }
        val api = mockk<QueryApi> { every { trusted().snapshots(source).dynamic(ObjectNode::class.java) } returns rows }
        val known = QueriedKnownAggregates { api }
        known.knows("shop", "order").assert().isTrue()
        known.knows("shop", "cart").assert().isFalse()
        asked.first().projection.include.map { it.path }.assert().containsExactly("aggregateId")
    }

    @Test
    fun `a node that serves no snapshot query should know of no aggregate`(): Unit = runBlocking {
        val api = mockk<QueryApi> { every { trusted().snapshots(source) } throws IllegalArgumentException("none") }
        QueriedKnownAggregates { api }.knows("shop", "order").assert().isFalse()
    }
}
