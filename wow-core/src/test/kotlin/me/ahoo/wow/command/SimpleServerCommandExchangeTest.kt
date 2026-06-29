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

package me.ahoo.wow.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.exception.DefaultErrorInfo
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.event.DomainEventException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.handler.COMMAND_RESULT_KEY
import me.ahoo.wow.messaging.handler.FUNCTION_KEY
import me.ahoo.wow.messaging.handler.SERVICE_PROVIDER_KEY
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.command.COMMAND_AGGREGATE_KEY
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.getCommandAggregate
import me.ahoo.wow.modeling.command.setCommandAggregate
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import org.junit.jupiter.api.Test

class SimpleServerCommandExchangeTest {

    @Test
    fun `should store command processing attributes`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val exchange = SimpleServerCommandExchange(message)
        val aggregateMetadata = mockk<AggregateMetadata<*, *>>()
        val aggregateProcessor = mockk<AggregateProcessor<*>>()
        val eventStream = mockk<DomainEventStream>()

        exchange.setAggregateMetadata(aggregateMetadata).assert().isSameAs(exchange)
        exchange.setAggregateProcessor(aggregateProcessor).assert().isSameAs(exchange)
        exchange.setCommandInvokeResult("handled").assert().isSameAs(exchange)
        exchange.setEventStream(eventStream).assert().isSameAs(exchange)
        exchange.setAggregateVersion(7).assert().isSameAs(exchange)

