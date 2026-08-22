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
import reactor.core.publisher.Flux
import reactor.kotlin.test.test

class SnapshotAggregationQueryApiTest {
    private val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
    private val row = mapOf<String, Any?>("count" to 1L)

    @Test
    fun `reactive and synchronous clients should expose dynamic rows`() {
        val reactive = object : ReactiveSnapshotAggregationQueryApi {
            override fun aggregate(query: AggregationQuery): Flux<Map<String, Any?>> = Flux.just(row)
        }
        query.aggregate(reactive).test().expectNext(row).verifyComplete()

        val synchronous = object : SynchronousSnapshotAggregationQueryApi {
            override fun aggregate(query: AggregationQuery): List<Map<String, Any?>> = listOf(row)
        }
        query.aggregate(synchronous).assert().containsExactly(row)
    }
}
