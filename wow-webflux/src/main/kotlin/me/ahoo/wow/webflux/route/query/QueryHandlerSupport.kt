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

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.withQueryEntry
import me.ahoo.wow.query.withQueryScope
import me.ahoo.wow.query.withQuerySelection
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.toServerResponse
import me.ahoo.wow.webflux.route.writeRawRequest
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * The request pipeline shared by every body-driven query handler:
 * decode the body, resolve the request scope, call the gateway with the query scope, the HTTP entry and the raw
 * request in the Reactor context (the gateway checks the HTTP budget at admission), bound the response and render it.
 */
internal class QueryHandlerSupport(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val queryRequestScope: QueryRequestScope,
    private val exceptionHandler: RequestExceptionHandler,
    private val guard: HttpQueryGuard,
) {
    fun <Q : Any, R : Any> mono(
        request: ServerRequest,
        extractor: QueryBodyExtractor<Q>,
        notFoundIfEmpty: Boolean = false,
        execute: (Q) -> Mono<R>,
    ): Mono<ServerResponse> = request.body(extractor)
        .flatMap { query ->
            val scope = queryRequestScope.resolve(aggregateMetadata, request)
            val result = guard.mono { execute(query) }.withQueryContext(scope, request)
            if (notFoundIfEmpty) result.throwNotFoundIfEmpty() else result
        }.toServerResponse(request, exceptionHandler)

    fun <Q : Any, R : Any> flux(
        request: ServerRequest,
        extractor: QueryBodyExtractor<Q>,
        prepare: (Q) -> Q = { it },
        execute: (Q) -> Flux<R>,
    ): Mono<ServerResponse> = request.body(extractor)
        .flatMapMany { body ->
            val query = prepare(body)
            val scope = queryRequestScope.resolve(aggregateMetadata, request)
            guard.flux(request) { execute(query) }.withQueryContext(scope, request)
        }.toServerResponse(request, exceptionHandler)
}

/**
 * Exposes the resolved query [scope], the route [selection] (the aggregate id a load route names; an operation
 * constraint, not scope), the [QueryEntry.HTTP] entry and the raw [request] to the gateway call through the Reactor
 * context. Every built-in query route runs its gateway call through here, so the HTTP entry is written in
 * one place. Applied to the inner gateway publisher, so the context covers exactly the gateway call and its guard.
 */
internal fun <T : Any> Mono<T>.withQueryContext(
    scope: QueryScope,
    request: ServerRequest,
    selection: FilterExpression = MatchAllFilter,
): Mono<T> = contextWrite {
    it.withQueryScope(scope).withQuerySelection(selection).withQueryEntry(QueryEntry.HTTP)
}.writeRawRequest(request)

/** The [Flux] counterpart of [Mono.withQueryContext]. */
internal fun <T : Any> Flux<T>.withQueryContext(
    scope: QueryScope,
    request: ServerRequest,
    selection: FilterExpression = MatchAllFilter,
): Flux<T> = contextWrite {
    it.withQueryScope(scope).withQuerySelection(selection).withQueryEntry(QueryEntry.HTTP)
}.writeRawRequest(request)

/**
 * Base factory for aggregate query routes: resolves the aggregate's gateway once per route and hands both to
 * [handlerFunction].
 */
abstract class QueryHandlerFunctionFactorySupport<G : QueryGateway<*>>(
    handlerKey: String,
    private val queryGateway: (AggregateMetadata<*, *>) -> G,
    private val handlerFunction: (AggregateMetadata<*, *>, G) -> HandlerFunction<ServerResponse>,
) : AggregateRouteHandlerFunctionFactorySupport(handlerKey) {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate,
    ): HandlerFunction<ServerResponse> {
        val aggregateMetadata = aggregateMetadata(metadata)
        return handlerFunction(aggregateMetadata, queryGateway(aggregateMetadata))
    }
}