        exchange.getAggregateMetadata().assert().isSameAs(aggregateMetadata)
        exchange.getAggregateProcessor().assert().isSameAs(aggregateProcessor)
        exchange.getCommandInvokeResult<String>().assert().isEqualTo("handled")
        exchange.getEventStream().assert().isSameAs(eventStream)
        exchange.getAggregateVersion().assert().isEqualTo(7)
    }

    @Test
    fun `should store message exchange attributes in dedicated fields`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val exchange = SimpleServerCommandExchange(message)
        val commandAggregate = mockk<CommandAggregate<Any, Any>>()
        val function = mockk<FunctionInfo>()
        val serviceProvider = mockk<ServiceProvider>()
        val error = IllegalStateException("failed")

        exchange.setCommandAggregate(commandAggregate).assert().isSameAs(exchange)
        exchange.setFunction(function).assert().isSameAs(exchange)
        exchange.setServiceProvider(serviceProvider).assert().isSameAs(exchange)
        exchange.setError(error)
        exchange.setCommandResult("answer", 42)

        exchange.getCommandAggregate<Any, Any>().assert().isSameAs(commandAggregate)
        exchange.getFunction().assert().isSameAs(function)
        exchange.getServiceProvider().assert().isSameAs(serviceProvider)
        exchange.getError().assert().isSameAs(error)
        exchange.getCommandResult<Int>("answer").assert().isEqualTo(42)
        exchange.attributes.assert().isEmpty()
    }

    @Test
    fun `should store and remove unknown attribute keys in attributes map`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val exchange = SimpleServerCommandExchange(message)

        exchange.setAttribute("custom-key", "custom-value")
        exchange.getAttribute<String>("custom-key").assert().isEqualTo("custom-value")
        exchange.attributes["custom-key"].assert().isEqualTo("custom-value")

        exchange.removeAttribute("custom-key")
        exchange.getAttribute<String>("custom-key").assert().isNull()
        exchange.attributes.containsKey("custom-key").assert().isFalse()
    }

    @Test
    fun `should remove well-known attribute keys`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val exchange = SimpleServerCommandExchange(message)
        val error = IllegalArgumentException("failed")
        val aggregateMetadata = mockk<AggregateMetadata<*, *>>()
        val aggregateProcessor = mockk<AggregateProcessor<*>>()
        val eventStream = mockk<DomainEventStream>()
        every { eventStream.iterator() } returns emptyList<DomainEvent<*>>().iterator()
        val commandAggregate = mockk<CommandAggregate<Any, Any>>()
        val function = mockk<FunctionInfo>()
        val serviceProvider = mockk<ServiceProvider>()

        exchange.setAggregateMetadata(aggregateMetadata)
        exchange.setAggregateProcessor(aggregateProcessor)
        exchange.setCommandInvokeResult("handled")
        exchange.setEventStream(eventStream)
        exchange.setError(error)
        exchange.setCommandAggregate(commandAggregate)
        exchange.setFunction(function)
        exchange.setServiceProvider(serviceProvider)
        exchange.setCommandResult("answer", 42)
        exchange.getError().assert().isSameAs(error)
        exchange.clearError()
        exchange.getError().assert().isNull()

        exchange.setAggregateVersion(7)
        exchange.getAggregateVersion().assert().isEqualTo(7)
        exchange.removeAttribute(AGGREGATE_VERSION_KEY)
        exchange.getAggregateVersion().assert().isNull()

        exchange.removeAttribute(AGGREGATE_METADATA_KEY)
        exchange.removeAttribute(AGGREGATE_PROCESSOR_KEY)
        exchange.removeAttribute(COMMAND_INVOKE_RESULT_KEY)
        exchange.removeAttribute(EVENT_STREAM_KEY)
        exchange.removeAttribute(COMMAND_AGGREGATE_KEY)
        exchange.removeAttribute(FUNCTION_KEY)
        exchange.removeAttribute(SERVICE_PROVIDER_KEY)
        exchange.removeAttribute(COMMAND_RESULT_KEY)
        exchange.getAggregateMetadata().assert().isNull()
        exchange.getAggregateProcessor().assert().isNull()
        exchange.getCommandInvokeResult<Any>().assert().isNull()
        exchange.getEventStream().assert().isNull()
        exchange.getCommandAggregate<Any, Any>().assert().isNull()
        exchange.getFunction().assert().isNull()
        exchange.getServiceProvider().assert().isNull()
        exchange.getCommandResult().assert().isEmpty()
    }

    @Test
    fun `should migrate well-known keys from caller-provided attributes map`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val eventStream = mockk<DomainEventStream>()
        val attributes = mutableMapOf<String, Any>(
            EVENT_STREAM_KEY to eventStream,
            "custom-key" to "custom-value",
        )
        val exchange = SimpleServerCommandExchange(message, attributes)

        exchange.getEventStream().assert().isSameAs(eventStream)
        attributes.containsKey(EVENT_STREAM_KEY).assert().isFalse()
        exchange.getAttribute<String>("custom-key").assert().isEqualTo("custom-value")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `should leave null caller-provided known key in attributes map`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val attributes = linkedMapOf<String, Any?>(
            EVENT_STREAM_KEY to null,
            "custom-key" to "custom-value",
        ) as MutableMap<String, Any>
        val exchange = SimpleServerCommandExchange(message, attributes)

        exchange.getEventStream().assert().isNull()
        attributes.containsKey(EVENT_STREAM_KEY).assert().isTrue()
        exchange.getAttribute<String>("custom-key").assert().isEqualTo("custom-value")
    }

    @Test
    fun `should extract declared message header aggregate id event stream and error`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val eventStream = mockk<DomainEventStream>()
        val error = IllegalArgumentException("failed")
        val exchange = SimpleServerCommandExchange(message)
            .setEventStream(eventStream)
        exchange.setError(error)

        exchange.extractDeclared(CommandMessage::class.java).assert().isSameAs(message)
        exchange.extractDeclared(message.header.javaClass).assert().isSameAs(message.header)
        exchange.extractDeclared(message.aggregateId.javaClass).assert().isSameAs(message.aggregateId)
        exchange.extractDeclared(DomainEventStream::class.java).assert().isSameAs(eventStream)
        exchange.extractDeclared(RuntimeException::class.java).assert().isSameAs(error)
    }

    @Test
    fun `should surface failed event stream as domain event exception`() {
        val message = AccountCommand(id = "account-1").toCommandMessage()
        val errorInfo = DefaultErrorInfo(errorCode = "EVENT_FAILED", errorMsg = "event failed")
        val errorEvent = mockk<DomainEvent<ErrorInfo>> {
            every { body } returns errorInfo
        }
        val eventStream = mockk<DomainEventStream> {
            every { iterator() } returns listOf(errorEvent).iterator()
        }

        val error = SimpleServerCommandExchange(message)
            .setEventStream(eventStream)
            .getError()

        error.assert().isInstanceOf(DomainEventException::class.java)
        (error as DomainEventException).errorCode.assert().isEqualTo("EVENT_FAILED")
        error.errorMsg.assert().isEqualTo("event failed")
    }
}
