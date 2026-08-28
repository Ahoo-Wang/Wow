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

package me.ahoo.wow.webflux.route.event

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.requiredQueryModelSchemaProvider
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.query.QuerySchemaHandlerFunction
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

class EventStreamSchemaHandlerFunctionFactory(
    private val eventStreamQueryServiceFactory: EventStreamQueryServiceFactory,
    private val exceptionHandler: RequestExceptionHandler,
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.SCHEMA) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate,
    ): HandlerFunction<ServerResponse> = eventStreamSchemaHandler(
        aggregateMetadata = aggregateMetadata(metadata),
        eventStreamQueryServiceFactory = eventStreamQueryServiceFactory,
        exceptionHandler = exceptionHandler,
        refresh = false,
    )
}

class EventStreamSchemaRefreshHandlerFunctionFactory(
    private val eventStreamQueryServiceFactory: EventStreamQueryServiceFactory,
    private val exceptionHandler: RequestExceptionHandler,
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Event.SCHEMA_REFRESH) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate,
    ): HandlerFunction<ServerResponse> = eventStreamSchemaHandler(
        aggregateMetadata = aggregateMetadata(metadata),
        eventStreamQueryServiceFactory = eventStreamQueryServiceFactory,
        exceptionHandler = exceptionHandler,
        refresh = true,
    )
}

private fun eventStreamSchemaHandler(
    aggregateMetadata: AggregateMetadata<*, *>,
    eventStreamQueryServiceFactory: EventStreamQueryServiceFactory,
    exceptionHandler: RequestExceptionHandler,
    refresh: Boolean,
): HandlerFunction<ServerResponse> = QuerySchemaHandlerFunction(
    provider = {
        eventStreamQueryServiceFactory.create(aggregateMetadata)
            .requiredQueryModelSchemaProvider()
    },
    exceptionHandler = exceptionHandler,
    refresh = refresh,
)
