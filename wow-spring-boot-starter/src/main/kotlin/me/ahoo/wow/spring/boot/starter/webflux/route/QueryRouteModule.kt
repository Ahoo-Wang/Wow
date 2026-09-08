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

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.CursorQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventStreamAggregationHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventStreamSchemaHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventStreamSchemaRefreshHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.ListQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.PagedQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.CursorQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.CursorQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SnapshotAggregationHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SnapshotSchemaHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SnapshotSchemaRefreshHandlerFunctionFactory
import org.springframework.beans.factory.BeanFactory

class QueryRouteModule(
    private val beanFactory: BeanFactory,
    snapshotQueryBackendFactory: SnapshotQueryBackendFactory,
    eventStreamQueryBackendFactory: EventStreamQueryBackendFactory,
    queryRequestScope: QueryRequestScope,
    exceptionHandler: RequestExceptionHandler,
    guard: HttpQueryGuard = HttpQueryGuard(),
) : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory> = listOf(
        SnapshotSchemaHandlerFunctionFactory(snapshotQueryBackendFactory, exceptionHandler),
        SnapshotSchemaRefreshHandlerFunctionFactory(snapshotQueryBackendFactory, exceptionHandler),
        LoadSnapshotHandlerFunctionFactory(::snapshotGateway, exceptionHandler, queryRequestScope, guard),
        ListQuerySnapshotHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        ListQuerySnapshotStateHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        PagedQuerySnapshotHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        CursorQuerySnapshotHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        PagedQuerySnapshotStateHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        CursorQuerySnapshotStateHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        SingleSnapshotHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        SingleSnapshotStateHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        CountSnapshotHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        SnapshotAggregationHandlerFunctionFactory(::snapshotGateway, queryRequestScope, exceptionHandler, guard),
        LoadEventStreamHandlerFunctionFactory(::eventStreamGateway, exceptionHandler, queryRequestScope, guard),
        EventStreamAggregationHandlerFunctionFactory(::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
        EventStreamSchemaHandlerFunctionFactory(eventStreamQueryBackendFactory, exceptionHandler),
        EventStreamSchemaRefreshHandlerFunctionFactory(eventStreamQueryBackendFactory, exceptionHandler),
        ListQueryEventStreamHandlerFunctionFactory(::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
        PagedQueryEventStreamHandlerFunctionFactory(::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
        CursorQueryEventStreamHandlerFunctionFactory(::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
        CountEventStreamHandlerFunctionFactory(::eventStreamGateway, queryRequestScope, exceptionHandler, guard),
    )

    @Suppress("UNCHECKED_CAST")
    private fun snapshotGateway(metadata: AggregateMetadata<*, *>): SnapshotQueryGateway<Any> =
        beanFactory.getBean(
            "${metadata.namedAggregate.toStringWithAlias()}.SnapshotQueryGateway",
            SnapshotQueryGateway::class.java,
        ) as SnapshotQueryGateway<Any>

    private fun eventStreamGateway(metadata: AggregateMetadata<*, *>): EventStreamQueryGateway =
        beanFactory.getBean(
            "${metadata.namedAggregate.toStringWithAlias()}.EventStreamQueryGateway",
            EventStreamQueryGateway::class.java,
        )
}
