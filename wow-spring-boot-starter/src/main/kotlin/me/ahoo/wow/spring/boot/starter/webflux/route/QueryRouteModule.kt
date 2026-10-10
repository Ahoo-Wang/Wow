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

package me.ahoo.wow.spring.boot.starter.webflux.route

import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Event
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys.Snapshot
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.toStateDocument
import me.ahoo.wow.query.snapshot.toStateDocumentCursorPage
import me.ahoo.wow.query.snapshot.toStateDocumentPagedList
import me.ahoo.wow.spring.query.eventStreamQueryGatewayBeanName
import me.ahoo.wow.spring.query.snapshotQueryGatewayBeanName
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.AggregationQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.CountQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.CursorQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import me.ahoo.wow.webflux.route.query.ListQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.PagedQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import me.ahoo.wow.webflux.route.query.QuerySchemaHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.QuerySchemaRefreshHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.SingleQueryHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import org.springframework.beans.factory.BeanFactory
import reactor.core.publisher.Mono

@Suppress("DEPRECATION")
class QueryRouteModule(
    private val beanFactory: BeanFactory,
    queryRequestScope: QueryRequestScope,
    exceptionHandler: RequestExceptionHandler,
    guard: HttpQueryGuard = HttpQueryGuard(),
) : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory> = listOf(
        QuerySchemaHandlerFunctionFactory(Snapshot.SCHEMA, ::snapshotGateway, exceptionHandler, guard),
        LoadSnapshotHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        ListQueryHandlerFunctionFactory(
            Snapshot.LIST_QUERY,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        ListQueryHandlerFunctionFactory(
            Snapshot.LIST_QUERY_STATE,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard,
        ) { it.toStateDocument() },
        PagedQueryHandlerFunctionFactory(
            Snapshot.PAGED_QUERY,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        PagedQueryHandlerFunctionFactory(
            Snapshot.PAGED_QUERY_STATE,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard,
        ) { it.toStateDocumentPagedList() },
        CursorQueryHandlerFunctionFactory(
            Snapshot.CURSOR_QUERY,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        CursorQueryHandlerFunctionFactory(
            Snapshot.CURSOR_QUERY_STATE,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard,
        ) { it.toStateDocumentCursorPage() },
        SingleQueryHandlerFunctionFactory(
            Snapshot.SINGLE,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        SingleQueryHandlerFunctionFactory(
            Snapshot.SINGLE_STATE,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard,
        ) { it.toStateDocument() },
        CountQueryHandlerFunctionFactory(Snapshot.COUNT, ::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        AggregationQueryHandlerFunctionFactory(
            Snapshot.AGGREGATION,
            ::snapshotGateway,
            queryRequestScope,
            exceptionHandler,
            guard,
        ),
        QuerySchemaHandlerFunctionFactory(Event.SCHEMA, ::eventStreamGateway, exceptionHandler, guard),
        LoadEventStreamHandlerFunctionFactory(::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
        ListQueryHandlerFunctionFactory(
            Event.LIST_QUERY,
            ::eventStreamGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        PagedQueryHandlerFunctionFactory(
            Event.PAGED_QUERY,
            ::eventStreamGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        CursorQueryHandlerFunctionFactory(
            Event.CURSOR_QUERY,
            ::eventStreamGateway,
            queryRequestScope,
            exceptionHandler,
            guard
        ),
        CountQueryHandlerFunctionFactory(Event.COUNT, ::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
        AggregationQueryHandlerFunctionFactory(
            Event.AGGREGATION,
            ::eventStreamGateway,
            queryRequestScope,
            exceptionHandler,
            guard,
        ),
        QuerySchemaRefreshHandlerFunctionFactory(
            Snapshot.SCHEMA_REFRESH,
            ::snapshotGateway,
            { revalidate(it, QueryModel.SNAPSHOT) },
            exceptionHandler,
            guard,
        ),
        QuerySchemaRefreshHandlerFunctionFactory(
            Event.SCHEMA_REFRESH,
            ::eventStreamGateway,
            { revalidate(it, QueryModel.EVENT_STREAM) },
            exceptionHandler,
            guard,
        ),
    )

    // compat(wow<9.2): what the 9.1 `POST …/schema/refresh` routes did, through the catalog: reload the one model.
    private fun revalidate(metadata: AggregateMetadata<*, *>, model: QueryModel): Mono<Void> {
        val catalog = beanFactory.getBeanProvider(QuerySchemaCatalog::class.java).ifAvailable ?: return Mono.empty()
        return catalog.revalidate(metadata.namedAggregate.toStringWithAlias(), model).then()
    }

    @Suppress("UNCHECKED_CAST")
    private fun snapshotGateway(metadata: AggregateMetadata<*, *>): SnapshotQueryGateway<Any> =
        beanFactory.getBean(
            metadata.namedAggregate.snapshotQueryGatewayBeanName(),
            SnapshotQueryGateway::class.java,
        ) as SnapshotQueryGateway<Any>

    private fun eventStreamGateway(metadata: AggregateMetadata<*, *>): EventStreamQueryGateway =
        beanFactory.getBean(
            metadata.namedAggregate.eventStreamQueryGatewayBeanName(),
            EventStreamQueryGateway::class.java,
        )
}
