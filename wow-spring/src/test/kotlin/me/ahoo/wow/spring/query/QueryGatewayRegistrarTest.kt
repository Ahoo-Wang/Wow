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

package me.ahoo.wow.spring.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.Filter
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.NoOpEventStreamQueryBackend
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.ResolvableType
import org.springframework.core.type.AnnotationMetadata
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class QueryGatewayRegistrarTest {

    @Test
    fun `should register aggregate bound gateways with state generic`() {
        val snapshotFactoryCalls = AtomicInteger()
        val eventFactoryCalls = AtomicInteger()
        val filterCalls = AtomicInteger()
        val context = newContext(snapshotFactoryCalls, eventFactoryCalls, filterCalls)

        context.use {
            registerGateways(context)
            context.refresh()

            val snapshot = context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME)
            val eventStream = context.getBean(EVENT_STREAM_GATEWAY_BEAN_NAME)
            snapshot.assert().isInstanceOf(SnapshotQueryGateway::class.java)
            eventStream.assert().isInstanceOf(EventStreamQueryGateway::class.java)
            val snapshotProvider: ObjectProvider<SnapshotQueryGateway<QueryRegistrarOrderState>> = context.getBeanProvider(
                ResolvableType.forClassWithGenerics(
                    SnapshotQueryGateway::class.java,
                    QueryRegistrarOrderState::class.java,
                ),
            )
            snapshotProvider.getObject().assert().isSameAs(snapshot)

            @Suppress("UNCHECKED_CAST")
            val typedSnapshot = snapshot as SnapshotQueryGateway<QueryRegistrarOrderState>
            typedSnapshot.single(
                singleQuery { },
            ).block()!!.state.assert().isInstanceOf(
                QueryRegistrarOrderState::class.java,
            )
            filterCalls.get().assert().isOne()
            snapshotFactoryCalls.get().assert().isOne()
            eventFactoryCalls.get().assert().isOne()
            context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME)
            context.getBean(EVENT_STREAM_GATEWAY_BEAN_NAME)
            snapshotFactoryCalls.get().assert().isOne()
            eventFactoryCalls.get().assert().isOne()

            context.getBeanNamesForType(SnapshotQueryGateway::class.java)
                .assert().containsExactly(SNAPSHOT_GATEWAY_BEAN_NAME)
            context.getBeanNamesForType(EventStreamQueryGateway::class.java)
                .assert().containsExactly(EVENT_STREAM_GATEWAY_BEAN_NAME)
            context.beanDefinitionNames.filter { it.endsWith(".SnapshotQueryService") }.assert().isEmpty()
            context.beanDefinitionNames.filter { it.endsWith(".EventStreamQueryService") }.assert().isEmpty()
            context.containsBean("snapshotQueryGateway").assert().isFalse()
            context.containsBean("eventStreamQueryGateway").assert().isFalse()
            context.containsBean("test.order.SnapshotQueryService").assert().isFalse()
            context.containsBean("test.order.EventStreamQueryService").assert().isFalse()
        }
    }

    @Test
    fun `same-name custom gateways should remain untouched`() {
        val snapshotFactoryCalls = AtomicInteger()
        val eventFactoryCalls = AtomicInteger()
        val context = newContext(snapshotFactoryCalls, eventFactoryCalls, AtomicInteger())
        val customSnapshotGateway = DefaultSnapshotQueryGateway<QueryRegistrarOrderState>(
            namedAggregate = NAMED_AGGREGATE,
            backend = NoOpSnapshotQueryBackend(NAMED_AGGREGATE),
            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                QueryRegistrarOrderState::class.java,
            ),
        )
        val customEventGateway = DefaultEventStreamQueryGateway(
            namedAggregate = NAMED_AGGREGATE,
            backend = NoOpEventStreamQueryBackend(NAMED_AGGREGATE),
        )
        context.registerBean(
            SNAPSHOT_GATEWAY_BEAN_NAME,
            SnapshotQueryGateway::class.java,
            Supplier { customSnapshotGateway },
        )
        context.registerBean(
            EVENT_STREAM_GATEWAY_BEAN_NAME,
            EventStreamQueryGateway::class.java,
            Supplier { customEventGateway },
        )

        context.use {
            registerGateways(context)
            context.refresh()

            context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME).assert().isSameAs(customSnapshotGateway)
            context.getBean(EVENT_STREAM_GATEWAY_BEAN_NAME).assert().isSameAs(customEventGateway)
            snapshotFactoryCalls.get().assert().isZero()
            eventFactoryCalls.get().assert().isZero()
            context.beanDefinitionNames.filter { it.endsWith(".SnapshotQueryService") }.assert().isEmpty()
        }
    }

    private fun newContext(
        snapshotFactoryCalls: AtomicInteger,
        eventFactoryCalls: AtomicInteger,
        filterCalls: AtomicInteger,
    ): GenericApplicationContext = GenericApplicationContext().apply {
        registerBean(
            SnapshotQueryBackendFactory::class.java,
            Supplier {
                object : SnapshotQueryBackendFactory {
                    override fun <S : Any> create(namedAggregate: NamedAggregate): SnapshotQueryBackend {
                        snapshotFactoryCalls.incrementAndGet()
                        return SnapshotBackend(namedAggregate)
                    }
                }
            },
        )
        registerBean(
            EventStreamQueryBackendFactory::class.java,
            Supplier {
                EventStreamQueryBackendFactory { namedAggregate ->
                    eventFactoryCalls.incrementAndGet()
                    NoOpEventStreamQueryBackend(namedAggregate)
                }
            },
        )
        registerBean(
            "snapshotQueryErrorHandler",
            ErrorHandler::class.java,
            Supplier { ErrorHandler<QueryContext<*, *>> { _, error -> Mono.error(error) } },
        )
        registerBean(
            "eventStreamQueryErrorHandler",
            ErrorHandler::class.java,
            Supplier { ErrorHandler<QueryContext<*, *>> { _, error -> Mono.error(error) } },
        )
        registerBean(
            Filter::class.java,
            Supplier {
                Filter<QueryContext<*, *>> { context, next ->
                    filterCalls.incrementAndGet()
                    next.filter(context)
                }
            },
        )
    }

    private fun registerGateways(context: GenericApplicationContext) {
        listOf(SnapshotQueryGatewayRegistrar(), EventStreamQueryGatewayRegistrar()).forEach { registrar ->
            registrar.setBeanFactory(context)
            registrar.registerBeanDefinitions(
                AnnotationMetadata.introspect(QueryGatewayRegistrarTest::class.java),
                context,
            )
        }
    }

    private class SnapshotBackend(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryBackend {
        override val name: String = "test"

        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.just(SNAPSHOT_JSON.toJsonNode())

        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.empty()

        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = Mono.just(PagedList.empty())

        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(0)

        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.empty()
    }

    private companion object {
        val NAMED_AGGREGATE = MaterializedNamedAggregate("query-registrar-test", "order")
        const val SNAPSHOT_GATEWAY_BEAN_NAME = "test.order.SnapshotQueryGateway"
        const val EVENT_STREAM_GATEWAY_BEAN_NAME = "test.order.EventStreamQueryGateway"
        const val SNAPSHOT_JSON = """
            {"contextName":"query-registrar-test","aggregateName":"order","tenantId":"tenant",
             "ownerId":"_default_","spaceId":"_default_","aggregateId":"order-id","version":1,
             "eventId":"event-id","firstOperator":"operator","operator":"operator","firstEventTime":1,
             "eventTime":1,"state":{"id":"order-id"},"snapshotTime":1,"tags":{},"deleted":false}
        """
    }
}

class QueryRegistrarOrder(private val state: QueryRegistrarOrderState)

data class QueryRegistrarOrderState(val id: String)
