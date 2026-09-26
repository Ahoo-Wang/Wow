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
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.AdmittedQuery
import me.ahoo.wow.query.BackendPage
import me.ahoo.wow.query.CursorPositionCodec
import me.ahoo.wow.query.GroupWindow
import me.ahoo.wow.query.PageWindow
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.DefaultEventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryBackend
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.spring.query.EventStreamQueryGatewayRegistrar.Companion.EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar.Companion.SNAPSHOT_QUERY_OBSERVER_BEAN_NAME
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
    private val snapshotObserverCalls = AtomicInteger()
    private val eventObserverCalls = AtomicInteger()

    @Test
    fun `should register aggregate bound gateways with state generic`() {
        val snapshotFactoryCalls = AtomicInteger()
        val eventFactoryCalls = AtomicInteger()
        val filterCalls = AtomicInteger()
        val snapshotBackend = SnapshotBackend(NAMED_AGGREGATE)
        val eventBackend = EventBackend(NAMED_AGGREGATE)
        val snapshotStorage = RecordingStorageAdapter()
        val eventStorage = RecordingStorageAdapter()
        val context = newContext(
            snapshotFactoryCalls,
            eventFactoryCalls,
            filterCalls,
            snapshotBackend,
            eventBackend,
            snapshotStorage,
            eventStorage,
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
                singleQuery { }
            ).block()!!.state.assert().isInstanceOf(QueryRegistrarOrderState::class.java)
            (eventStream as EventStreamQueryGateway).dynamicSingle(singleQuery { }).block()
            // The Catalog compiles each model once from its storage facts and every gateway reads that schema.
            snapshotStorage.factsCalls.get().assert().isOne()
            snapshotBackend.backendModel.get().assert().isEqualTo(QueryModel.SNAPSHOT)
            eventStorage.factsCalls.get().assert().isOne()
            eventBackend.backendModel.get().assert().isEqualTo(QueryModel.EVENT_STREAM)
            filterCalls.get().assert().isEqualTo(2)
            snapshotObserverCalls.get().assert().isOne()
            eventObserverCalls.get().assert().isOne()
            // Once for the gateway's backend and once for the Catalog's storage adapter; singletons thereafter.
            snapshotFactoryCalls.get().assert().isEqualTo(2)
            eventFactoryCalls.get().assert().isEqualTo(2)
            context.getBean(SNAPSHOT_GATEWAY_BEAN_NAME)
            context.getBean(EVENT_STREAM_GATEWAY_BEAN_NAME)
            snapshotFactoryCalls.get().assert().isEqualTo(2)
            eventFactoryCalls.get().assert().isEqualTo(2)

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
        snapshotStorage: RecordingStorageAdapter = RecordingStorageAdapter(),
        eventStorage: RecordingStorageAdapter = RecordingStorageAdapter(),
    ): GenericApplicationContext = GenericApplicationContext().apply {
        registerBean(
            QuerySchemaCatalog::class.java,
            Supplier {
                QuerySchemaCatalog(
                    snapshots = getBean(SnapshotQueryBackendFactory::class.java),
                    eventStreams = getBean(EventStreamQueryBackendFactory::class.java),
                )
            },
        )
        registerBean(
            SnapshotQueryBackendFactory::class.java,
            Supplier {
                object : SnapshotQueryBackendFactory {
                    override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> {
                        snapshotFactoryCalls.incrementAndGet()
                        return QueryBackendBinding(snapshotBackend, snapshotStorage)
                    }
                }
            },
        )
        registerBean(
            EventStreamQueryBackendFactory::class.java,
            Supplier {
                EventStreamQueryBackendFactory {
                    eventFactoryCalls.incrementAndGet()
                    QueryBackendBinding(eventBackend, eventStorage)
                }
            },
        )
        registerBean(
            SNAPSHOT_QUERY_OBSERVER_BEAN_NAME,
            QueryObserver::class.java,
            Supplier {
                object : QueryObserver {
                    override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) {
                        snapshotObserverCalls.incrementAndGet()
                    }
                }
            },
        )
        registerBean(
            EVENT_STREAM_QUERY_OBSERVER_BEAN_NAME,
            QueryObserver::class.java,
            Supplier {
                object : QueryObserver {
                    override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) {
                        eventObserverCalls.incrementAndGet()
                    }
                }
            },
        )
        registerBean(
            QueryFilter::class.java,
            Supplier {
                object : QueryFilter {
                    override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
                        filterCalls.incrementAndGet()
                        return Mono.just(context.query)
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
        val backendModel = AtomicReference<QueryModel>()
        override val name: String = "test"

        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> {
            val model = query.model
            return Mono.fromSupplier {
                backendModel.set(model)
                BackendPage(listOf(SNAPSHOT_JSON.toJsonNode()), 1)
            }
        }

        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()

        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0)

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
            Flux.empty()
    }

    private class EventBackend(
        override val namedAggregate: NamedAggregate,
    ) : EventStreamQueryBackend {
        val backendModel = AtomicReference<QueryModel>()

        override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

        override fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage> {
            val model = query.model
            return Mono.fromSupplier {
                backendModel.set(model)
                BackendPage(emptyList(), 0)
            }
        }

        override fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.empty()

        override fun count(query: AdmittedQuery<FilterExpression>): Mono<Long> = Mono.just(0)

        override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode> =
            Flux.empty()
    }

    /** Reports every logical field as exactly matchable and sortable, counting the Catalog's compilations. */
    private class RecordingStorageAdapter : QueryStorageAdapter {
        val factsCalls = AtomicInteger()

        override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> = Mono.fromSupplier {
            factsCalls.incrementAndGet()
            QueryStorageFacts(me.ahoo.wow.spring.query.testQueryBindings(logicalSchema))
        }
    }

    private class RecordingSchemaProvider(model: QueryModel) : QueryModelSchemaProvider {
        val schema = me.ahoo.wow.spring.query.testQuerySchema(model)
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
