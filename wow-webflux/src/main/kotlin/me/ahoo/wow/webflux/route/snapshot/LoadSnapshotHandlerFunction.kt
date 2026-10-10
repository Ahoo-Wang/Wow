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

import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.AggregateRouteHandlerFunctionFactorySupport
import me.ahoo.wow.webflux.route.identity.identity
import me.ahoo.wow.webflux.route.query.HttpQueryGuard
import me.ahoo.wow.webflux.route.query.QueryRequestScope
import me.ahoo.wow.webflux.route.query.withQueryContext
import me.ahoo.wow.webflux.route.toServerResponse
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

class LoadSnapshotHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val snapshotQueryGateway: SnapshotQueryGateway<Any>,
    private val queryRequestScope: QueryRequestScope,
    private val exceptionHandler: RequestExceptionHandler,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : HandlerFunction<ServerResponse> {
    private val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
    override fun handle(request: ServerRequest): Mono<ServerResponse> =
        // Deferred: an identity error (a blank path variable, a V3 conflict) is a signal the error mapping sees.
        Mono.defer {
            val identity = request.identity(aggregateRouteMetadata)
            val tenantId = identity.tenantId() ?: TenantId.DEFAULT_TENANT_ID
            val id = requireNotNull(identity.aggregateId())
            val ownerId = identity.readOwnerId()
            val selection = filter {
                tenantId(tenantId)
                id(id)
                if (!ownerId.isNullOrBlank()) {
                    ownerId(ownerId)
                }
            }
            val singleQuery = SingleQuery(MatchAllFilter)
            guard.of(snapshotQueryGateway).mono {
                snapshotQueryGateway.dynamicSingle(singleQuery)
            }
                .withQueryContext(queryRequestScope.resolve(aggregateMetadata, request), request, selection)
                .throwNotFoundIfEmpty()
        }.toServerResponse(request, exceptionHandler)
}

class LoadSnapshotHandlerFunctionFactory(
    private val snapshotQueryGateway: (AggregateMetadata<*, *>) -> SnapshotQueryGateway<Any>,
    private val queryRequestScope: QueryRequestScope,
    private val exceptionHandler: RequestExceptionHandler,
    private val guard: HttpQueryGuard = HttpQueryGuard(),
) : AggregateRouteHandlerFunctionFactorySupport(BuiltInHttpRouteHandlerKeys.Snapshot.LOAD) {
    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse> {
        val aggregateRouteMetadata = aggregateRouteMetadata(metadata)
        return LoadSnapshotHandlerFunction(
            aggregateRouteMetadata,
            snapshotQueryGateway(aggregateRouteMetadata.aggregateMetadata),
            queryRequestScope,
            exceptionHandler,
            guard,
        )
    }
}
