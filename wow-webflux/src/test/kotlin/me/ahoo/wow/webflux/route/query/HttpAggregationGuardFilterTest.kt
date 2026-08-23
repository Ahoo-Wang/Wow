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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import me.ahoo.wow.query.snapshot.filter.SnapshotAggregationQueryContext
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.TimeoutException

class HttpAggregationGuardFilterTest {
    @Test
    fun `should reject invalid aggregation limits`() {
        listOf(
            { HttpQueryGuardFilter(maxAggregationElements = -1) },
            { HttpQueryGuardFilter(maxAggregationElements = AggregationQuery.MAX_ELEMENTS + 1) },
            { HttpQueryGuardFilter(maxAggregationMetrics = -1) },
            { HttpQueryGuardFilter(maxAggregationMetrics = AggregationQuery.MAX_METRICS + 1) },
        ).forEach { create -> assertThrows<IllegalArgumentException> { create() } }
    }

    @Test
    fun `zero aggregation limits should defer to public hard limits`() {
        val context = context(
            AggregationQuery(
                condition = Condition.eq(MessageRecords.AGGREGATE_ID, "aggregate-id"),
                elements = listOf(AggregationElement("state.orders")),
                groupBy = listOf(AggregationGroup.Terms("state.orders.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
                limit = AggregationQuery.MAX_LIMIT,
            ),
        )
        HttpAggregationQueryGuardFilter(
            HttpQueryGuardFilter(
                maxListSize = 0,
                maxAggregationElements = 0,
                maxAggregationMetrics = 0,
                allowExpensiveOperators = true,
            ),
        ).filter(
            context,
            FilterChain {
                it.setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(MockServerRequest.builder().build()).test().verifyComplete()
        context.getRequiredResult().test().verifyComplete()
    }

    @Test
    fun `sse aggregation should apply idle timeout without buffering`() {
        val context = context(
            AggregationQuery(
                condition = Condition.id("aggregate-id"),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        )
        val request = MockServerRequest.builder()
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .build()
        HttpAggregationQueryGuardFilter(
            HttpQueryGuardFilter(idleTimeout = Duration.ofMillis(10)),
        ).filter(
            context,
            FilterChain {
                it.setResult(Flux.never())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()

        context.getRequiredResult().test()
            .expectError(TimeoutException::class.java)
            .verify()
    }

    private fun context(
        query: AggregationQuery,
    ): SnapshotAggregationQueryContext = SnapshotAggregationQueryContext(MOCK_AGGREGATE_METADATA, query)
}
