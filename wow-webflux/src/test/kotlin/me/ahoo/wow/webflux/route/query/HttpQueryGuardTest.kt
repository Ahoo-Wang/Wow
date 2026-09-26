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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.webflux.route.query

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.webflux.exception.WebFluxRequestExceptionHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class HttpQueryGuardTest {
    private val request = MockServerRequest.builder().build()
    private val sseRequest = MockServerRequest.builder()
        .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
        .build()

    @Test
    fun rejectsInvalidConfiguration() {
        assertThrows<IllegalArgumentException> { HttpQueryGuard(defaultListSize = -1) }
        assertThrows<IllegalArgumentException> { HttpQueryGuard(idleTimeout = Duration.ofMillis(-1)) }
    }

    @Test
    fun timesOutTheWholeMonoAndIdleFluxPublisher() {
        StepVerifier.withVirtualTime {
            guard(idleTimeout = Duration.ofSeconds(1)).mono {
                Mono.delay(Duration.ofSeconds(2)).thenReturn(1)
            }
        }.thenAwait(Duration.ofSeconds(1))
            .expectError(TimeoutException::class.java)
            .verify()

        StepVerifier.withVirtualTime {
            guard(idleTimeout = Duration.ofSeconds(1)).flux(sseRequest) {
                Flux.concat(Mono.just(1), Mono.delay(Duration.ofSeconds(2)).thenReturn(2))
            }
        }.expectNext(1)
            .thenAwait(Duration.ofSeconds(1))
            .expectError(TimeoutException::class.java)
            .verify()
    }

    @Test
    fun continuousSseMayRunLongerThanTheIdleTimeout() {
        StepVerifier.withVirtualTime {
            guard(idleTimeout = Duration.ofSeconds(1)).flux(sseRequest) {
                Flux.interval(Duration.ofMillis(500)).take(5)
            }
        }.thenAwait(Duration.ofSeconds(3))
            .expectNext(0, 1, 2, 3, 4)
            .verifyComplete()
    }

    @Test
    fun propagatesCancellationToTheGatewayPublisher() {
        val cancelled = AtomicBoolean()
        StepVerifier.withVirtualTime {
            guard().flux(sseRequest) {
                Flux.never<Int>().doOnCancel { cancelled.set(true) }
            }
        }.thenCancel().verify()

        cancelled.get().assert().isTrue()
    }

    @Test
    fun buffersJsonButStreamsSseBeforeALateFailure() {
        val failure = IllegalStateException("late failure")

        guard(idleTimeout = Duration.ZERO).flux(request) {
            Flux.concat(Flux.just(1), Flux.error(failure))
        }.test()
            .expectErrorMatches { it === failure }
            .verify()

        guard(idleTimeout = Duration.ZERO).flux(sseRequest) {
            Flux.concat(Flux.just(1), Flux.error(failure))
        }.test()
            .expectNext(1)
            .expectErrorMatches { it === failure }
            .verify()
    }

    @Test
    fun streamsJsonWhenTheListCapIsOffSoNothingBuffersWithoutBound() {
        val failure = IllegalStateException("late failure")
        guard(maxListSize = 0, idleTimeout = Duration.ZERO).flux(request) {
            Flux.concat(Flux.just(1), Flux.error(failure))
        }.test()
            .expectNext(1)
            .expectErrorMatches { it === failure }
            .verify()
    }

    @Test
    fun enforcesActualOutputSizesAndCancelsExcessFlux() {
        val cancelled = AtomicBoolean()
        val bounded = guard(maxListSize = 2, idleTimeout = Duration.ZERO)
        bounded.flux(request) {
            Flux.range(1, 3).doOnCancel { cancelled.set(true) }
        }.test()
            .expectError(QueryExecutionException::class.java)
            .verify()
        cancelled.get().assert().isTrue()

        val boundedPage = guard(maxPageSize = 2, idleTimeout = Duration.ZERO)
        boundedPage.mono {
            Mono.just(PagedList(3, listOf(1, 2, 3)))
        }.test().expectError(QueryExecutionException::class.java).verify()

        boundedPage.mono {
            Mono.just(CursorPage(listOf(1, 2, 3), null))
        }.test().expectError(QueryExecutionException::class.java).verify()
    }

    @Test
    fun zeroCapsExplicitlyDisableOutputLimits() {
        val guard = guard(maxListSize = 0, maxPageSize = 0, idleTimeout = Duration.ZERO)
        guard.flux(request) {
            Flux.range(1, 1001)
        }.test().expectNextCount(1001).verifyComplete()

        guard.mono {
            Mono.just(PagedList(1000, List(1000) { it }))
        }.test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun appliesServerDefaultWhenListQueryLimitIsZero() {
        val gateway = mockk<QueryGateway<Any>> {
            every { dynamicList(any()) } returns Flux.empty()
            every { entryPolicy } returns me.ahoo.wow.query.QueryEntryPolicy.DEFAULT
        }
        val handler = ListQueryHandlerFunction(
            aggregateMetadata = MOCK_AGGREGATE_METADATA,
            queryGateway = gateway,
            queryRequestScope = QueryRequestScope { _, _ -> QueryScope.NONE },
            exceptionHandler = WebFluxRequestExceptionHandler(),
            guard = HttpQueryGuard(),
            rewriteResult = { it },
        )
        val response = handler.handle(
            MockServerRequest.builder().body(ListQuery(MatchAllFilter, limit = 0).toMono()),
        ).block()!!
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build())

        response.writeTo(exchange, SERVER_RESPONSE_CONTEXT).block()

        exchange.response.statusCode.assert().isEqualTo(HttpStatus.OK)
        verify(exactly = 1) { gateway.dynamicList(match { it.limit == 100 }) }
    }

    @Test
    fun applyListDefaultAppliesConfiguredDefaultWhenLimitIsZero() {
        guard().applyListDefault(ListQuery(MatchAllFilter, limit = 0)).limit.assert().isEqualTo(100)
    }

    @Test
    fun applyListDefaultClampsDefaultToMaxListSize() {
        guard(maxListSize = 50).applyListDefault(ListQuery(MatchAllFilter, limit = 0)).limit.assert().isEqualTo(50)
    }

    @Test
    fun applyListDefaultKeepsZeroWhenListCapsAreDisabled() {
        guard(maxListSize = 0).applyListDefault(ListQuery(MatchAllFilter, limit = 0)).limit.assert().isEqualTo(0)
    }

    @Test
    fun applyListDefaultKeepsZeroWhenDefaultIsDisabled() {
        guard(defaultListSize = 0).applyListDefault(ListQuery(MatchAllFilter, limit = 0)).limit.assert().isEqualTo(0)
    }

    @Test
    fun applyListDefaultKeepsPositiveLimit() {
        guard().applyListDefault(ListQuery(MatchAllFilter, limit = 7)).limit.assert().isEqualTo(7)
    }

    @Test
    fun applyListDefaultKeepsNegativeLimitForValidation() {
        guard().applyListDefault(ListQuery(MatchAllFilter, limit = -1)).limit.assert().isEqualTo(-1)
    }

    private fun guard(
        maxListSize: Int = 1000,
        defaultListSize: Int = 100,
        maxPageSize: Int = 100,
        idleTimeout: Duration = Duration.ofSeconds(10),
    ) = HttpQueryGuard(
        defaultListSize = defaultListSize,
        idleTimeout = idleTimeout,
    ).of(QueryBudget(QueryBudget.HTTP_LABEL, maxListSize = maxListSize, maxPageSize = maxPageSize))

    private companion object {
        private val SERVER_RESPONSE_CONTEXT = object : ServerResponse.Context {
            private val strategies = HandlerStrategies.withDefaults()
            override fun messageWriters() = strategies.messageWriters()
            override fun viewResolvers() = strategies.viewResolvers()
        }
    }
}
