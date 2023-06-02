package me.ahoo.wow.event

import io.mockk.mockk
import me.ahoo.wow.api.event.DomainEvent
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExchangeEventFunctionKtTest {

    @Test
    fun setEventFunction() {
        val exchange = SimpleDomainEventExchange(mockk<DomainEvent<Any>>())
        assertThat(exchange.setEventFunction(mockk()).getEventFunction(), notNullValue())
    }
}
