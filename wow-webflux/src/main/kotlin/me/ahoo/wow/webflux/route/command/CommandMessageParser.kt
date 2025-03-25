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

package me.ahoo.wow.webflux.route.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.messaging.withLocalFirst
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.AGGREGATE_VERSION
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Header.REQUEST_ID
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.webflux.route.command.appender.CommandRequestHeaderAppender
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono

interface CommandMessageParser {
    fun parse(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandMessage<Any>>
}

class DefaultCommandMessageParser(
    private val commandMessageFactory: CommandMessageFactory,
    private val commandRequestHeaderAppends: List<CommandRequestHeaderAppender> = listOf()
) : CommandMessageParser {
    override fun parse(
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandMessage<Any>> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        val tenantId = request.getTenantId(aggregateMetadata)
        val ownerId = request.getOwnerId()
        val aggregateId = request.getAggregateId(aggregateRouteMetadata.owner, ownerId)
        val aggregateVersion = request.headers().firstHeader(AGGREGATE_VERSION)?.toIntOrNull()
        val requestId = request.headers().firstHeader(REQUEST_ID).ifNotBlank { it }
        val commandBuilder = commandBody.commandBuilder()
            .aggregateId(aggregateId)
            .tenantId(tenantId)
            .ownerId(ownerId)
            .aggregateVersion(aggregateVersion)
            .requestId(requestId)
            .namedAggregate(aggregateMetadata.namedAggregate)
        commandRequestHeaderAppends.forEach {
            it.append(request, commandBuilder.header)
        }
        request.getLocalFirst()?.let {
            commandBuilder.header { header ->
                header.withLocalFirst(it)
            }
        }
        return request.principal().map {
            commandBuilder.header { header ->
                header.withOperator(it.name)
            }
        }.then(
            commandMessageFactory.create<Any>(commandBuilder)
        )
    }
}
