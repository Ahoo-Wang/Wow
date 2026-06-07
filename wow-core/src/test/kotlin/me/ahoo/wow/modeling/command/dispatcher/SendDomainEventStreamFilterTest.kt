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

package me.ahoo.wow.modeling.command.dispatcher

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.FilterChain
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class SendDomainEventStreamFilterTest {

    @Test
    fun `filter sends event stream before continuing chain`() {
        val eventStream = mockk<DomainEventStream> {
            every { id } returns "event-stream-1"
        }
        val sent = mutableListOf<String>()
        val domainEventBus = mockk<DomainEventBus> {
            every { send(eventStream) } returns Mono.fromRunnable { sent += "send" }
        }
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns eventStream
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(exchange) } returns Mono.fromRunnable { sent += "next" }
        }

        StepVerifier.create(SendDomainEventStreamFilter(domainEventBus).filter(exchange, next))
            .verifyComplete()

        sent.assert().isEqualTo(listOf("send", "next"))
        verify(exactly = 1) { domainEventBus.send(eventStream) }
        verify(exactly = 1) { next.filter(exchange) }
    }

    @Test
    fun `filter continues chain without sending when no event stream exists`() {
        val domainEventBus = mockk<DomainEventBus>(relaxed = true)
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns null
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(exchange) } returns Mono.empty()
        }

        StepVerifier.create(SendDomainEventStreamFilter(domainEventBus).filter(exchange, next))
            .verifyComplete()

        verify { domainEventBus wasNot Called }
        verify { next.filter(exchange) }
    }

    @Test
    fun `filter propagates event bus send failure and does not continue chain`() {
        val error = IllegalStateException("domain event bus failed")
        val eventStream = mockk<DomainEventStream> {
            every { id } returns "event-stream-1"
        }
        val domainEventBus = mockk<DomainEventBus> {
            every { send(eventStream) } returns Mono.error(error)
        }
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns eventStream
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(any()) } returns Mono.empty()
        }

        StepVerifier.create(SendDomainEventStreamFilter(domainEventBus).filter(exchange, next))
            .expectErrorMatches { it === error }
            .verify()

        verify { next wasNot Called }
    }
}
