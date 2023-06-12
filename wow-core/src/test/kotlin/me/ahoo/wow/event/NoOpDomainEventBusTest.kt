package me.ahoo.wow.event

import io.mockk.mockk
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpDomainEventBusTest {

    @Test
    fun send() {
        NoOpDomainEventBus.send(mockk())
            .test()
            .verifyComplete()
    }

    @Test
    fun receive() {
        NoOpDomainEventBus.receive(mockk())
            .test()
            .verifyComplete()
    }
}
