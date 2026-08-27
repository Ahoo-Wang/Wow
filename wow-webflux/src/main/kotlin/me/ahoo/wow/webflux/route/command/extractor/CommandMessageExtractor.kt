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

package me.ahoo.wow.webflux.route.command.extractor

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono

interface CommandMessageExtractor {
    fun extract(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandMessage<Any>>
}

class DefaultCommandMessageExtractor(
    private val commandMessageFactory: CommandMessageFactory,
    private val commandBuilderExtractor: CommandBuilderExtractor,
    private val commandRequestHeaderAppends: List<CommandRequestHeaderAppender> = listOf()
) : CommandMessageExtractor {
    override fun extract(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandMessage<Any>> {
        return commandBuilderExtractor.extract(aggregateRouteMetadata, commandBody, request).flatMap { commandBuilder ->
            commandRequestHeaderAppends.forEach {
                it.append(request, commandBuilder.header)
            }
            commandMessageFactory.create(commandBuilder)
        }
    }
}
