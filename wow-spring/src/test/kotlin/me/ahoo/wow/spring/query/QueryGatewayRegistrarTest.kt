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
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
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
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class QueryGatewayRegistrarTest {

    @Test
    fun `should register aggregate bound gateways with state generic`() {
        val snapshotFactoryCalls = AtomicInteger()
        val eventFactoryCalls = AtomicInteger()
        val filterCalls = AtomicInteger()
        val snapshotBackend = SnapshotBackend(NAMED_AGGREGATE)
        val eventBackend = EventBackend(NAMED_AGGREGATE)
        val snapshotSchemaProvider = RecordingSchemaProvider(QueryModel.SNAPSHOT)
        val eventSchemaProvider = RecordingSchemaProvider(QueryModel.EVENT_STREAM)
        val context = newContext(
            snapshotFactoryCalls,
            eventFactoryCalls,
            filterCalls,
            snapshotBackend,
            eventBackend,
            snapshotSchemaProvider,
            eventSchemaProvider,
        )

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
            (eventStream as EventStreamQueryGateway).dynamicSingle(singleQuery { }).block()
            snapshotSchemaProvider.schemaCalls.get().assert().isOne()
            snapshotBackend.backendSchema.get().assert().isSameAs(snapshotSchemaProvider.schema)
            eventSchemaProvider.schemaCalls.get().assert().isOne()
            eventBackend.backendSchema.get().assert().isSameAs(eventSchemaProvider.schema)
            filterCalls.get().assert().isEqualTo(2)
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
            context.containsBean("snapshotQueryGateway").assert().isFalse()
            context.containsBean("eventStreamQueryGateway").assert().isFalse()
        }
    }

    @Test
    fun `same-name custom gateways should remain untouched`() {
        val snapshotFactoryCalls = AtomicInteger()
        val eventFactoryCalls = AtomicInteger()
        val context = newContext(snapshotFactoryCalls, eventFactoryCalls, AtomicInteger())
        val customSnapshotBackend = SnapshotBackend(NAMED_AGGREGATE)
        val customSnapshotSchemaProvider = RecordingSchemaProvider(QueryModel.SNAPSHOT)
        val customSnapshotGateway = DefaultSnapshotQueryGateway<QueryRegistrarOrderState>(
            namedAggregate = NAMED_AGGREGATE,
            backend = customSnapshotBackend,
            schemaProvider = customSnapshotSchemaProvider,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                QueryRegistrarOrderState::class.java,
            ),
        )
        val customEventBackend = EventBackend(NAMED_AGGREGATE)
        val customEventSchemaProvider = RecordingSchemaProvider(QueryModel.EVENT_STREAM)
        val customEventGateway = DefaultEventStreamQueryGateway(
            namedAggregate = NAMED_AGGREGATE,
            backend = customEventBackend,
            schemaProvider = customEventSchemaProvider,
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
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
        }
    }

    private fun newContext(
        snapshotFactoryCalls: AtomicInteger,
        eventFactoryCalls: AtomicInteger,
        filterCalls: AtomicInteger,
        snapshotBackend: SnapshotBackend = SnapshotBackend(NAMED_AGGREGATE),
        eventBackend: EventBackend = EventBackend(NAMED_AGGREGATE),
        snapshotSchemaProvider: RecordingSchemaProvider = RecordingSchemaProvider(QueryModel.SNAPSHOT),
        eventSchemaProvider: RecordingSchemaProvider = RecordingSchemaProvider(QueryModel.EVENT_STREAM),
    ): GenericApplicationContext = GenericApplicationContext().apply {
        registerBean(QuerySchemaValidationMode::class.java, Supplier { QuerySchemaValidationMode.COMPATIBLE })
        registerBean(
            SnapshotQueryBackendFactory::class.java,
            Supplier {
                object : SnapshotQueryBackendFactory {
                    override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> {
                        snapshotFactoryCalls.incrementAndGet()
                        return QueryBackendBinding(snapshotBackend, snapshotSchemaProvider)
                    }
                }
            },
        )
        registerBean(
            EventStreamQueryBackendFactory::class.java,
            Supplier {
                EventStreamQueryBackendFactory {
                    eventFactoryCalls.incrementAndGet()
                    QueryBackendBinding(eventBackend, eventSchemaProvider)
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
            QueryFilter::class.java,
            Supplier {
                object : QueryFilter<QueryContext<*, *>> {
                    override fun filter(
                        context: QueryContext<*, *>,
                        next: FilterChain<QueryContext<*, *>>,
                    ): Mono<Void> {
                        filterCalls.incrementAndGet()
                        return next.filter(context)
                    }
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
        val backendSchema = AtomicReference<QueryModelSchema>()
        override val name: String = "test"

        override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> = Mono.fromSupplier {
            backendSchema.set(query.schema)
            SNAPSHOT_JSON.toJsonNode()
        }

        override fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()

        override fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> = Mono.just(
            PagedList.empty()
        )

        override fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> =
            Mono.just(CursorPage(emptyList(), null))

        override fun count(query: ResolvedQuery<FilterExpression>): Mono<Long> = Mono.just(0)

        override fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode> = Flux.empty()
    }

    private class EventBackend(
        override val namedAggregate: NamedAggregate,
    ) : EventStreamQueryBackend {
        val backendSchema = AtomicReference<QueryModelSchema>()

        override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> = Mono.fromSupplier {
            backendSchema.set(query.schema)
            null
        }

        override fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()

        override fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> = Mono.just(
            PagedList.empty()
        )

        override fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> =
            Mono.just(CursorPage(emptyList(), null))

        override fun count(query: ResolvedQuery<FilterExpression>): Mono<Long> = Mono.just(0)

        override fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode> = Flux.empty()
    }

    private class RecordingSchemaProvider(model: QueryModel) : QueryModelSchemaProvider {
        val schema = QueryModelSchema(model, emptySet(), emptyMap())
        val schemaCalls = AtomicInteger()

        override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
            schemaCalls.incrementAndGet()
            schema
        }

        override fun refresh(): Mono<QueryModelSchema> = schema()
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
