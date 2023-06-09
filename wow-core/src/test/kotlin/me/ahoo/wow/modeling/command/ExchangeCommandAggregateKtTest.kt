package me.ahoo.wow.modeling.command

import io.mockk.mockk
import me.ahoo.wow.command.CommandMessage
import me.ahoo.wow.command.SimpleServerCommandExchange
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ExchangeCommandAggregateKtTest {

    @Test
    fun setCommandAggregate() {
        val exchange = SimpleServerCommandExchange(mockk<CommandMessage<*>>())
        assertThat(exchange.setCommandAggregate(mockk()).getCommandAggregate<Any, Any>(), notNullValue())
    }
}
