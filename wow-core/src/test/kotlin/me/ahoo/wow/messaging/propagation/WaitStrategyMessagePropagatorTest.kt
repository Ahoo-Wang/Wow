package me.ahoo.wow.messaging.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_FUNCTION
import me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.COMMAND_WAIT_STAGE
import me.ahoo.wow.command.wait.injectWaitStrategy
import me.ahoo.wow.command.wait.stage.WaitingFor
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class WaitStrategyMessagePropagatorTest {

    @Test
    fun inject() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()

        upstreamMessage.header.injectWaitStrategy(
            "wait-endpoint",
            WaitingFor.projected("context", "processor", "function")
        )
        WaitStrategyMessagePropagator().inject(header, upstreamMessage)
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo("wait-endpoint")
        header[COMMAND_WAIT_STAGE].assert().isEqualTo("PROJECTED")
        header[COMMAND_WAIT_CONTEXT].assert().isEqualTo("context")
        header[COMMAND_WAIT_PROCESSOR].assert().isEqualTo("processor")
        header[COMMAND_WAIT_FUNCTION].assert().isEqualTo("function")
    }

    @Test
    fun injectIfBlank() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        upstreamMessage.header.injectWaitStrategy("wait-endpoint", WaitingFor.sent())
        WaitStrategyMessagePropagator().inject(header, upstreamMessage)
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(upstreamMessage.header[COMMAND_WAIT_ENDPOINT])
        header[COMMAND_WAIT_STAGE].assert().isEqualTo(upstreamMessage.header[COMMAND_WAIT_STAGE])
        header[COMMAND_WAIT_CONTEXT].assert().isNull()
        header[COMMAND_WAIT_PROCESSOR].assert().isNull()
        header[COMMAND_WAIT_FUNCTION].assert().isNull()
    }
}
