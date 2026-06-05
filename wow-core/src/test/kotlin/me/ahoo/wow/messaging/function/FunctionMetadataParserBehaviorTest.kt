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

@file:Suppress("unused", "UNUSED_PARAMETER")

package me.ahoo.wow.messaging.function

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test

class FunctionMetadataParserBehaviorTest {

    @Test
    fun `parses body parameter event function metadata`() {
        val metadata = MetadataProcessor::onEvent.toFunctionMetadata<MetadataProcessor, Unit>()

        metadata.functionKind.assert().isEqualTo(FunctionKind.EVENT)
        metadata.supportedType.assert().isEqualTo(ParsedEventBody::class.java)
        metadata.firstParameterKind.assert().isEqualTo(FirstParameterKind.MESSAGE_BODY)
        metadata.supportedTopics.assert().containsExactly(
            MaterializedNamedAggregate("wow-core-test", "messaging_aggregate"),
        )
        metadata.contextName.assert().isEqualTo("wow.messaging")
        metadata.processorName.assert().isEqualTo("MetadataProcessor")
        metadata.name.assert().isEqualTo("onEvent")
        metadata.injectParameters.assert().isEmpty()
    }

    @Test
    fun `parses message and exchange first parameter kinds`() {
        val wrapped = MetadataProcessor::wrappedEvent.toFunctionMetadata<MetadataProcessor, Unit>()
        val exchange = MetadataProcessor::exchangeEvent.toFunctionMetadata<MetadataProcessor, Unit>()

        wrapped.firstParameterKind.assert().isEqualTo(FirstParameterKind.MESSAGE)
        wrapped.supportedType.assert().isEqualTo(ParsedEventBody::class.java)
        exchange.firstParameterKind.assert().isEqualTo(FirstParameterKind.MESSAGE_EXCHANGE)
        exchange.supportedType.assert().isEqualTo(ParsedExchange::class.java)
    }

    @Test
    fun `parses declared event topics and injectable parameters`() {
        val metadata = MetadataProcessor::projected.toFunctionMetadata<MetadataProcessor, Unit>()

        metadata.functionKind.assert().isEqualTo(FunctionKind.STATE_EVENT)
        metadata.supportedTopics.assert().containsExactly(
            MaterializedNamedAggregate("wow.messaging", "aggregate-one"),
            MaterializedNamedAggregate("wow.messaging", "aggregate-two"),
        )
        metadata.injectParameterLength.assert().isEqualTo(2)
        metadata.injectParameters[0].name.assert().isEqualTo("named-service")
        metadata.injectParameters[1].name.assert().isEmpty()
    }

    @Test
    fun `unannotated non default function names are rejected`() {
        assertThrownBy<IllegalStateException> {
            MetadataProcessor::plain.toFunctionMetadata<MetadataProcessor, Unit>()
        }
    }
}

internal data class ParsedEventBody(
    val value: String = "event"
)

internal interface ParsedService

internal class MetadataProcessor {
    fun onEvent(body: ParsedEventBody) = Unit

    @OnEvent
    fun wrappedEvent(message: CommandMessage<ParsedEventBody>) = Unit

    @OnEvent
    fun exchangeEvent(exchange: MessageExchange<ParsedExchange, TestNamedMessage>) = Unit

    @OnStateEvent("aggregate-one", "aggregate-two")
    fun projected(
        body: ParsedEventBody,
        @Name("named-service") namedService: ParsedService,
        typedService: ParsedService
    ) = Unit

    fun plain(body: ParsedEventBody) = Unit
}

internal class ParsedExchange(
    override val message: TestNamedMessage
) : MessageExchange<ParsedExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = mutableMapOf()
}
