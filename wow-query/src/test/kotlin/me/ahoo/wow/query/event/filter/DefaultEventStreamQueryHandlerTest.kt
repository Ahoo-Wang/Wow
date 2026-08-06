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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.test.publisher.PublisherProbe

class DefaultEventStreamQueryHandlerTest {
    private val tailSnapshotQueryFilter = TailEventStreamQueryFilter(NoOpEventStreamQueryServiceFactory)
    private val queryFilterChain = FilterChainBuilder<QueryContext<*, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter))
        .filterCondition(EventStreamQueryHandler::class)
        .build()
    private val queryHandler = DefaultEventStreamQueryHandler(
        queryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun `should execute single event stream query`() {
        val query = singleQuery {
        }

        queryHandler.single(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic single event stream query`() {
        val query = singleQuery {
        }

        queryHandler.dynamicSingle(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute list event stream query`() {
        val query = listQuery { }
        queryHandler.list(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute dynamic list event stream query`() {
        val query = listQuery { }
        queryHandler.dynamicList(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun `should execute paged event stream query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.paged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute dynamic paged event stream query`() {
        val pagedQuery = me.ahoo.wow.query.dsl.pagedQuery { }
        queryHandler.dynamicPaged(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                it.total.assert().isZero()
            }
            .verifyComplete()
    }

    @Test
    fun `should execute count event stream query`() {
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

    @Test
    fun `should handle asynchronous event stream query error`() {
        val failure = IllegalStateException("query failed")
        val query = listQuery { }
        val queryService = mockk<EventStreamQueryService> {
            every { list(query) } returns Flux.error(failure)
        }
        val queryServiceFactory = mockk<EventStreamQueryServiceFactory> {
            every { create(any()) } returns queryService
        }
        val errorHandler = mockk<ErrorHandler<QueryContext<*, *>>> {
            every { handle(any(), failure) } returns Mono.error(failure)
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(TailEventStreamQueryFilter(queryServiceFactory)))
            .filterCondition(EventStreamQueryHandler::class)
            .build()
        val handler = DefaultEventStreamQueryHandler(chain, errorHandler)

        handler.list(MOCK_AGGREGATE_METADATA, query)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()

        verify(exactly = 1) { errorHandler.handle(any(), failure) }
    }

    @Test
    fun `should observe synchronous event stream query error once`() {
        val failure = IllegalStateException("query failed")
        val query = listQuery { }
        val queryService = mockk<EventStreamQueryService> {
            every { list(query) } throws failure
        }
        val queryServiceFactory = mockk<EventStreamQueryServiceFactory> {
            every { create(any()) } returns queryService
        }
        val resumeErrorHandler = mockk<ErrorHandler<QueryContext<*, *>>> {
            every { handle(any(), failure) } returns Mono.empty()
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(TailEventStreamQueryFilter(queryServiceFactory)))
            .filterCondition(EventStreamQueryHandler::class)
            .build()
        val handler = DefaultEventStreamQueryHandler(chain, resumeErrorHandler)

        handler.list(MOCK_AGGREGATE_METADATA, query)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()

        verify(exactly = 1) { resumeErrorHandler.handle(any(), failure) }
    }

    @Test
    fun `should not recover partial event stream query error`() {
        val failure = IllegalStateException("query failed")
        val query = listQuery { }
        val event = mockk<DomainEventStream>()
        val queryService = mockk<EventStreamQueryService> {
            every { list(query) } returns Flux.concat(Flux.just(event), Flux.error(failure))
        }
        val queryServiceFactory = mockk<EventStreamQueryServiceFactory> {
            every { create(any()) } returns queryService
        }
        val resumeErrorHandler = mockk<ErrorHandler<QueryContext<*, *>>> {
            every { handle(any(), failure) } returns Mono.empty()
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(TailEventStreamQueryFilter(queryServiceFactory)))
            .filterCondition(EventStreamQueryHandler::class)
            .build()
        val handler = DefaultEventStreamQueryHandler(chain, resumeErrorHandler)

        handler.list(MOCK_AGGREGATE_METADATA, query)
            .test()
            .expectNext(event)
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()

        verify(exactly = 1) { resumeErrorHandler.handle(any(), failure) }
    }

    @Test
    fun `should propagate cancellation without handling it as an error`() {
        val query = listQuery { }
        val backendPublisher = PublisherProbe.of(Flux.never<DomainEventStream>())
        val queryService = mockk<EventStreamQueryService> {
            every { list(query) } returns backendPublisher.flux()
        }
        val queryServiceFactory = mockk<EventStreamQueryServiceFactory> {
            every { create(any()) } returns queryService
        }
        val errorHandler = mockk<ErrorHandler<QueryContext<*, *>>>(relaxed = true)
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(TailEventStreamQueryFilter(queryServiceFactory)))
            .filterCondition(EventStreamQueryHandler::class)
            .build()
        val handler = DefaultEventStreamQueryHandler(chain, errorHandler)

        handler.list(MOCK_AGGREGATE_METADATA, query)
            .test()
            .then { backendPublisher.assertWasSubscribed() }
            .thenCancel()
            .verify()

        backendPublisher.assertWasCancelled()
        verify(exactly = 0) { errorHandler.handle(any(), any()) }
    }
}
