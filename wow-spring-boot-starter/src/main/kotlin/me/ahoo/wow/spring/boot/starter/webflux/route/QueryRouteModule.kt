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

import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventStreamAggregationHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventStreamSchemaHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.EventStreamSchemaRefreshHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.ListQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.PagedQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.RewriteRequestFilter
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
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

class QueryRouteModule(
    snapshotQueryGateway: SnapshotQueryGateway,
    snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
    eventStreamQueryGateway: EventStreamQueryGateway,
    rewriteRequestFilter: RewriteRequestFilter,
    exceptionHandler: RequestExceptionHandler,
    eventStreamQueryServiceFactory: EventStreamQueryServiceFactory = NoOpEventStreamQueryServiceFactory,
) : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory> = listOf(
        SnapshotSchemaHandlerFunctionFactory(
            snapshotQueryServiceFactory = snapshotQueryServiceFactory,
            exceptionHandler = exceptionHandler,
        ),
        SnapshotSchemaRefreshHandlerFunctionFactory(
            snapshotQueryServiceFactory = snapshotQueryServiceFactory,
            exceptionHandler = exceptionHandler,
        ),
        LoadSnapshotHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            exceptionHandler = exceptionHandler
        ),
        ListQuerySnapshotHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        ListQuerySnapshotStateHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        PagedQuerySnapshotHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        PagedQuerySnapshotStateHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        SingleSnapshotHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        SingleSnapshotStateHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        CountSnapshotHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        SnapshotAggregationHandlerFunctionFactory(
            snapshotQueryGateway = snapshotQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        LoadEventStreamHandlerFunctionFactory(
            eventStreamQueryGateway = eventStreamQueryGateway,
            exceptionHandler = exceptionHandler
        ),
        EventStreamAggregationHandlerFunctionFactory(
            eventStreamQueryGateway = eventStreamQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler,
        ),
        EventStreamSchemaHandlerFunctionFactory(
            eventStreamQueryServiceFactory = eventStreamQueryServiceFactory,
            exceptionHandler = exceptionHandler,
        ),
        EventStreamSchemaRefreshHandlerFunctionFactory(
            eventStreamQueryServiceFactory = eventStreamQueryServiceFactory,
            exceptionHandler = exceptionHandler,
        ),
        ListQueryEventStreamHandlerFunctionFactory(
            eventStreamQueryGateway = eventStreamQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        PagedQueryEventStreamHandlerFunctionFactory(
            eventStreamQueryGateway = eventStreamQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
        CountEventStreamHandlerFunctionFactory(
            eventStreamQueryGateway = eventStreamQueryGateway,
            rewriteRequestFilter = rewriteRequestFilter,
            exceptionHandler = exceptionHandler
        ),
    )
}
