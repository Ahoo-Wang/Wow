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

import me.ahoo.wow.query.event.filter.EventStreamQueryHandler
import me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.event.CountEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.ListQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.LoadEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.event.PagedQueryEventStreamHandlerFunctionFactory
import me.ahoo.wow.webflux.route.query.RewriteRequestCondition
import me.ahoo.wow.webflux.route.snapshot.CountSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.ListQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.LoadSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.PagedQuerySnapshotStateHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotHandlerFunctionFactory
import me.ahoo.wow.webflux.route.snapshot.SingleSnapshotStateHandlerFunctionFactory

class QueryRouteModule(
    snapshotQueryHandler: SnapshotQueryHandler,
    eventStreamQueryHandler: EventStreamQueryHandler,
    rewriteRequestCondition: RewriteRequestCondition,
    exceptionHandler: RequestExceptionHandler
) : WebFluxRouteModule {
    override val factories = listOf(
        LoadSnapshotHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            exceptionHandler = exceptionHandler
        ),
        ListQuerySnapshotHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        ListQuerySnapshotStateHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        PagedQuerySnapshotHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        PagedQuerySnapshotStateHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        SingleSnapshotHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        SingleSnapshotStateHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        CountSnapshotHandlerFunctionFactory(
            snapshotQueryHandler = snapshotQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        LoadEventStreamHandlerFunctionFactory(
            eventStreamQueryHandler = eventStreamQueryHandler,
            exceptionHandler = exceptionHandler
        ),
        ListQueryEventStreamHandlerFunctionFactory(
            eventStreamQueryHandler = eventStreamQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        PagedQueryEventStreamHandlerFunctionFactory(
            eventStreamQueryHandler = eventStreamQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
        CountEventStreamHandlerFunctionFactory(
            eventStreamQueryHandler = eventStreamQueryHandler,
            rewriteRequestCondition = rewriteRequestCondition,
            exceptionHandler = exceptionHandler
        ),
    )
}
