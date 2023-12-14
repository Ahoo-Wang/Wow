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

package me.ahoo.wow.command.wait

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class ProjectedNotifierFilterTest {

    @Test
    fun filter() {
        val projectedNotifierFilter = ProjectedNotifierFilter(LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar))
        val domainEvent = mockk<DomainEvent<Any>>()
        every { domainEvent.header } returns DefaultHeader.empty()
        every { domainEvent.commandId } returns GlobalIdGenerator.generateAsString()
        every { domainEvent.isLast } returns true
        val exchange = SimpleDomainEventExchange(domainEvent, mockk())
        val chain = FilterChainBuilder<DomainEventExchange<Any>>().build()
        projectedNotifierFilter.filter(exchange, chain)
            .test()
            .verifyComplete()
    }
}
