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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackendFactory
import me.ahoo.wow.query.event.filter.EventStreamQueryFilter
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.filter.AbacQueryFilter
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import reactor.util.context.ContextView
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger

class QueryAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `query registrars exclude retryable event filter and retain query filters`() {
        val genericCalls = AtomicInteger()
        val snapshotCalls = AtomicInteger()
        val eventCalls = AtomicInteger()

        contextRunner.enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(RecordingSnapshotQueryBackendFactory::class.java, { RecordingSnapshotQueryBackendFactory() })
            .withBean(EventStreamQueryBackendFactory::class.java, { NoOpEventStreamQueryBackendFactory })
            .withBean(RetryableFilter::class.java, { RetryableFilter<DomainEventExchange<Any>>() })
            .withBean(RecordingQueryFilter::class.java, { RecordingQueryFilter(genericCalls) })
            .withBean(RecordingSnapshotQueryFilter::class.java, { RecordingSnapshotQueryFilter(snapshotCalls) })
            .withBean(RecordingEventStreamQueryFilter::class.java, { RecordingEventStreamQueryFilter(eventCalls) })
            .run { context: AssertableApplicationContext ->
                @Suppress("UNCHECKED_CAST")
                val snapshot = context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME) as SnapshotQueryGateway<Any>
                val event = context.getBean(EVENT_STREAM_GATEWAY_BEAN_NAME) as EventStreamQueryGateway

                snapshot.dynamicSingle(singleQuery { }).test().expectNextCount(1).verifyComplete()
                event.dynamicSingle(singleQuery { }).test().verifyComplete()

