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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

internal class MainDispatcherTest {
    @Test
    fun `should not initialize aggregate dispatchers when stopping before start`() {
        val dispatcher = RecordingMainDispatcher()

        dispatcher.stopGracefully().block(Duration.ofSeconds(1))

        dispatcher.receiveCount.get().assert().isEqualTo(0)
        dispatcher.createCount.get().assert().isEqualTo(0)
        dispatcher.childStopCount.get().assert().isEqualTo(0)
    }

    private class RecordingMainDispatcher : MainDispatcher<String>() {
        val receiveCount = AtomicInteger()
        val createCount = AtomicInteger()
        val childStopCount = AtomicInteger()

        override val name: String = "RecordingMainDispatcher"
        override val namedAggregates: Set<NamedAggregate> = setOf(
            "test.test".toNamedAggregate().materialize(),
        )

        override fun receiveMessage(namedAggregate: NamedAggregate): Flux<String> {
            receiveCount.incrementAndGet()
            return Flux.never()
        }

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>
        ): MessageDispatcher {
            createCount.incrementAndGet()
            return object : MessageDispatcher {
                override val name: String = "RecordingChildDispatcher"

                override fun start() = Unit

                override fun stopGracefully(): Mono<Void> {
                    childStopCount.incrementAndGet()
                    return Mono.empty()
                }
            }
        }
    }
}
