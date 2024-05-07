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

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.infra.TypeNameMapper.toType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.command.CommandHeaders
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toObject
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.BodyExtractors
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.kotlin.core.publisher.toMono
import reactor.util.function.Tuple2
import reactor.util.function.Tuples

object CommandFacadeBodyExtractor :
    BodyExtractor<Mono<Tuple2<Any, AggregateMetadata<Any, Any>>>, ReactiveHttpInputMessage> {
    override fun extract(
        inputMessage: ReactiveHttpInputMessage,
        context: BodyExtractor.Context
    ): Mono<Tuple2<Any, AggregateMetadata<Any, Any>>> {
        val commandType = requireNotNull(inputMessage.headers.getFirst(CommandHeaders.COMMAND_TYPE)) {
            "${CommandHeaders.COMMAND_TYPE} can not be empty."
        }.toType<Any>()

        val commandAggregateContext = inputMessage.headers.getFirst(CommandHeaders.COMMAND_AGGREGATE_CONTEXT)
        val commandAggregateName = inputMessage.headers.getFirst(CommandHeaders.COMMAND_AGGREGATE_NAME)
        val namedAggregate = if (!commandAggregateContext.isNullOrBlank()) {
            requireNotNull(commandAggregateName) {
                "${CommandHeaders.COMMAND_AGGREGATE_NAME} can not be empty."
            }
            MaterializedNamedAggregate(commandAggregateContext, commandAggregateName)
        } else {
            commandType.requiredNamedAggregate()
        }
        val aggregateMetadata = namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, Any>()
        return BodyExtractors.toMono(ObjectNode::class.java)
            .extract(inputMessage, context)
            .switchIfEmpty {
                ObjectNode(JsonSerializer.nodeFactory, mutableMapOf()).toMono()
            }
            .map {
                val commandBody = it.toObject(commandType)
                Tuples.of(commandBody, aggregateMetadata)
            }
    }
}
