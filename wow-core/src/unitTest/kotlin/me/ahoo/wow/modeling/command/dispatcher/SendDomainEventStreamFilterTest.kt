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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.MockNamedEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.id.generateGlobalId
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class SendDomainEventStreamFilterTest {

    @Test
    fun `should propagate domain event bus send failure`() {
        val error = IllegalStateException("domain event bus failed")
        val eventStream = MockNamedEvent().toDomainEvent(
            aggregateId = generateGlobalId(),
            tenantId = generateGlobalId(),
            commandId = generateGlobalId(),
            version = 1,
        ).let {
            SimpleDomainEventStream(requestId = generateGlobalId(), body = listOf(it))
        }
        val domainEventBus = mockk<DomainEventBus> {
            every { send(eventStream) } returns Mono.error(error)
        }
        val filter = SendDomainEventStreamFilter(domainEventBus)
        val exchange = mockk<ServerCommandExchange<*>> {
            every { getEventStream() } returns eventStream
        }
        val next = mockk<FilterChain<ServerCommandExchange<*>>> {
            every { filter(any()) } returns Mono.empty()
        }

        filter.filter(exchange, next)
            .test()
            .expectErrorMatches { it === error }
            .verify()

        verify {
            next wasNot Called
        }
    }
}
