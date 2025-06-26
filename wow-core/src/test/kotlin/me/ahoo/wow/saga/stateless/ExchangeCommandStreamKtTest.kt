package me.ahoo.wow.saga.stateless

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.event.SimpleDomainEventExchange
import org.junit.jupiter.api.Test

class ExchangeCommandStreamKtTest {

    @Test
    fun setCommandStream() {
        val exchange = SimpleDomainEventExchange(mockk<DomainEvent<Any>>())
        exchange.setCommandStream(mockk()).getCommandStream().assert().isNotNull()
    }
}
