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

package me.ahoo.wow.apiclient.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import org.junit.jupiter.api.Test
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class SnapshotAggregationQueryApiTest {
    @Test
    fun `aggregation API should use the snapshot aggregation resource`() {
        val method = SnapshotAggregationQueryApi::class.java.getMethod("aggregate", AggregationQuery::class.java)

        method.getAnnotation(PostExchange::class.java).value.assert().isEqualTo("snapshot/aggregation")
    }

    @Test
    fun `aggregation query extensions should delegate to their API`() {
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
        val reactiveResult = Flux.just(mapOf<String, Any?>("count" to 1L))
        lateinit var reactiveQuery: AggregationQuery
        val reactiveApi = object : ReactiveSnapshotAggregationQueryApi {
            override fun aggregate(query: AggregationQuery): Flux<Map<String, Any?>> {
                reactiveQuery = query
                return reactiveResult
            }
        }
        val synchronousResult = listOf(mapOf<String, Any?>("count" to 1L))
        lateinit var synchronousQuery: AggregationQuery
        val synchronousApi = object : SynchronousSnapshotAggregationQueryApi {
            override fun aggregate(query: AggregationQuery): List<Map<String, Any?>> {
                synchronousQuery = query
                return synchronousResult
            }
        }

        query.query(reactiveApi).test().expectNext(mapOf<String, Any?>("count" to 1L)).verifyComplete()
        reactiveQuery.assert().isEqualTo(query)
        query.query(synchronousApi).assert().isEqualTo(synchronousResult)
        synchronousQuery.assert().isEqualTo(query)
    }
}
