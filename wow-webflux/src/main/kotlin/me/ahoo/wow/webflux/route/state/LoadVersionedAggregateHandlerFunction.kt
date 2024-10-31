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

import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.openapi.state.LoadVersionedAggregateRouteSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.RouteHandlerFunctionFactory
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

class LoadVersionedAggregateHandlerFunction(
    aggregateMetadata: AggregateMetadata<*, *>,
    stateAggregateRepository: StateAggregateRepository,
    exceptionHandler: RequestExceptionHandler
) : AbstractLoadAggregateHandlerFunction(aggregateMetadata, stateAggregateRepository, exceptionHandler) {
    override fun getVersion(request: ServerRequest): Int {
        return request.pathVariable(MessageRecords.VERSION).toInt()
    }

    override fun checkVersion(targetVersion: Int, stateAggregate: StateAggregate<*>) {
        check(targetVersion == stateAggregate.version) {
            "targetVersion:$targetVersion != stateAggregate.version:${stateAggregate.version}"
        }
    }
}

class LoadVersionedAggregateHandlerFunctionFactory(
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: RequestExceptionHandler
) : RouteHandlerFunctionFactory<LoadVersionedAggregateRouteSpec> {
    override val supportedSpec: Class<LoadVersionedAggregateRouteSpec>
        get() = LoadVersionedAggregateRouteSpec::class.java

    override fun create(spec: LoadVersionedAggregateRouteSpec): HandlerFunction<ServerResponse> {
        return LoadVersionedAggregateHandlerFunction(spec.aggregateMetadata, stateAggregateRepository, exceptionHandler)
    }
}
