package me.ahoo.wow.messaging.propagation

import io.mockk.mockk
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.UpstreamNameMessagePropagator.Companion.upstreamName
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class UpstreamNameMessagePropagatorTest {

    @Test
    fun inject() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        UpstreamNameMessagePropagator().inject(header, upstreamMessage)
        assertThat(header.upstreamName, equalTo(upstreamMessage.name))
    }

    @Test
    fun injectIfNotNamed() {
        val header = DefaultHeader.empty()
        val upstreamMessage = mockk<Message<*, *>>()
        UpstreamNameMessagePropagator().inject(header, upstreamMessage)
        assertThat(header.upstreamName, nullValue())
    }
}
