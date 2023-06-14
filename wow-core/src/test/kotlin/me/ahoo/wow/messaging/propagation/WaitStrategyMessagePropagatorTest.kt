package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.COMMAND_WAIT_STAGE
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.injectWaitStrategy
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class WaitStrategyMessagePropagatorTest {

    @Test
    fun inject() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .asCommandMessage()
        upstreamMessage.header.injectWaitStrategy("wait-endpoint", CommandStage.SENT, "context", "processor")
        WaitStrategyMessagePropagator().inject(header, upstreamMessage)
        assertThat(header[COMMAND_WAIT_ENDPOINT], equalTo("wait-endpoint"))
        assertThat(header[COMMAND_WAIT_STAGE], equalTo("SENT"))
        assertThat(header[COMMAND_WAIT_CONTEXT], equalTo("context"))
        assertThat(header[COMMAND_WAIT_PROCESSOR], equalTo("processor"))
    }

    @Test
    fun injectIfBlank() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .asCommandMessage()
        upstreamMessage.header.injectWaitStrategy("wait-endpoint", CommandStage.SENT, "", "")
        WaitStrategyMessagePropagator().inject(header, upstreamMessage)
        assertThat(header[COMMAND_WAIT_ENDPOINT], equalTo(upstreamMessage.header[COMMAND_WAIT_ENDPOINT]))
        assertThat(header[COMMAND_WAIT_STAGE], equalTo(upstreamMessage.header[COMMAND_WAIT_STAGE]))
        assertThat(header[COMMAND_WAIT_CONTEXT], nullValue())
        assertThat(header[COMMAND_WAIT_PROCESSOR], nullValue())
    }
}
