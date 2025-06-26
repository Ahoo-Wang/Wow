package me.ahoo.wow.modeling.command

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.SimpleServerCommandExchange
import org.junit.jupiter.api.Test

class ExchangeCommandAggregateKtTest {

    @Test
    fun setCommandAggregate() {
        val exchange = SimpleServerCommandExchange(mockk<CommandMessage<*>>())
        exchange.setCommandAggregate(mockk()).getCommandAggregate<Any, Any>().assert().isNotNull()
    }
}
