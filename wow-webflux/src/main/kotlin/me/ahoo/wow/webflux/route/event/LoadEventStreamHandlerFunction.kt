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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpec
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantIdOrDefault
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class LoadEventStreamHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val eventStore: EventStore
) :
    HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = request.pathVariable(RoutePaths.ID_KEY)
        val headVersion = request.pathVariable(RoutePaths.HEAD_VERSION_KEY).toInt()
        val tailVersion = request.pathVariable(RoutePaths.TAIL_VERSION_KEY).toInt()
        val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
        return eventStore
            .load(
                aggregateId = aggregateId,
                headVersion = headVersion,
                tailVersion = tailVersion
            ).collectList()
            .toServerResponse()
    }
}

class LoadEventStreamHandlerFunctionFactory(
    private val eventStore: EventStore
) : RouteHandlerFunctionFactory<LoadEventStreamRouteSpec> {
    override val supportedSpec: Class<LoadEventStreamRouteSpec>
        get() = LoadEventStreamRouteSpec::class.java

    override fun create(spec: LoadEventStreamRouteSpec): HandlerFunction<ServerResponse> {
        return LoadEventStreamHandlerFunction(spec.aggregateMetadata, eventStore)
    }
}
