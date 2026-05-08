package me.ahoo.wow.messaging.handler

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
    fun `should mono finally ack`() {
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
    fun `should mono finally ack if error`() {
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
    fun `should flux finally ack`() {
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
    fun `should flux finally ack if error`() {
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
    fun `should filter then ack if true`() {
        val exchange = mockk<MessageExchange<*, *>>()
        Flux.just(exchange)
            .filterThenAck {
                true
            }
            .test()
            .expectNext(exchange)
            .verifyComplete()
    }

    @Test
    fun `should filter then ack if false`() {
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
