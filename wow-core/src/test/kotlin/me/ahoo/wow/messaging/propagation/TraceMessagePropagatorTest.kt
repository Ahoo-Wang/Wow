package me.ahoo.wow.messaging.propagation

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamName
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class TraceMessagePropagatorTest {

    @Test
    fun inject() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        TraceMessagePropagator().inject(injectedHeader, upstreamMessage)
        assertThat(upstreamMessage.header.traceId, equalTo(upstreamMessage.id))
        assertThat(injectedHeader.traceId, equalTo(upstreamMessage.header.traceId))
        assertThat(injectedHeader.upstreamId, equalTo(upstreamMessage.id))
        assertThat(injectedHeader.upstreamName, equalTo(upstreamMessage.name))
    }

    @Test
    fun injectIfNotNamed() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage = mockk<Message<*, *>> {
            every { id } returns generateGlobalId()
            every { header } returns DefaultHeader.empty()
        }
        TraceMessagePropagator().inject(injectedHeader, upstreamMessage)
        assertThat(injectedHeader.upstreamName, nullValue())
    }
}
