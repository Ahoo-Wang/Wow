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

package me.ahoo.wow.query.event.filter

import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class DefaultEventStreamQueryHandlerTest {
    private val tailSnapshotQueryFilter = TailEventStreamQueryFilter(NoOpEventStreamQueryServiceFactory)
    private val queryFilterChain = FilterChainBuilder<QueryContext<*, *, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter))
        .filterCondition(EventStreamQueryHandler::class)
        .build()
    private val queryHandler = DefaultEventStreamQueryHandler(
        queryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun query() {
        val query = listQuery { }
        queryHandler.list(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun dynamicList() {
        val query = listQuery { }
        queryHandler.dynamicList(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun count() {
        val condition = condition {
            id("1")
        }
        queryHandler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .consumeNextWith {
                assertThat(it, equalTo(0))
            }
            .verifyComplete()
    }
}
