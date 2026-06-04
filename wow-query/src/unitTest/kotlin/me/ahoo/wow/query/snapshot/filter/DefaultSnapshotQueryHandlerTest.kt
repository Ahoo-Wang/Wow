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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class DefaultSnapshotQueryHandlerTest {
    private val tailSnapshotQueryFilter = TailSnapshotQueryFilter<Any>(NoOpSnapshotQueryServiceFactory)
    private val snapshotQueryFilterChain = FilterChainBuilder<QueryContext<*, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter))
        .filterCondition(SnapshotQueryHandler::class)
        .build()
    private val queryHandler = DefaultSnapshotQueryHandler(
        snapshotQueryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun `should execute single query`() {
        val query = singleQuery {
        }

        queryHandler.single(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic single query`() {
        val query = singleQuery {
        }

        queryHandler.dynamicSingle(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute list query`() {
        val query = listQuery { }
        queryHandler.list(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic list query`() {
        val query = listQuery { }
        queryHandler.dynamicList(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute paged query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.paged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute dynamic paged query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.dynamicPaged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute count query`() {
        val condition = condition {
            id("1")
        }
        queryHandler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .consumeNextWith {
                it.assert().isZero()
            }
            .verifyComplete()
    }
}
