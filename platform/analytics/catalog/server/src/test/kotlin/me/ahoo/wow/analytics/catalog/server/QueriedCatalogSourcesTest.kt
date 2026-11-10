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

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContract
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.query.read.QueryNotServedException
import me.ahoo.wow.query.schema.QueryModel
import org.junit.jupiter.api.Test

/** The sources the catalog holds, read from their snapshots as a job reading the whole model. */
class QueriedCatalogSourcesTest {
    @Test
    fun `the tables of the sources held in a database, not missing, should be read`(): Unit = runBlocking {
        val asked = mutableListOf<ListQuery>()
        fun held(table: String): Snapshot<SourceState> = mockk {
            every { state } returns mockk { every { this@mockk.table } returns table }
        }
        val rows = mockk<SnapshotQueries<SourceState>> {
            every { list(any()) } answers {
                asked += firstArg<ListQuery>()
                flowOf(held("orders"), held("customers"))
            }
        }
        val api = mockk<NodeQueryApi> {
            every { trusted().unbounded().snapshots(SourceState::class.java) } returns rows
        }
        QueriedCatalogSources { api }.present("ch", "db").assert().containsExactlyInAnyOrder("orders", "customers")
        asked.single().filter.toString().assert()
            .contains("state.connection").contains("ch")
            .contains("state.database").contains("db")
            .contains("state.missing")
    }

    @Test
    fun `a node that serves no snapshot query should hold no source it can read`(): Unit = runBlocking {
        val source = AnalyticsCatalogContract.Source.TYPE
        val api = mockk<NodeQueryApi> {
            every { trusted().unbounded().snapshots(SourceState::class.java) } throws
                QueryNotServedException(source, QueryModel.SNAPSHOT)
        }
        QueriedCatalogSources { api }.present("ch", "db").assert().isEmpty()
    }
}
