package me.ahoo.wow.messaging.propagation

import io.mockk.mockk
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.CommandNameMessagePropagator.Companion.commandName
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CommandNameMessagePropagatorTest {

    @Test
    fun inject() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        CommandNameMessagePropagator().inject(header, upstreamMessage)
        assertThat(header.commandName, equalTo(upstreamMessage.name))
    }

    @Test
    fun injectIfNotCommand() {
        val header = DefaultHeader.empty()
        val upstreamMessage = mockk<DomainEvent<Any>>()
        CommandNameMessagePropagator().inject(header, upstreamMessage)
        assertThat(header.commandName, nullValue())
    }
}
