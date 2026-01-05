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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.TopicKindCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.Decorator.Companion.getOriginalDelegate
import me.ahoo.wow.messaging.LocalMessageBus
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.metrizable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import java.time.Duration

/**
 * Message Bus Implementation Specification.
 */
abstract class MessageBusSpec<M : Message<*, *>, E : MessageExchange<*, M>, BUS : MessageBus<M, E>> : TopicKindCapable {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    abstract val namedAggregate: NamedAggregate
    protected abstract fun createMessageBus(): BUS
    protected abstract fun createMessage(): M
    protected open fun Flux<E>.onReceive(onReady: Sinks.Empty<Void>): Flux<E> {
        onReady.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST)
        return this
    }

    open fun verify(block: BUS.() -> Unit) {
        val messageBus = createMessageBus()
        messageBus.metrizable().use { bus ->
            if (bus.getOriginalDelegate() is TopicKindCapable) {
                (bus.getOriginalDelegate() as TopicKindCapable).topicKind.assert().isEqualTo(topicKind)
            }
            block(bus)
        }
        messageBus.close()
    }

    @Test
    fun localSubscriberCount() {
        val messageBus = createMessageBus().metrizable()
        if (messageBus !is LocalMessageBus<*, *>) {
            return
        }
        messageBus.subscriberCount(namedAggregate).assert().isEqualTo(0)
        messageBus.receive(setOf(namedAggregate)).test()
            .then {
                messageBus.subscriberCount(namedAggregate).assert().isEqualTo(1)
            }
            .expectNextCount(0)
            .thenCancel()
            .verify()
    }

    @Test
    fun send() {
        verify {
            val onReady = Sinks.empty<Void>()
            val message = createMessage()
            receive(setOf(namedAggregate))
                .writeReceiverGroup(generateGlobalId())
                .onReceive(onReady)
                .doOnSubscribe {
                    onReady.asMono()
                        .then(send(message))
                        .delaySubscription(Duration.ofMillis(1000))
                        .subscribe()
                }
                .test()
                .consumeNextWith {
                    it.message.id.assert().isEqualTo(message.id)
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
                .writeReceiverGroup(generateGlobalId())
                .onReceive(onReady)
                .doOnSubscribe {
                    val sendFlux = Flux.range(0, 10)
                        .flatMap {
                            val message = createMessage()
                            send(message)
                        }
                    onReady.asMono()
                        .thenMany(sendFlux)
                        .delaySubscription(Duration.ofMillis(1000))
                        .subscribe()
                }
                .test()
                .expectNextCount(10)
                .thenCancel()
                .verify()
        }
    }

    @Test
    fun sendPerformance() {
        verify {
            val onReady = Sinks.empty<Void>()
            receive(setOf(namedAggregate))
                .writeReceiverGroup(generateGlobalId())
                .onReceive(onReady)
                .doOnSubscribe {
                    val duration = sendLoop(messageBus = this)
                        .test()
                        .verifyComplete()
                    log.info {
                        "[${this.javaClass.simpleName}] sendPerformance - duration:$duration"
                    }
                }
                .test()
                .thenCancel()
                .verify()
        }
    }

    private fun sendLoop(messageBus: BUS, maxCount: Int = 1000): Mono<Void> {
        return Flux.range(0, maxCount)
            .flatMap {
                val message = createMessage()
                messageBus.send(message)
            }.then()
    }

    @DisabledIfEnvironmentVariable(named = "CI", matches = ".*")
    @Test
    fun receivePerformance() {
        verify {
            val maxCount: Long = 1000
            val onReady = Sinks.empty<Void>()
            val duration = receive(setOf(namedAggregate))
                .writeReceiverGroup(generateGlobalId())
                .onReceive(onReady)
                .doOnSubscribe {
                    val sendFlux = sendLoop(messageBus = this, maxCount = maxCount.toInt())
                    onReady.asMono()
                        .thenMany(sendFlux)
                        .delaySubscription(Duration.ofMillis(1000))
                        .subscribe()
                }
                .test()
                .expectNextCount(maxCount)
                .thenCancel()
                .verify()
            log.info { "[${this.javaClass.simpleName}] receivePerformance - duration:$duration" }
        }
    }
}
