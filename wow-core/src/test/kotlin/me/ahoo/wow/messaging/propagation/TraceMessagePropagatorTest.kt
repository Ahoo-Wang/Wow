package me.ahoo.wow.messaging.propagation

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.traceId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamId
import me.ahoo.wow.messaging.propagation.TraceMessagePropagator.Companion.upstreamName
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class TraceMessagePropagatorTest {

    @Test
    fun propagate() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        TraceMessagePropagator().propagate(injectedHeader, upstreamMessage)
        upstreamMessage.header.traceId.assert().isEqualTo(upstreamMessage.id)
        injectedHeader.traceId.assert().isEqualTo(upstreamMessage.header.traceId)
        injectedHeader.upstreamId.assert().isEqualTo(upstreamMessage.id)
        injectedHeader.upstreamName.assert().isEqualTo(upstreamMessage.name)
    }

    @Test
    fun propagateIfNotNamed() {
        val injectedHeader = DefaultHeader.empty()
        val upstreamMessage = mockk<Message<*, *>> {
            every { id } returns generateGlobalId()
            every { header } returns DefaultHeader.empty()
        }
        TraceMessagePropagator().propagate(injectedHeader, upstreamMessage)
        injectedHeader.upstreamName.assert().isNull()
    }
}
