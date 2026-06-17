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

import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.requireAggregateHandlerMetadata
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

class LoadAggregateHandlerFunction(
    aggregateRouteMetadata: AggregateRouteMetadata<*>,
    stateAggregateRepository: StateAggregateRepository,
    exceptionHandler: RequestExceptionHandler
) : AbstractLoadAggregateHandlerFunction(aggregateRouteMetadata, stateAggregateRepository, exceptionHandler) {
    override fun getVersion(request: ServerRequest): Int {
        return Int.MAX_VALUE
    }

    override fun checkVersion(targetVersion: Int, stateAggregate: StateAggregate<*>) = Unit
}

class LoadAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : HttpRouteHandlerFunctionFactory {
    override val handlerKey: String = BuiltInHttpRouteHandlerKeys.State.LOAD_AGGREGATE

    override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(metadata.requireAggregateHandlerMetadata(handlerKey).aggregateRouteMetadata)
    }

    private fun create(aggregateRouteMetadata: AggregateRouteMetadata<*>): HandlerFunction<ServerResponse> {
        return LoadAggregateHandlerFunction(aggregateRouteMetadata, stateAggregateRepository, exceptionHandler)
    }
}
