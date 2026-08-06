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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.test.publisher.PublisherProbe

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

    @Test
    fun `should handle asynchronous query service error`() {
        val failure = IllegalStateException("query failed")
        val condition = condition { id("1") }
        val queryService = mockk<SnapshotQueryService<Any>> {
            every { count(condition) } returns Mono.error(failure)
        }
        val queryServiceFactory = mockk<SnapshotQueryServiceFactory> {
            every { create<Any>(any()) } returns queryService
        }
        val errorHandler = mockk<ErrorHandler<QueryContext<*, *>>> {
            every { handle(any(), failure) } returns Mono.error(failure)
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(TailSnapshotQueryFilter<Any>(queryServiceFactory)))
            .filterCondition(SnapshotQueryHandler::class)
            .build()
        val handler = DefaultSnapshotQueryHandler(chain, errorHandler)

        handler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()

        verify(exactly = 1) { errorHandler.handle(any(), failure) }
    }

    @Test
    fun `should not recover asynchronous query service error`() {
        val failure = IllegalStateException("query failed")
        val condition = condition { id("1") }
        val queryService = mockk<SnapshotQueryService<Any>> {
            every { count(condition) } returns Mono.error(failure)
        }
        val queryServiceFactory = mockk<SnapshotQueryServiceFactory> {
            every { create<Any>(any()) } returns queryService
        }
        val resumeErrorHandler = mockk<ErrorHandler<QueryContext<*, *>>> {
            every { handle(any(), failure) } returns Mono.empty()
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(TailSnapshotQueryFilter<Any>(queryServiceFactory)))
            .filterCondition(SnapshotQueryHandler::class)
            .build()
        val handler = DefaultSnapshotQueryHandler(chain, resumeErrorHandler)

        handler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()

        verify(exactly = 1) { resumeErrorHandler.handle(any(), failure) }
    }

    @Test
    fun `should not execute result after filter chain error`() {
        val failure = IllegalStateException("masking failed")
        val condition = condition { id("1") }
        val backendPublisher = PublisherProbe.of(Mono.just(1L))
        val queryService = mockk<SnapshotQueryService<Any>> {
            every { count(condition) } returns backendPublisher.mono()
        }
        val queryServiceFactory = mockk<SnapshotQueryServiceFactory> {
            every { create<Any>(any()) } returns queryService
        }
        val failingPostFilter = object : SnapshotQueryFilter {
            override fun filter(
                context: QueryContext<*, *>,
                next: FilterChain<QueryContext<*, *>>,
            ): Mono<Void> {
                return next.filter(context).then(Mono.error(failure))
            }
        }
        val resumeErrorHandler = mockk<ErrorHandler<QueryContext<*, *>>> {
            every { handle(any(), failure) } returns Mono.empty()
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(failingPostFilter, TailSnapshotQueryFilter<Any>(queryServiceFactory)))
            .filterCondition(SnapshotQueryHandler::class)
            .build()
        val handler = DefaultSnapshotQueryHandler(chain, resumeErrorHandler)

        handler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .expectErrorSatisfies {
                it.assert().isSameAs(failure)
            }
            .verify()

        backendPublisher.assertWasNotSubscribed()
        verify(exactly = 1) { resumeErrorHandler.handle(any(), failure) }
    }

    @Test
    fun `should isolate mutable query context for each subscription`() {
        val original = Condition.eq("aggregateId", "1")
        val mandatory = Condition.eq("tenantId", "tenant")
        val capturedConditions = mutableListOf<Condition>()
        val queryService = mockk<SnapshotQueryService<Any>> {
            every { count(capture(capturedConditions)) } returns Mono.just(0)
        }
        val queryServiceFactory = mockk<SnapshotQueryServiceFactory> {
            every { create<Any>(any()) } returns queryService
        }
        val mandatoryFilter = object : SnapshotQueryFilter {
            override fun filter(
                context: QueryContext<*, *>,
                next: FilterChain<QueryContext<*, *>>,
            ): Mono<Void> {
                context.asRewritableQuery().rewriteQuery {
                    it.appendCondition(mandatory)
                }
                return next.filter(context)
            }
        }
        val chain = FilterChainBuilder<QueryContext<*, *>>()
            .addFilters(listOf(mandatoryFilter, TailSnapshotQueryFilter<Any>(queryServiceFactory)))
            .filterCondition(SnapshotQueryHandler::class)
            .build()
        val handler = DefaultSnapshotQueryHandler(chain)
        val result = handler.count(MOCK_AGGREGATE_METADATA, original)

        result.test().expectNext(0).verifyComplete()
        result.test().expectNext(0).verifyComplete()

        capturedConditions.assert().hasSize(2)
        capturedConditions[0].assert().isEqualTo(Condition.and(original, mandatory))
        capturedConditions[1].assert().isEqualTo(capturedConditions[0])
    }
}
