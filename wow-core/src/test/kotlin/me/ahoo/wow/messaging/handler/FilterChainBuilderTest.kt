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
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.ioc.ServiceProvider
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

internal class FilterChainBuilderTest {
    @Test
    fun build() {
        val chain = FilterChainBuilder<MessageExchange<Message<Any>>>()
            .addFilter(MockLastFilter())
            .addFilter(MockFirstFilter())
            .build() as SimpleFilterChain

        assertThat(chain.current, instanceOf(MockFirstFilter::class.java))
        assertThat((chain.next as SimpleFilterChain).current, instanceOf(MockLastFilter::class.java))
        val exchange: MessageExchange<Message<Any>> = object : MessageExchange<Message<Any>> {
            override val message: Message<Any>
                get() = throw UnsupportedOperationException()
            override var serviceProvider: ServiceProvider? = null
        }
        chain.filter(exchange)
            .test()
            .verifyComplete()
    }
}

@Order(ORDER_FIRST)
internal class MockFirstFilter : Filter<MessageExchange<Message<Any>>> {
    override fun filter(
        exchange: MessageExchange<Message<Any>>,
        chain: FilterChain<MessageExchange<Message<Any>>>,
    ): Mono<Void> {
        return chain.filter(exchange)
    }
}

@Order(ORDER_LAST)
internal class MockLastFilter : Filter<MessageExchange<Message<Any>>> {
    override fun filter(
        exchange: MessageExchange<Message<Any>>,
        chain: FilterChain<MessageExchange<Message<Any>>>,
    ): Mono<Void> {
        return chain.filter(exchange)
    }
}
