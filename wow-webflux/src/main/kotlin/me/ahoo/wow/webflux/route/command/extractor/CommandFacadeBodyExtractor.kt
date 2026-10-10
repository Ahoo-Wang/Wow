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

import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.configuration.aggregateType
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.serialization.toObject
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.BodyExtractors
import reactor.core.publisher.Mono
import reactor.util.function.Tuple2
import reactor.util.function.Tuples
import tools.jackson.databind.node.ObjectNode

/**
 * Reads the global command facade's request: the `Command-Type` header names the command, the optional
 * `Command-Aggregate-Context` / `Command-Aggregate-Name` headers name its aggregate, and the body is the command.
 *
 * The facade accepts exactly the commands that have an aggregate command route: a command type registered on an
 * aggregate known to this service's metadata (or one of the default delete / recover / apply-resource-tags commands
 * the aggregate does not override), whose aggregate route and `@CommandRoute` are enabled. The type name is matched
 * against those registered classes; a client string is never loaded with `Class.forName`. Any other type answers
 * like a disabled route: [NotFoundResourceException] (`NotFound`, 404).
 */
object CommandFacadeBodyExtractor :
    BodyExtractor<Mono<Tuple2<Any, AggregateRouteMetadata<Any>>>, ReactiveHttpInputMessage> {
    override fun extract(
        inputMessage: ReactiveHttpInputMessage,
        context: BodyExtractor.Context
    ): Mono<Tuple2<Any, AggregateRouteMetadata<Any>>> {
        val commandTypeName = requireNotNull(inputMessage.headers.getFirst(CommandHeaders.COMMAND_TYPE)) {
            "${CommandHeaders.COMMAND_TYPE} can not be empty."
        }

        val commandAggregateContext = inputMessage.headers.getFirst(CommandHeaders.COMMAND_AGGREGATE_CONTEXT)
        val commandAggregateName = inputMessage.headers.getFirst(CommandHeaders.COMMAND_AGGREGATE_NAME)
        val namedAggregate = if (!commandAggregateContext.isNullOrBlank()) {
            requireNotNull(commandAggregateName) {
                "${CommandHeaders.COMMAND_AGGREGATE_NAME} can not be empty."
            }
            MaterializedNamedAggregate(commandAggregateContext, commandAggregateName)
        } else {
            MetadataSearcher.scopeNamedAggregate.search(commandTypeName)
        }
        val aggregateRouteMetadata = namedAggregate?.aggregateType<Any>()?.aggregateRouteMetadata()
        val commandType = aggregateRouteMetadata?.routableCommandType(commandTypeName)
        if (aggregateRouteMetadata == null || commandType == null) {
            return Mono.error(
                NotFoundResourceException(
                    "No command route for ${CommandHeaders.COMMAND_TYPE} [$commandTypeName]."
                )
            )
        }
        return BodyExtractors.toMono(ObjectNode::class.java)
            .extract(inputMessage, context)
            .switchEmptyObjectNodeIfEmpty()
            .map {
                val commandBody = it.toObject(commandType)
                Tuples.of(commandBody, aggregateRouteMetadata)
            }
    }
}

/**
 * The registered command class named [commandTypeName] when it has an enabled command route on this aggregate, as
 * the command route contributor publishes them.
 */
@Suppress("UNCHECKED_CAST")
internal fun AggregateRouteMetadata<*>.routableCommandType(commandTypeName: String): Class<Any>? {
    if (!enabled) {
        return null
    }
    val command = aggregateMetadata.command
    val routableTypes = buildList {
        addAll(command.registeredCommands)
        if (!command.registeredDeleteAggregate) {
            add(DefaultDeleteAggregate::class.java)
        }
        if (!command.registeredRecoverAggregate) {
            add(DefaultRecoverAggregate::class.java)
        }
        if (!command.registeredApplyResourceTags) {
            add(DefaultApplyResourceTags::class.java)
        }
    }
    return routableTypes.firstOrNull { it.name == commandTypeName }
        ?.takeIf { it.commandRouteMetadata().enabled } as Class<Any>?
}
