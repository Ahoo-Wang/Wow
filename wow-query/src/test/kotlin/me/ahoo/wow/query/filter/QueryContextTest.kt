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

package me.ahoo.wow.query.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class QueryContextTest {

    @Test
    fun `should set and get query`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = singleQuery { }
        context.setQuery(query)
        context.getQuery().assert().isEqualTo(query)
    }

    @Test
    fun `should throw when get query without set`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            context.getQuery()
        }
    }

    @Test
    fun `should rewrite query`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = singleQuery {
            condition { "field1" eq "value1" }
        }
        context.setQuery(query)
        context.rewriteQuery {
            singleQuery {
                condition { "field2" eq "value2" }
            }
        }
        context.getQuery().condition.assert().isEqualTo(
            Condition.eq("field2", "value2")
        )
    }

    @Test
    fun `should set and get result`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val result: Mono<Any> = Mono.just("result")
        context.setResult(result)
        context.getRequiredResult().assert().isSameAs(result)
    }

    @Test
    fun `should set result from query handler`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<String>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val query = singleQuery { }
        context.setQuery(query)
        context.setResult { queryArg ->
            queryArg.condition.assert().isEqualTo(query.condition)
            Mono.just("handled")
        }
        context.getRequiredResult().block().assert().isEqualTo("handled")
    }

    @Test
    fun `should rewrite result`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<String>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setResult(Mono.just("original"))
        context.rewriteResult { it.map { "$it-modified" } }
        context.getRequiredResult().block().assert().isEqualTo("original-modified")
    }

    @Test
    fun `should set and get generic attributes`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setAttribute("key1", "value1")
        val value: String? = context.getAttribute("key1")
        value.assert().isEqualTo("value1")
    }

    @Test
    fun `should return null for missing attribute`() {
        val context = DefaultQueryContext<ISingleQuery, Mono<Any>>(
            queryType = QueryType.SINGLE,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        val value: String? = context.getAttribute("missing")
        value.assert().isNull()
    }

    @Test
    fun `should cast to count query context`() {
        val context = DefaultQueryContext<Condition, Mono<Long>>(
            queryType = QueryType.COUNT,
            namedAggregate = MOCK_AGGREGATE_METADATA
        )
        context.setQuery(Condition.ALL)
        val countContext = context.asCountQuery()
        countContext.getQuery().assert().isEqualTo(Condition.ALL)
    }
}
