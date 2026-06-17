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

package me.ahoo.wow.webflux.route

import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.ServerResponse

abstract class AggregateRouteHandlerFunctionFactorySupport(
    final override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(contract, metadata.requireAggregateHandlerMetadata(handlerKey))
    }

    protected abstract fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Aggregate
    ): HandlerFunction<ServerResponse>

    protected fun aggregateRouteMetadata(metadata: HttpRouteHandlerMetadata.Aggregate): AggregateRouteMetadata<*> {
        return metadata.aggregateRouteMetadata
    }

    protected fun aggregateMetadata(metadata: HttpRouteHandlerMetadata.Aggregate): AggregateMetadata<*, *> {
        return metadata.aggregateRouteMetadata.aggregateMetadata
    }
}

abstract class CommandRouteHandlerFunctionFactorySupport(
    final override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        return create(contract, metadata.requireCommandHandlerMetadata(handlerKey))
    }

    protected abstract fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata.Command
    ): HandlerFunction<ServerResponse>
}

abstract class NoMetadataRouteHandlerFunctionFactorySupport(
    final override val handlerKey: String
) : HttpRouteHandlerFunctionFactory {
    final override fun create(
        contract: HttpRouteContract,
        metadata: HttpRouteHandlerMetadata
    ): HandlerFunction<ServerResponse> {
        metadata.requireNoHandlerMetadata(handlerKey)
        return create(contract)
    }

    protected abstract fun create(contract: HttpRouteContract): HandlerFunction<ServerResponse>
}
