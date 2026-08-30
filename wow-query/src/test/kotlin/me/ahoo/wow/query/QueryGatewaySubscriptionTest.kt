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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewaySubscriptionTest {
    @Test
    fun `repeat retry and concurrent subscriptions should isolate context and object node`() {
        assertIsolated { publisher -> publisher.repeat(1) }
        val failures = AtomicInteger(1)
        assertIsolated(
            expectedOutputs = 1,
            filterResult = { node ->
                if (failures.getAndDecrement() > 0) Mono.error(IllegalStateException("retry")) else Mono.just(node)
            },
        ) { publisher -> publisher.retry(1) }
        assertIsolated { publisher ->
            Flux.merge(
                publisher.subscribeOn(Schedulers.parallel()),
                publisher.subscribeOn(Schedulers.parallel()),
            )
        }
    }

    @Test
    fun `error handler should observe but never swallow original failure`() {
        val original = IllegalStateException("backend")
        val handled = CopyOnWriteArrayList<Throwable>()
        val gateway = gateway(
            backend = backend(cursor = { Mono.error(original) }) { Mono.error(original) },
            errorHandler = ErrorHandler { _, error ->
                handled += error
                Mono.empty()
            },
        )

        listOf<Publisher<*>>(
            gateway.dynamicSingle(singleQuery { }),
            gateway.dynamicCursor(CursorQuery(MatchAllFilter)),
        ).forEach { publisher ->
            StepVerifier.create(publisher)
                .expectErrorMatches { it === original }
                .verify()
        }
        handled.assert().hasSize(2)
        handled.forEach { it.assert().isSameAs(original) }
    }

    @Test
    fun `cursor repeat should isolate context and page nodes`() {
        val contexts = CopyOnWriteArrayList<QueryContext<*, *>>()
        val nodes = CopyOnWriteArrayList<ObjectNode>()
        val filter = object : QueryFilter<QueryContext<*, *>> {
            override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
                contexts += context
                return next.filter(context).then(
                    Mono.fromRunnable {
                        context.asCursorQuery().rewriteResult { result ->
                            result.map { page ->
                                page.copy(
                                    list = page.list.map { node ->
                                        nodes += node
                                        node
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        val gateway = gateway(
            backend(
                cursor = { Mono.fromSupplier { CursorPage(listOf(snapshotNode()), "next") } },
            ) { Mono.empty() },
            filters = listOf(filter),
        )

        StepVerifier.create(gateway.dynamicCursor(CursorQuery(MatchAllFilter)).repeat(1))
            .expectNextCount(2)
            .verifyComplete()
        contexts.map(System::identityHashCode).toSet().assert().hasSize(2)
        nodes.map(System::identityHashCode).toSet().assert().hasSize(2)
    }

    @Test
    fun `error handler failure should be suppressed on original`() {
        val original = IllegalStateException("typed-conversion")
        val handlerFailure = IllegalArgumentException("handler")
        val invalidNode = snapshotNode().apply { remove("state") }
        val gateway = gateway(
            backend = backend { Mono.just(invalidNode) },
            errorHandler = ErrorHandler { _, _ -> Mono.error(handlerFailure) },
        )

        StepVerifier.create(gateway.single(singleQuery { }))
            .expectErrorMatches { it !== handlerFailure && it.suppressed.single() === handlerFailure }
            .verify()
    }

    @Test
    fun `same error from handler should not self suppress`() {
        val original = IllegalStateException("backend")
        val gateway = gateway(
            backend = backend { Mono.error(original) },
            errorHandler = ErrorHandler { _, _ -> Mono.error(original) },
        )

        StepVerifier.create(gateway.dynamicSingle(singleQuery { }))
            .expectErrorMatches { it === original && it.suppressed.isEmpty() }
            .verify()
    }

    @Test
    fun `error boundary should cover request and result filters`() {
        listOf(
            errorFilter { _, _ -> Mono.error(IllegalStateException("request-filter")) },
            errorFilter { context, next ->
                next.filter(context).then(
                    Mono.fromRunnable {
                        context.asSingleQuery().rewriteResult { Mono.error(IllegalStateException("result-filter")) }
                    },
                )
            },
        ).forEach { filter ->
            val handled = CopyOnWriteArrayList<Throwable>()
            val gateway = gateway(
                backend { Mono.fromSupplier(::snapshotNode) },
                listOf(filter),
                ErrorHandler { _, error ->
                    handled += error
                    Mono.empty()
                },
            )

            StepVerifier.create(gateway.dynamicSingle(singleQuery { }))
                .expectErrorMatches { it === handled.single() }
                .verify()
        }
    }

    private fun errorFilter(
        block: (QueryContext<*, *>, FilterChain<QueryContext<*, *>>) -> Mono<Void>,
    ) = object : QueryFilter<QueryContext<*, *>> {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> =
            block(context, next)
    }

    private fun assertIsolated(
        expectedOutputs: Long = 2,
        filterResult: (ObjectNode) -> Mono<ObjectNode> = { Mono.just(it) },
        resubscribe: (Mono<ObjectNode>) -> Publisher<ObjectNode>,
    ) {
        val contexts = CopyOnWriteArrayList<QueryContext<*, *>>()
        val nodes = CopyOnWriteArrayList<ObjectNode>()
        val filter = object : QueryFilter<QueryContext<*, *>> {
            override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
                contexts += context
                context.setAttribute("subscription", contexts.size)
                return next.filter(context).then(
                    Mono.fromRunnable {
                        context.asSingleQuery().rewriteResult { result ->
                            result.flatMap { node ->
                                nodes += node
                                node.withObject("state").put("seen", true)
                                filterResult(node)
                            }
                        }
                    },
                )
            }
        }
        val publisher = gateway(backend { Mono.fromSupplier(::snapshotNode) }, filters = listOf(filter))
            .dynamicSingle(singleQuery { })

        StepVerifier.create(resubscribe(publisher)).expectNextCount(expectedOutputs).verifyComplete()
        contexts.map(System::identityHashCode).toSet().assert().hasSize(2)
        nodes.map(System::identityHashCode).toSet().assert().hasSize(2)
        contexts.forEach { it.getAttribute<Int>("subscription").assert().isNotNull() }
        nodes.forEach { it.path("state").path("seen").booleanValue().assert().isTrue() }
    }

    private fun gateway(
        backend: SnapshotQueryBackend,
        filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
        errorHandler: ErrorHandler<QueryContext<*, *>> = ErrorHandler { _, error -> Mono.error(error) },
    ) = DefaultSnapshotQueryGateway<TestState>(
        MOCK_AGGREGATE_METADATA,
        backend,
        JsonSerializer.typeFactory.constructParametricType(MaterializedSnapshot::class.java, TestState::class.java),
        filters,
        errorHandler,
    )

    private fun backend(
        cursor: () -> Mono<CursorPage<ObjectNode>> = {
            Mono.error(UnsupportedOperationException("Cursor query is not supported."))
        },
        single: () -> Mono<ObjectNode>,
    ) = object : SnapshotQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "subscription"
        override fun single(query: ISingleQuery): Mono<ObjectNode> = single()
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())
        override fun cursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> = cursor()
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private data class TestState(val value: String)

    private companion object {
        fun snapshotNode(): ObjectNode = """
            {"contextName":"mock","aggregateName":"mock","tenantId":"tenant","ownerId":"_default_",
             "spaceId":"_default_","aggregateId":"aggregate","version":1,"eventId":"event",
             "firstOperator":"operator","operator":"operator","firstEventTime":1,"eventTime":1,
             "state":{"value":"state-value"},"snapshotTime":1,"tags":{},"deleted":false}
        """.toJsonNode()
    }
}
