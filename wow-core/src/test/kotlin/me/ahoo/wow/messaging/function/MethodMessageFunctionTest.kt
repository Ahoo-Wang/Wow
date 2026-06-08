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
import me.ahoo.wow.infra.accessor.function.FunctionAccessor
import me.ahoo.wow.infra.accessor.function.SimpleFunctionAccessor
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.ioc.register
import me.ahoo.wow.messaging.TestNamedMessage
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.messaging.handler.MessageExchange
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions

class MethodMessageFunctionTest {

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
    fun `simple message function uses single argument accessor`() {
        val processor = InvocationProcessor()
        val delegate = SimpleFunctionAccessor<InvocationProcessor, String>(
            InvocationProcessor::class.declaredFunctions.first { it.name == "handleBody" }
        )
        val accessor = RecordingFunctionAccessor(delegate)
        val metadata = InvocationProcessor::handleBody.toFunctionMetadata<InvocationProcessor, String> { accessor }
        val function = metadata.toMessageFunction<InvocationProcessor, InvocationExchange, String>(processor)

        function(InvocationExchange(body = ParsedEventBody("body"))).assert().isEqualTo("body:body")

        accessor.invoke1Count.assert().isEqualTo(1)
        accessor.invokeArrayCount.assert().isEqualTo(0)
        accessor.lastSingleArgument.assert().isEqualTo(ParsedEventBody("body"))
    }

    @Test
    fun `injectable message function keeps array invocation`() {
        val processor = InvocationProcessor()
        val delegate = SimpleFunctionAccessor<InvocationProcessor, String>(
            InvocationProcessor::class.declaredFunctions.first { it.name == "handleWithServices" }
        )
        val accessor = RecordingFunctionAccessor(delegate)
        val provider = SimpleServiceProvider()
        val namedService = InvocationService("named")
        val typedService = InvocationService("typed")
        provider.register(service = namedService, serviceName = "named-service")
        provider.register<InvocationService>(typedService)
        val exchange = InvocationExchange(body = ParsedEventBody("event"))
            .setServiceProvider(provider)
        val metadata = InvocationProcessor::handleWithServices.toFunctionMetadata<InvocationProcessor, String> { accessor }
        val function = metadata.toMessageFunction<InvocationProcessor, InvocationExchange, String>(processor)

        function(exchange).assert().isEqualTo("event:named:typed")

        accessor.invoke1Count.assert().isEqualTo(0)
        accessor.invokeArrayCount.assert().isEqualTo(1)
        accessor.lastArraySize.assert().isEqualTo(3)
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

private class RecordingFunctionAccessor<P, R>(
    private val delegate: FunctionAccessor<P, R>
) : FunctionAccessor<P, R> {
    var invokeArrayCount: Int = 0
        private set
    var invoke1Count: Int = 0
        private set
    var lastArraySize: Int? = null
        private set
    var lastSingleArgument: Any? = null
        private set

    override val function: KFunction<*> = delegate.function

    override fun invoke(target: P, args: Array<Any?>): R {
        invokeArrayCount++
        lastArraySize = args.size
        return delegate.invoke(target, args)
    }

    override fun invoke1(target: P, arg: Any?): R {
        invoke1Count++
        lastSingleArgument = arg
        return delegate.invoke1(target, arg)
    }
}
