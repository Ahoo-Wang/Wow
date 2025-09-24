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

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.serialization.JsonSerializer
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.server.RouterFunctions
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono

class CommandBodyExtractor<C : Any>(private val commandRouteMetadata: CommandRouteMetadata<C>) :
    BodyExtractor<Mono<C>, ReactiveHttpInputMessage> {
    override fun extract(inputMessage: ReactiveHttpInputMessage, context: BodyExtractor.Context): Mono<C> {
        @Suppress("UNCHECKED_CAST")
        val pathVariables = context.hints()[RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE] as Map<String, String>

        return BodyExtractors.toMono(ObjectNode::class.java)
            .extract(inputMessage, context)
            .switchEmptyObjectNodeIfEmpty()
            .map { objectNode ->
                commandRouteMetadata.decode(objectNode, {
                    pathVariables[it]
                }) {
                    inputMessage.headers.getFirst(it)
                }
            }
    }
}

internal fun Mono<ObjectNode>.switchEmptyObjectNodeIfEmpty(): Mono<ObjectNode> {
    return switchIfEmpty {
        ObjectNode(JsonSerializer.nodeFactory).toMono()
    }
}
