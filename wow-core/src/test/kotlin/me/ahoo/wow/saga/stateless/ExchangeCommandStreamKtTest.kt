package me.ahoo.wow.saga.stateless

import io.mockk.mockk
import me.ahoo.wow.event.DomainEvent
import me.ahoo.wow.event.SimpleDomainEventExchange
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExchangeCommandStreamKtTest {

    @Test
    fun setCommandStream() {
        val exchange = SimpleDomainEventExchange(mockk<DomainEvent<Any>>())
        assertThat(exchange.setCommandStream(mockk()).getCommandStream(), notNullValue())
    }
}
