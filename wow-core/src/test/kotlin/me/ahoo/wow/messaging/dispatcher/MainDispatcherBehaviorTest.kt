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
import me.ahoo.wow.messaging.getReceiverGroup
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger

class MainDispatcherBehaviorTest {

    @Test
    fun `stopGracefully before start does not initialize aggregate dispatchers`() {
        val dispatcher = RecordingMainDispatcher()

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()

        dispatcher.receiveCount.get().assert().isEqualTo(0)
        dispatcher.createCount.get().assert().isEqualTo(0)
        dispatcher.childStopCount.get().assert().isEqualTo(0)
    }

    @Test
    fun `start creates one child per aggregate and subscribes with receiver group context`() {
        val dispatcher = RecordingMainDispatcher()

        StepVerifier.create(dispatcher.receiverGroups.asFlux().take(2))
            .then { dispatcher.start() }
            .expectNext("recording-main", "recording-main")
            .verifyComplete()

        dispatcher.receiveCount.get().assert().isEqualTo(2)
        dispatcher.createCount.get().assert().isEqualTo(2)

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()
        dispatcher.childStopCount.get().assert().isEqualTo(2)
    }

    private class RecordingMainDispatcher : MainDispatcher<String>() {
        val receiveCount = AtomicInteger()
        val createCount = AtomicInteger()
        val childStopCount = AtomicInteger()
        val receiverGroups: Sinks.Many<String> = Sinks.many().replay().all()

        override val name: String = "recording-main"
        override val namedAggregates: Set<NamedAggregate> = setOf(
            "wow-core-test.messaging_aggregate".toNamedAggregate().materialize(),
            "wow-core-test.command_aggregate".toNamedAggregate().materialize(),
        )

        override fun receiveMessage(namedAggregate: NamedAggregate): Flux<String> {
            receiveCount.incrementAndGet()
            return Flux.deferContextual {
                Flux.just(it.getReceiverGroup())
            }
        }

        override fun newAggregateDispatcher(
            namedAggregate: NamedAggregate,
            messageFlux: Flux<String>
        ): MessageDispatcher {
            createCount.incrementAndGet()
            return object : MessageDispatcher {
                override val name: String = "child-${namedAggregate.aggregateName}"

                override fun start() {
                    messageFlux.subscribe {
                        receiverGroups.tryEmitNext(it).orThrow()
                    }
                }

                override fun stopGracefully(): Mono<Void> =
                    Mono.fromRunnable {
                        childStopCount.incrementAndGet()
                    }
            }
        }
    }
}
