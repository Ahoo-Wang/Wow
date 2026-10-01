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

package me.ahoo.wow.webflux.route.query

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/**
 * `POST …/snapshot/schema/refresh` and `POST …/event/schema/refresh`, the 9.1 routes that reloaded a model's schema.
 * 9.2 revalidates schemas through the `wowQuerySchema` actuator endpoint (and periodically); these routes stay so
 * operators' scripts do not answer 404: each [revalidate]s the route's model of the aggregate (completing once it is
 * reloaded; concurrent calls share the reload in flight), then answers as `GET …/schema`. The handler key is `Snapshot.SCHEMA_REFRESH` or `Event.SCHEMA_REFRESH`.
 */
@Deprecated("Scheduled for removal in 10.0.0. Use the wowQuerySchema actuator endpoint.")
class QuerySchemaRefreshHandlerFunctionFactory(
    handlerKey: String,
    private val queryGateway: (AggregateMetadata<*, *>) -> QueryGateway<*>,
    private val revalidate: (AggregateMetadata<*, *>) -> Mono<Void>,
    private val exceptionHandler: RequestExceptionHandler,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate,
    ): HandlerFunction<ServerResponse> {
        val aggregateMetadata = aggregateMetadata(metadata)
        val describe = QuerySchemaHandlerFunction(queryGateway(aggregateMetadata), exceptionHandler, guard)
        return HandlerFunction { request: ServerRequest ->
            Mono.defer { revalidate(aggregateMetadata) }
                .then(Mono.defer { describe.handle(request) })
                .onErrorResume { exceptionHandler.handle(request, it) }
        }
    }
}
