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

import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.ListQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.PagedQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.RewriteRequestCondition
import me.ahoo.wow.webflux.route.query.WebFluxQueryAdmission
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotStateHandlerFunctionFactory

class QueryRouteModule(
    queryGateway: QueryGateway,
    rewriteRequestCondition: RewriteRequestCondition,
    queryAdmission: WebFluxQueryAdmission,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val httpFactories: List<HttpRouteHandlerFunctionFactory> = listOf(
        LoadSnapshotHandlerFunctionFactory(
            queryGateway = queryGateway,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        ListQuerySnapshotHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        ListQuerySnapshotStateHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        PagedQuerySnapshotHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        PagedQuerySnapshotStateHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        SingleSnapshotHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        SingleSnapshotStateHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        CountSnapshotHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        LoadEventStreamHandlerFunctionFactory(
            queryGateway = queryGateway,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        ListQueryEventStreamHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        PagedQueryEventStreamHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
        CountEventStreamHandlerFunctionFactory(
            queryGateway = queryGateway,
            rewriteRequestCondition = rewriteRequestCondition,
            queryAdmission = queryAdmission,
            exceptionHandler = exceptionHandler
        ),
    )
}
