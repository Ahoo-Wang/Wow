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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class QueryServiceCompatibilityTest {
    @Suppress("DEPRECATION")
    @Test
    fun `legacy count should delegate once to filter implementation`() {
        lateinit var captured: FilterExpression
        val service = object : LegacyQueryService() {
            override fun count(filter: FilterExpression): Mono<Long> {
                captured = filter
                return Mono.just(1)
            }
        }

        service.count(Condition.id("id-1")).test().expectNext(1).verifyComplete()
        captured.assert().isEqualTo(IdFilter("id-1"))
    }

    @Test
    fun `query service should inherit unsupported aggregation`() {
        LegacyQueryService().aggregate(
            AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))),
        ).test()
            .expectErrorMessage("Aggregation is not supported.")
            .verify()
    }

    @Test
    fun `legacy query service should inherit unsupported cursor`() {
        LegacyQueryService().cursor(CursorQuery(me.ahoo.wow.api.query.MatchAllFilter)).test()
            .expectErrorMessage("Cursor query is not supported.")
            .verify()
    }

    private open class LegacyQueryService : QueryService<Any> {
        override val namedAggregate = "test.test".toNamedAggregate()
        override fun single(singleQuery: ISingleQuery): Mono<Any> = Mono.empty()
        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.empty()
        override fun list(listQuery: IListQuery): Flux<Any> = Flux.empty()
        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.empty()
        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<Any>> = Mono.empty()
        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> = Mono.empty()
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
    }
}
