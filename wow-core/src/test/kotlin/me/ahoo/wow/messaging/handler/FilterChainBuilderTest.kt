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

package me.ahoo.wow.messaging.handler

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.command.AggregateProcessor
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

internal class FilterChainBuilderTest {
    @Suppress("ThrowsCount")
    @Test
    fun build() {
        val chain = FilterChainBuilder<ServerCommandExchange<Any>>()
            .addFilter(MockLastFilter())
            .addFilter(MockFirstFilter())
            .build() as SimpleFilterChain

        assertThat(chain.current, instanceOf(MockFirstFilter::class.java))
        assertThat((chain.next as SimpleFilterChain).current, instanceOf(MockLastFilter::class.java))
        val exchange: ServerCommandExchange<Any> = object : ServerCommandExchange<Any> {
            override var aggregateProcessor: AggregateProcessor<Any>?
                get() = throw UnsupportedOperationException()
                set(value) {}
            override var eventStream: DomainEventStream?
                get() = throw UnsupportedOperationException()
                set(value) {}
            override val attributes: MutableMap<String, Any>
                get() = mutableMapOf()
            override val message: CommandMessage<Any>
                get() = throw UnsupportedOperationException()
        }
        chain.filter(exchange)
            .test()
            .verifyComplete()
    }
}

@Order(ORDER_FIRST)
internal class MockFirstFilter : Filter<ServerCommandExchange<Any>> {
    override fun filter(
        exchange: ServerCommandExchange<Any>,
        next: FilterChain<ServerCommandExchange<Any>>
    ): Mono<Void> {
        return next.filter(exchange)
    }
}

@Order(ORDER_LAST)
internal class MockLastFilter : Filter<ServerCommandExchange<Any>> {
    override fun filter(
        exchange: ServerCommandExchange<Any>,
        next: FilterChain<ServerCommandExchange<Any>>
    ): Mono<Void> {
        return next.filter(exchange)
    }
}
