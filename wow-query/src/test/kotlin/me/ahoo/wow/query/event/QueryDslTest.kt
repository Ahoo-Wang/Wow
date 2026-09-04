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

package me.ahoo.wow.query.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

@Suppress("DEPRECATION")
class QueryDslTest {
    private val schemaProvider = object : QueryModelSchemaProvider {
        private val schema = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
        override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
        override fun refresh(): Mono<QueryModelSchema> = schema()
    }
    private val gateway = DefaultEventStreamQueryGateway(
        MOCK_AGGREGATE_METADATA,
        QueryBackendBinding(NoOpEventStreamQueryBackend(MOCK_AGGREGATE_METADATA), schemaProvider),
        QuerySchemaValidationMode.COMPATIBLE,
    )

    @Test
    fun `query DSL should execute every aggregate-bound gateway operation`() {
        SingleQuery(MatchAllFilter).query(gateway).test().verifyComplete()
        SingleQuery(MatchAllFilter).dynamicQuery(gateway).test().verifyComplete()
        ListQuery(MatchAllFilter).query(gateway).test().verifyComplete()
        ListQuery(MatchAllFilter).dynamicQuery(gateway).test().verifyComplete()
        PagedQuery(MatchAllFilter).query(gateway).test()
            .assertNext {
                it.total.assert().isZero()
                it.list.assert().isEmpty()
            }
            .verifyComplete()
        PagedQuery(MatchAllFilter).dynamicQuery(gateway).test()
            .assertNext {
                it.total.assert().isZero()
                it.list.assert().isEmpty()
            }
            .verifyComplete()
        MatchAllFilter.count(gateway).test().expectNext(0L).verifyComplete()
        Condition().count(gateway).test().expectNext(0L).verifyComplete()
        AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
            .query(gateway).test().verifyComplete()
    }
}
