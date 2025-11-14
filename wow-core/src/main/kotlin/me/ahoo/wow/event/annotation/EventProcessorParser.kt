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
package me.ahoo.wow.event.annotation

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.processor.MessageAnnotationFunctionCondition
import me.ahoo.wow.messaging.processor.ProcessorMetadata
import me.ahoo.wow.messaging.processor.ProcessorMetadataParser

/**
 * Parser for event processor metadata.
 *
 * This object parses classes annotated with event processing annotations
 * (@OnEvent, @OnStateEvent) to extract processor metadata for domain event handling.
 *
 * @see ProcessorMetadataParser
 * @see DomainEventExchange
 * @see OnEvent
 * @see OnStateEvent
 * @see MessageAnnotationFunctionCondition
 */
object EventProcessorParser : ProcessorMetadataParser<DomainEventExchange<*>>(
    MessageAnnotationFunctionCondition(OnEvent::class, OnStateEvent::class),
)

/**
 * Extension function to parse event processor metadata from a class.
 *
 * @param P The processor type
 * @receiver The class to parse processor metadata from
 * @return The parsed processor metadata for domain event handling
 *
 * @see EventProcessorParser.parse
 * @see ProcessorMetadata
 * @see DomainEventExchange
 */
fun <P : Any> Class<P>.eventProcessorMetadata(): ProcessorMetadata<P, DomainEventExchange<*>> = EventProcessorParser.parse(
    this
)

/**
 * Inline function to get event processor metadata for a reified type.
 *
 * @param P The processor type
 * @return The processor metadata for domain event handling
 *
 * @see eventProcessorMetadata
 * @see ProcessorMetadata
 * @see DomainEventExchange
 */
inline fun <reified P : Any> eventProcessorMetadata(): ProcessorMetadata<P, DomainEventExchange<*>> = P::class.java.eventProcessorMetadata()
