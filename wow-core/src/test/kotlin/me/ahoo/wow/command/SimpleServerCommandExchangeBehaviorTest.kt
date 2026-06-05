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
import me.ahoo.wow.event.DomainEventException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import org.junit.jupiter.api.Test

class SimpleServerCommandExchangeBehaviorTest {

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
