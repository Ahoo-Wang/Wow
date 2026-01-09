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
package me.ahoo.wow.messaging.dispatcher

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.*

internal class AggregateDispatcherTest {
    @Test
    fun stopGracefully() {
        val testDispatcher = TestAggregateDispatcher()
        testDispatcher.start()

        // Give some time for the dispatcher to start processing
        Thread.sleep(100)

        // Stop gracefully and verify it completes
        testDispatcher.stopGracefully().block(Duration.ofSeconds(5))
    }

    class TestAggregateDispatcher : TestBaseAggregateDispatcher() {
        override fun handleExchange(exchange: TestMessageExchange): Mono<Void> = Mono.empty()
    }

    abstract class TestBaseAggregateDispatcher : AggregateDispatcher<TestMessageExchange>() {
        override val parallelism: Int = 2
        override val messageFlux =
            Mono
                .just(Unit)
                .repeat(2)
                .map {
                    TestMessageExchange(DefaultTestMessage())
                }.cast(TestMessageExchange::class.java)

        override val namedAggregate: NamedAggregate = "test.test".toNamedAggregate().materialize()
        override val name: String = "TestAggregateDispatcher"

        abstract override fun handleExchange(exchange: TestMessageExchange): Mono<Void>

        override fun TestMessageExchange.toGroupKey(): Int = 1
    }

    class TestMessageExchange(
        override val message: TestMessage
    ) : MessageExchange<TestMessageExchange, TestMessage> {
        override val attributes: MutableMap<String, Any> = HashMap()
    }

    interface TestMessage : Message<TestMessage, String> {
        override val id: String
            get() = UUID.randomUUID().toString()
        override val header: Header
            get() = DefaultHeader()
        override val body: String
            get() = "test"
        override val createTime: Long
            get() = System.currentTimeMillis()
    }

    class DefaultTestMessage : TestMessage
}
