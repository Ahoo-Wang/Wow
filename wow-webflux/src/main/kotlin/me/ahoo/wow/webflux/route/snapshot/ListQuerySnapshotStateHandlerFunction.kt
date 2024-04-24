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

import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.snapshot.ListQuerySnapshotStateRouteSpec
import me.ahoo.wow.query.filter.SnapshotQueryHandler
import me.ahoo.wow.query.toState
import me.ahoo.wow.query.toStateDocument
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.command.CommandParser.getTenantId
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class ListQuerySnapshotStateHandlerFunction(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val snapshotQueryHandler: SnapshotQueryHandler,
    private val exceptionHandler: ExceptionHandler
) : HandlerFunction<ServerResponse> {

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantId(aggregateMetadata)
        return request.bodyToMono(ListQuery::class.java)
            .flatMap {
                val query = if (tenantId == null) it else it.copy(condition = it.condition.withTenantId(tenantId))

                if (query.projection.isEmpty()) {
                    snapshotQueryHandler.list<Any>(aggregateMetadata, query).toState().collectList()
                } else {
                    snapshotQueryHandler.dynamicList(aggregateMetadata, query).toStateDocument().collectList()
                }
            }.toServerResponse(exceptionHandler)
    }
}

class ListQuerySnapshotStateHandlerFunctionFactory(
    private val snapshotQueryHandler: SnapshotQueryHandler,
    private val exceptionHandler: ExceptionHandler
) : RouteHandlerFunctionFactory<ListQuerySnapshotStateRouteSpec> {
    override val supportedSpec: Class<ListQuerySnapshotStateRouteSpec>
        get() = ListQuerySnapshotStateRouteSpec::class.java

    override fun create(spec: ListQuerySnapshotStateRouteSpec): HandlerFunction<ServerResponse> {
        return ListQuerySnapshotStateHandlerFunction(
            spec.aggregateMetadata,
            snapshotQueryHandler,
            exceptionHandler
        )
    }
}
