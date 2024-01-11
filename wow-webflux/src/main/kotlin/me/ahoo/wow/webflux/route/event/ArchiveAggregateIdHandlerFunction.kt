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

import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.event.ArchiveAggregateIdRouteSpec
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class ArchiveAggregateIdHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val eventStore: EventStore,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {
    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        return eventStore.archiveAggregateId(aggregateMetadata.namedAggregate)
            .then(ErrorInfo.OK.toMono())
            .toServerResponse(exceptionHandler)
    }
}

class ArchiveAggregateIdHandlerFunctionFactory(
    private val eventStore: EventStore,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<ArchiveAggregateIdRouteSpec> {
    override val supportedSpec: Class<ArchiveAggregateIdRouteSpec>
        get() = ArchiveAggregateIdRouteSpec::class.java

    override fun create(spec: ArchiveAggregateIdRouteSpec): HandlerFunction<ServerResponse> {
        return ArchiveAggregateIdHandlerFunction(spec.aggregateMetadata, eventStore, exceptionHandler)
    }
}