                context.assert().hasSingleBean(RetryableFilter::class.java)
                genericCalls.get().assert().isEqualTo(2)
                snapshotCalls.get().assert().isOne()
                eventCalls.get().assert().isOne()
            }
    }

    @Test
    fun `should register aggregate gateways and only shared query infrastructure`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasBean(SNAPSHOT_GATEWAY_BEAN_NAME)
                    .hasBean(EVENT_STREAM_GATEWAY_BEAN_NAME)
                    .hasBean("noOpSnapshotQueryBackendFactory")
                    .hasBean("noOpEventStreamQueryBackendFactory")
                    .hasBean("snapshotQueryErrorHandler")
                    .hasBean("eventStreamQueryErrorHandler")
                    .doesNotHaveBean("stateObjectNodeMaskerRegistry")
                    .doesNotHaveBean("eventStreamObjectNodeMaskerRegistry")
                    .doesNotHaveBean("maskingSnapshotQueryFilter")
                    .doesNotHaveBean("maskingEventStreamQueryFilter")
                    .doesNotHaveBean("snapshotQueryFilterChain")
                    .doesNotHaveBean("eventStreamQueryFilterChain")
                    .doesNotHaveBean("snapshotQueryGateway")
                    .doesNotHaveBean("eventStreamQueryGateway")

                context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME).assert()
                    .isInstanceOf(SnapshotQueryGateway::class.java)
                context.getBean(EVENT_STREAM_GATEWAY_BEAN_NAME).assert()
                    .isInstanceOf(EventStreamQueryGateway::class.java)
            }
    }

    @Test
    fun `unavailable backends should fail every operation with exact error`() {
        contextRunner.enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                val snapshotFactory = context.getBean(SnapshotQueryBackendFactory::class.java)
                val eventFactory = context.getBean(EventStreamQueryBackendFactory::class.java)
                val snapshot = snapshotFactory.create<Any>(MOCK_AGGREGATE_METADATA)
                val event = eventFactory.create(MOCK_AGGREGATE_METADATA)

                listOf(snapshot, event).forEach { backend ->
                    backend.single(singleQuery { }).test().expectErrorSatisfies(::assertUnavailable).verify()
                    backend.list(ListQuery(MatchAllFilter, limit = 1))
                        .test().expectErrorSatisfies(::assertUnavailable).verify()
                    backend.paged(PagedQuery(MatchAllFilter))
                        .test().expectErrorSatisfies(::assertUnavailable).verify()
                    backend.cursor(CursorQuery(MatchAllFilter))
                        .test().expectErrorSatisfies(::assertUnavailable).verify()
                    backend.count(MatchAllFilter).test().expectErrorSatisfies(::assertUnavailable).verify()
                    backend.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
                        .test().expectErrorSatisfies(::assertUnavailable).verify()
                }
            }
    }

    @Test
    fun `aggregate gateway should apply policies while backend remains raw`() {
        contextRunner.enableWow()
            .withUserConfiguration(QueryAutoConfiguration::class.java)
            .withBean(RecordingSnapshotQueryBackendFactory::class.java, { RecordingSnapshotQueryBackendFactory() })
            .withBean(AbacQueryFilter::class.java, { TestAbacQueryFilter })
            .run { context: AssertableApplicationContext ->
                val factory = context.getBean(RecordingSnapshotQueryBackendFactory::class.java)

                @Suppress("UNCHECKED_CAST")
                val gateway = context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME) as SnapshotQueryGateway<Any>
                val query = singleQuery { }

                gateway.dynamicSingle(query).test().expectNextCount(1).verifyComplete()
                factory.backend.lastQuery!!.filter.operator.assert()
                    .isNotEqualTo(me.ahoo.wow.api.query.FilterOperator.MATCH_ALL)

                val rawBackend = factory.create<Any>(MOCK_AGGREGATE_METADATA)
                rawBackend.assert().isSameAs(factory.backend)
                rawBackend.single(query).test()
                    .consumeNextWith { it["state"][SECRET].stringValue().assert().isEqualTo(RAW_SECRET) }
                    .verifyComplete()
                factory.backend.lastQuery.assert().isSameAs(query)
            }
    }

    private fun assertUnavailable(error: Throwable) {
        error.assert().isInstanceOf(WowException::class.java)
        (error as WowException).errorCode.assert().isEqualTo(ErrorCodes.INTERNAL_SERVER_ERROR)
        error.message.assert().isEqualTo(
            "No query backend is configured for aggregate[${MOCK_AGGREGATE_METADATA.namedAggregate}]."
        )
    }

    internal class RecordingSnapshotQueryBackendFactory : SnapshotQueryBackendFactory {
        val backend = RecordingSnapshotQueryBackend()
        override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryBackend = backend
    }

    internal class RecordingSnapshotQueryBackend : SnapshotQueryBackend by
    NoOpSnapshotQueryBackend(MOCK_AGGREGATE_METADATA) {
        override val name: String = "raw"
        var lastQuery: me.ahoo.wow.api.query.ISingleQuery? = null

        override fun single(query: me.ahoo.wow.api.query.ISingleQuery): Mono<ObjectNode> {
            lastQuery = query
            return Mono.just(
                JsonNodeFactory.instance.objectNode().set(
                    "state",
                    JsonNodeFactory.instance.objectNode().put(SECRET, RAW_SECRET),
                )
            )
        }
    }

    internal object TestAbacQueryFilter : AbacQueryFilter() {
        override fun getPrincipalTags(
            contextView: ContextView,
            context: me.ahoo.wow.query.filter.QueryContext<*, *>
        ): Mono<AbacTags> = mapOf("role" to listOf("*")).toMono()
    }

    internal class RecordingQueryFilter(
        private val calls: AtomicInteger,
    ) : QueryFilter<QueryContext<*, *>> {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls.incrementAndGet()
            return next.filter(context)
        }
    }

    internal class RecordingSnapshotQueryFilter(
        private val calls: AtomicInteger,
    ) : SnapshotQueryFilter {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls.incrementAndGet()
            return next.filter(context)
        }
    }

    internal class RecordingEventStreamQueryFilter(
        private val calls: AtomicInteger,
    ) : EventStreamQueryFilter {
        override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> {
            calls.incrementAndGet()
            return next.filter(context)
        }
    }

    private companion object {
        const val SNAPSHOT_GATEWAY_BEAN_NAME = "example.order.SnapshotQueryGateway"
        const val EVENT_STREAM_GATEWAY_BEAN_NAME = "example.order.EventStreamQueryGateway"
        const val SECRET = "secret"
        const val RAW_SECRET = "raw-secret"
    }
}
