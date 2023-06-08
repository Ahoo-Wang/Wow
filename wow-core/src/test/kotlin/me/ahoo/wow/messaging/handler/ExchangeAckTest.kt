package me.ahoo.wow.messaging.handler

import io.mockk.called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ExchangeAckTest {

    @Test
    fun monoFinallyAck() {
        val exchange = mockk<MessageExchange<*, *>> {
            every { acknowledge() } returns Mono.empty()
        }
        Mono.empty<Void>()
            .finallyAck(exchange)
            .test()
            .verifyComplete()

        verify {
            exchange.acknowledge()
        }
    }

    @Test
    fun monoFinallyAckIfError() {
        val exchange = mockk<MessageExchange<*, *>> {
            every { acknowledge() } returns Mono.empty()
        }
        Mono.error<Void>(IllegalArgumentException())
            .finallyAck(exchange)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        verify {
            exchange.acknowledge()
        }
    }

    @Test
    fun fluxFinallyAck() {
        val exchange = mockk<MessageExchange<*, *>> {
            every { acknowledge() } returns Mono.empty()
        }
        Flux.empty<Void>()
            .finallyAck(exchange)
            .test()
            .verifyComplete()

        verify {
            exchange.acknowledge()
        }
    }

    @Test
    fun fluxFinallyAckIfError() {
        val exchange = mockk<MessageExchange<*, *>> {
            every { acknowledge() } returns Mono.empty()
        }
        Flux.error<Void>(IllegalArgumentException())
            .finallyAck(exchange)
            .test()
            .expectError(IllegalArgumentException::class.java)
            .verify()

        verify {
            exchange.acknowledge()
        }
    }

    @Test
    fun filterThenAckIfTrue() {
        val exchange = mockk<MessageExchange<*, *>> {
            every { acknowledge() } returns Mono.empty()
        }
        Flux.just(exchange)
            .filterThenAck {
                true
            }
            .test()
            .expectNext(exchange)
            .verifyComplete()

        verify {
            exchange.acknowledge() wasNot called
        }
    }

    @Test
    fun filterThenAckIfFalse() {
        val exchange = mockk<MessageExchange<*, *>> {
            every { acknowledge() } returns Mono.empty()
        }
        Flux.just(exchange)
            .filterThenAck {
                false
            }
            .test()
            .verifyComplete()
        verify {
            exchange.acknowledge()
        }
    }
}
