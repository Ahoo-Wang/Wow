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

package me.ahoo.wow.webflux.route.snapshot

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.snapshot.SingleSnapshotStateRouteSpec
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.SnapshotQueryServiceFactory
import me.ahoo.wow.query.toState
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class SingleSnapshotStateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val snapshotQueryService: SnapshotQueryService<Any>,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        return request.bodyToMono(Condition::class.java)
            .flatMap {
                snapshotQueryService.single(tenantId, it).toState().throwNotFoundIfEmpty()
            }.toServerResponse(exceptionHandler)
    }
}

class SingleSnapshotStateHandlerFunctionFactory(
    private val snapshotQueryServiceFactory: SnapshotQueryServiceFactory,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<SingleSnapshotStateRouteSpec> {
    override val supportedSpec: Class<SingleSnapshotStateRouteSpec>
        get() = SingleSnapshotStateRouteSpec::class.java

    override fun create(spec: SingleSnapshotStateRouteSpec): HandlerFunction<ServerResponse> {
        return SingleSnapshotStateHandlerFunction(
            spec.aggregateMetadata,
            snapshotQueryServiceFactory.create(spec.aggregateMetadata),
            exceptionHandler
        )
    }
}
