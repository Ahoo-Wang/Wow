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

package me.ahoo.wow.webflux.route.state

import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.route.AggregateRouteMetadata
import me.ahoo.wow.query.mask.tryMask
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.exception.toServerResponse
import me.ahoo.wow.webflux.route.command.getAggregateId
import me.ahoo.wow.webflux.route.command.getTenantIdOrDefault
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

abstract class AbstractLoadAggregateHandlerFunction(
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : HandlerFunction<ServerResponse> {
    protected val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
    abstract fun getVersion(request: ServerRequest): Int
    abstract fun checkVersion(targetVersion: Int, stateAggregate: StateAggregate<*>)

    override fun handle(request: ServerRequest): Mono<ServerResponse> {
        val tenantId = request.getTenantIdOrDefault(aggregateMetadata)
        val id = requireNotNull(request.getAggregateId(aggregateRouteMetadata.owner))
        val aggregateId = aggregateMetadata.aggregateId(id = id, tenantId = tenantId)
        val version = getVersion(request)
        return stateAggregateRepository
            .load(aggregateId, aggregateMetadata.state, version)
            .filter {
                it.initialized && !it.deleted
            }
            .map {
                checkVersion(version, it)
                it.state.tryMask()
                OwnerAggregatePrecondition(request, aggregateRouteMetadata.owner).check(it)
            }
            .throwNotFoundIfEmpty()
            .toServerResponse(request, exceptionHandler)
    }
}
