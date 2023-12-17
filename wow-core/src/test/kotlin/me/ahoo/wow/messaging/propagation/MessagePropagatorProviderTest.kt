package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.MessagePropagatorProvider.inject
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class MessagePropagatorProviderTest {

    @Test
    fun inject() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        header.inject(upstreamMessage)
    }
}
