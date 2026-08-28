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
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.EmptyFilterChain
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryService
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class QueryServiceProxyTest {
    private val namedAggregate = MaterializedNamedAggregate("test", "proxy")

    @Test
    fun `event stream proxy should delegate every operation to gateway`() {
        val gateway = RecordingEventStreamQueryGateway()
        val proxy = EventStreamQueryServiceProxy(NoOpEventStreamQueryService(namedAggregate), gateway)

        proxy.single(singleQuery { })
        proxy.dynamicSingle(singleQuery { })
        proxy.list(listQuery { })
        proxy.dynamicList(listQuery { })
        proxy.paged(pagedQuery { })
        proxy.dynamicPaged(pagedQuery { })
        proxy.count(MatchAllFilter)
        proxy.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))

        gateway.queryTypes.assert().containsExactly(
            QueryType.SINGLE,
            QueryType.DYNAMIC_SINGLE,
            QueryType.LIST,
            QueryType.DYNAMIC_LIST,
            QueryType.PAGED,
            QueryType.DYNAMIC_PAGED,
            QueryType.COUNT,
            QueryType.AGGREGATION,
        )
        gateway.namedAggregates.toSet().assert().containsExactly(namedAggregate)
    }

    @Test
    fun `event stream proxy should preserve schema provider capability`() {
        val initial = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
        val refreshed = QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap())
        val delegate = object :
            EventStreamQueryService by NoOpEventStreamQueryService(namedAggregate),
            QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(initial)

            override fun refresh(): Mono<QueryModelSchema> = Mono.just(refreshed)
        }
        val provider = EventStreamQueryServiceProxy(
            delegate,
            RecordingEventStreamQueryGateway(),
        ).requiredQueryModelSchemaProvider()

        provider.schema().block().assert().isSameAs(initial)
        provider.refresh().block().assert().isSameAs(refreshed)
    }

    @Test
    fun `event stream proxy should report unavailable schema reactively`() {
        val proxy = EventStreamQueryServiceProxy(
            NoOpEventStreamQueryService(namedAggregate),
            RecordingEventStreamQueryGateway(),
        )

        val unavailableSchema = proxy.requiredQueryModelSchemaProvider().schema()

        assertThrows<QuerySchemaUnavailableException> {
            unavailableSchema.block()
        }
    }

    @Test
    fun `snapshot proxy should preserve delegate identity`() {
        val delegate = NoOpSnapshotQueryService<Any>(namedAggregate)
        val gateway = DefaultSnapshotQueryGateway(EmptyFilterChain.instance<QueryContext<*, *>>())
        val proxy = SnapshotQueryServiceProxy(delegate, gateway)

        proxy.name.assert().isEqualTo(delegate.name)
        proxy.namedAggregate.assert().isSameAs(delegate.namedAggregate)
    }

    @Test
    fun `snapshot proxy should route aggregation through gateway`() {
        val gateway = DefaultSnapshotQueryGateway(
            FilterChain { context ->
                context.queryType.assert().isEqualTo(QueryType.AGGREGATION)
                context.asAggregationQuery().setResult(Flux.empty())
                Mono.empty()
            }
        )
        val proxy = SnapshotQueryServiceProxy(NoOpSnapshotQueryService<Any>(namedAggregate), gateway)

        proxy.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
            .collectList()
            .block()
            .assert()
            .isEmpty()
    }

    @Test
    fun `registrar should preserve raw service when gateway is unavailable`() {
        val beanFactory = DefaultListableBeanFactory()
        val rawService = NoOpEventStreamQueryService(namedAggregate)
        beanFactory.registerSingleton(
            "eventStreamQueryServiceFactory",
            EventStreamQueryServiceFactory { rawService },
        )
        val registrar = EventStreamQueryServiceRegistrar()
        registrar.setBeanFactory(beanFactory)

        val entry = mapOf<MaterializedNamedAggregate, Class<*>>(namedAggregate to Any::class.java).entries.single()
        registrar.registerQueryService(entry, beanFactory)

        beanFactory.getBean(EventStreamQueryService::class.java).assert().isSameAs(rawService)
    }

    private class RecordingEventStreamQueryGateway : EventStreamQueryGateway {
        val queryTypes = mutableListOf<QueryType>()
        val namedAggregates = mutableListOf<NamedAggregate>()

        override fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DomainEventStream> =
            record(QueryType.SINGLE, namedAggregate, Mono.empty())

        override fun dynamicSingle(
            namedAggregate: NamedAggregate,
            singleQuery: ISingleQuery
        ): Mono<DynamicDocument> = record(QueryType.DYNAMIC_SINGLE, namedAggregate, Mono.empty())

        override fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<DomainEventStream> =
            record(QueryType.LIST, namedAggregate, Flux.empty())

        override fun dynamicList(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<DynamicDocument> =
            record(QueryType.DYNAMIC_LIST, namedAggregate, Flux.empty())

        override fun paged(
            namedAggregate: NamedAggregate,
            pagedQuery: IPagedQuery
        ): Mono<PagedList<DomainEventStream>> = record(QueryType.PAGED, namedAggregate, Mono.just(PagedList.empty()))

        override fun dynamicPaged(
            namedAggregate: NamedAggregate,
            pagedQuery: IPagedQuery
        ): Mono<PagedList<DynamicDocument>> =
            record(QueryType.DYNAMIC_PAGED, namedAggregate, Mono.just(PagedList.empty()))

        override fun count(namedAggregate: NamedAggregate, filter: FilterExpression): Mono<Long> =
            record(QueryType.COUNT, namedAggregate, Mono.just(0L))

        override fun aggregate(
            namedAggregate: NamedAggregate,
            query: AggregationQuery,
        ): Flux<DynamicDocument> = record(QueryType.AGGREGATION, namedAggregate, Flux.empty())

        private fun <T> record(queryType: QueryType, namedAggregate: NamedAggregate, result: T): T {
            queryTypes += queryType
            namedAggregates += namedAggregate
            return result
        }
    }
}
