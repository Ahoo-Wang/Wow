package me.ahoo.wow.event

import io.mockk.mockk
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExchangeEventFunctionKtTest {

    @Test
    fun setEventFunction() {
        val exchange = SimpleDomainEventExchange(mockk<DomainEvent<Any>>())
        assertThat(exchange.setEventFunction(mockk()).getEventFunction(), notNullValue())
        assertThat(exchange.getProcessor(), notNullValue())
    }
}
