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

package me.ahoo.wow.messaging.function

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.ioc.register
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.messaging.handler.MessageExchange
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class MethodMessageFunctionBehaviorTest {

    @Test
    fun `simple message function invokes method with extracted body`() {
        val processor = InvocationProcessor()
        val function = InvocationProcessor::handleBody
            .toFunctionMetadata<InvocationProcessor, String>()
            .toMessageFunction<InvocationProcessor, InvocationExchange, String>(processor)

        val result = function(InvocationExchange(body = ParsedEventBody("body")))

        result.assert().isEqualTo("body:body")
        function.qualifiedName.assert().isEqualTo("InvocationProcessor.handleBody(ParsedEventBody)")
    }

    @Test
    fun `injectable message function resolves named and typed services`() {
        val processor = InvocationProcessor()
        val provider = SimpleServiceProvider()
        val namedService = InvocationService("named")
        val typedService = InvocationService("typed")
        provider.register(service = namedService, serviceName = "named-service")
        provider.register<InvocationService>(typedService)
        val exchange = InvocationExchange(body = ParsedEventBody("event"))
            .setServiceProvider(provider)

        val function = InvocationProcessor::handleWithServices
            .toFunctionMetadata<InvocationProcessor, String>()
            .toMessageFunction<InvocationProcessor, InvocationExchange, String>(processor)

        function.handle(exchange).assert().isEqualTo("event:named:typed")
    }

    @Test
    fun `exchange parameter is passed through as the first argument`() {
        val processor = InvocationProcessor()
        val exchange = InvocationExchange(body = ParsedEventBody("event"))
        val function = InvocationProcessor::handleExchange
            .toFunctionMetadata<InvocationProcessor, InvocationExchange>()
            .toMessageFunction<InvocationProcessor, InvocationExchange, InvocationExchange>(processor)

        function(exchange).assert().isSameAs(exchange)
    }
}

private data class InvocationService(
    val label: String
) : ParsedService

private class InvocationProcessor {
    @OnEvent
    fun handleBody(body: ParsedEventBody): String = "body:${body.value}"

    @OnEvent
    fun handleWithServices(
        body: ParsedEventBody,
        @Name("named-service") namedService: InvocationService,
        typedService: InvocationService
    ): String = "${body.value}:${namedService.label}:${typedService.label}"

    @OnEvent
    fun handleExchange(exchange: MessageExchange<InvocationExchange, TestNamedMessage>): InvocationExchange =
        exchange as InvocationExchange
}

private class InvocationExchange(
    body: ParsedEventBody
) : MessageExchange<InvocationExchange, TestNamedMessage> {
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
    override val message: TestNamedMessage = TestNamedMessage(body = body)
}
