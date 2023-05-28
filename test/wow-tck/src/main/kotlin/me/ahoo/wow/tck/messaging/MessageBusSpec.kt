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
package me.ahoo.wow.tck.messaging

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.metrizable
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration

/**
 * Message Bus Implementation Specification.
 */
abstract class MessageBusSpec<M : Message<*>, E : MessageExchange<*, M>, BUS : MessageBus<M, E>> {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(MessageBusSpec::class.java)
    }

    abstract val namedAggregate: NamedAggregate
    protected abstract fun createMessageBus(): BUS
    protected abstract fun createMessage(): M
    protected open fun Flux<E>.onReceive(onReady: Sinks.Empty<Void>): Flux<E> {
        onReady.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST)
        return this
    }

    open fun verify(block: BUS.() -> Unit) {
        createMessageBus().metrizable().use { commandBus ->
            block(commandBus)
        }
    }

    @Test
    fun send() {
        verify {
            val onReady = Sinks.empty<Void>()
            val message = createMessage()
            receive(setOf(namedAggregate))
                .writeReceiverGroup(GlobalIdGenerator.generateAsString())
                .onReceive(onReady)
                .doOnSubscribe {
                    onReady.asMono().then(send(message)).subscribe()
                }
                .test()
                .consumeNextWith {
                    assertThat(it.message.id, equalTo(message.id))
                }
                .thenCancel()
                .verify()
        }
    }

    @Test
    fun receive() {
        verify {
            val onReady = Sinks.empty<Void>()
            receive(setOf(namedAggregate))
                .writeReceiverGroup(GlobalIdGenerator.generateAsString())
                .onReceive(onReady)
                .doOnSubscribe {
                    val sendFlux = Flux.range(0, 10)
                        .flatMap {
                            val message = createMessage()
                            send(message)
                        }
                    onReady.asMono().thenMany(sendFlux).subscribe()
                }
                .test()
                .expectNextCount(10)
                .verifyTimeout(Duration.ofSeconds(2))
        }
    }

    @Test
    fun sendPerformance() {
        verify {
            val duration = sendLoop(messageBus = this)
                .test()
                .verifyComplete()
            log.info("[${this.javaClass.simpleName}] sendPerformance - duration:{}", duration)
        }
    }

    private fun sendLoop(messageBus: BUS, maxCount: Int = 2000): Mono<Void> {
        return Flux.range(0, maxCount)
            .flatMap {
                val message = createMessage()
                messageBus.send(message)
            }.then()
    }

    @Test
    fun receivePerformance() {
        verify {
            val maxCount: Long = 2000
            val onReady = Sinks.empty<Void>()
            val duration = receive(setOf(namedAggregate))
                .writeReceiverGroup(GlobalIdGenerator.generateAsString())
                .onReceive(onReady)
                .doOnSubscribe {
                    val sendFlux = sendLoop(messageBus = this, maxCount = maxCount.toInt())
                    onReady.asMono()
                        .thenMany(sendFlux)
                        .delaySubscription(Duration.ofMillis(2000))
                        .subscribe()
                }
                .test()
                .expectNextCount(maxCount)
                .verifyTimeout(Duration.ofSeconds(5))
            log.info("[${this.javaClass.simpleName}] receivePerformance - duration:{}", duration)
        }
    }
}
