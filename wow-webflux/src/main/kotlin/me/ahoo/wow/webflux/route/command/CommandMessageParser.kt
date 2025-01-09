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
import me.ahoo.wow.command.factory.CommandBuilder
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.messaging.withLocalFirst
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.command.CommandRequestHeaders
import org.springframework.web.reactive.function.server.ServerRequest
import reactor.core.publisher.Mono

interface CommandMessageParser {
    fun parse(
        aggregateMetadata: AggregateMetadata<*, *>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandMessage<Any>>
}

class DefaultCommandMessageParser(private val commandMessageFactory: CommandMessageFactory) : CommandMessageParser {
    override fun parse(
        aggregateMetadata: AggregateMetadata<*, *>,
        commandBody: Any,
        request: ServerRequest
    ): Mono<CommandMessage<Any>> {
        val aggregateId = request.getAggregateId()
        val tenantId = request.getTenantId(aggregateMetadata)
        val aggregateVersion = request.headers().firstHeader(CommandRequestHeaders.AGGREGATE_VERSION)?.toIntOrNull()
        val requestId = request.headers().firstHeader(CommandRequestHeaders.REQUEST_ID).ifNotBlank { it }
        val commandBuilder = commandBody.commandBuilder()
            .aggregateId(aggregateId)
            .tenantId(tenantId)
            .aggregateVersion(aggregateVersion)
            .requestId(requestId)
            .namedAggregate(aggregateMetadata.namedAggregate)
        injectExtensionHeaders(commandBuilder, request)
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

    private fun injectExtensionHeaders(commandBuilder: CommandBuilder, request: ServerRequest) {
        val extendedHeaders = request.headers().asHttpHeaders()
            .filter { (key, _) -> key.startsWith(CommandRequestHeaders.COMMAND_HEADER_X_PREFIX) }
            .map { (key, value) ->
                key.substring(CommandRequestHeaders.COMMAND_HEADER_X_PREFIX.length) to value.firstOrNull<String>().orEmpty()
            }.toMap<String, String>()
        if (extendedHeaders.isEmpty()) {
            return
        }
        commandBuilder.header { header ->
            header.with(extendedHeaders)
        }
    }
}
