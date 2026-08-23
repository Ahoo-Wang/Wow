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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.EmptyFilterChain
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryService
import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryService
import me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class QueryServiceProxyTest {
    private val namedAggregate = MaterializedNamedAggregate("test", "proxy")

    @Test
    fun `event stream proxy should delegate every operation to handler`() {
        val handler = RecordingEventStreamQueryHandler()
        val proxy = EventStreamQueryServiceProxy(NoOpEventStreamQueryService(namedAggregate), handler)

        proxy.single(singleQuery { })
        proxy.dynamicSingle(singleQuery { })
        proxy.list(listQuery { })
        proxy.dynamicList(listQuery { })
        proxy.paged(pagedQuery { })
        proxy.dynamicPaged(pagedQuery { })
        proxy.count(MatchAllFilter)

        handler.queryTypes.assert().containsExactly(
            QueryType.SINGLE,
            QueryType.DYNAMIC_SINGLE,
            QueryType.LIST,
            QueryType.DYNAMIC_LIST,
            QueryType.PAGED,
            QueryType.DYNAMIC_PAGED,
            QueryType.COUNT,
        )
        handler.namedAggregates.toSet().assert().containsExactly(namedAggregate)
    }

    @Test
    fun `snapshot proxy should preserve delegate identity`() {
        val delegate = NoOpSnapshotQueryService<Any>(namedAggregate)
        val handler = DefaultSnapshotQueryHandler(EmptyFilterChain.instance<QueryContext<*, *>>())
        val proxy = SnapshotQueryServiceProxy(delegate, handler)

        proxy.name.assert().isEqualTo(delegate.name)
        proxy.namedAggregate.assert().isSameAs(delegate.namedAggregate)
    }

    @Test
    fun `registrar should preserve raw service when handler is unavailable`() {
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

    private class RecordingEventStreamQueryHandler : EventStreamQueryHandler {
        val queryTypes = mutableListOf<QueryType>()
        val namedAggregates = mutableListOf<NamedAggregate>()

        override fun handle(context: QueryContext<*, *>): Mono<Void> = Mono.empty()

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

        private fun <T> record(queryType: QueryType, namedAggregate: NamedAggregate, result: T): T {
            queryTypes += queryType
            namedAggregates += namedAggregate
            return result
        }
    }
}
