package me.ahoo.wow.messaging.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.COMMAND_WAIT_ENDPOINT
import me.ahoo.wow.command.wait.COMMAND_WAIT_FUNCTION
import me.ahoo.wow.command.wait.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.requireExtractWaitCommandId
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.COMMAND_WAIT_STAGE
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class WaitStrategyMessagePropagatorTest {

    @Test
    fun propagate() {
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        WaitingForStage.projected(upstreamMessage.commandId, "context", "processor", "function")
            .propagate("wait-endpoint", upstreamMessage.header)
        val header = DefaultHeader.empty()
        WaitStrategyMessagePropagator().propagate(header, upstreamMessage)
        header.requireExtractWaitCommandId().assert().isEqualTo(upstreamMessage.commandId)
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo("wait-endpoint")
        header[COMMAND_WAIT_STAGE].assert().isEqualTo("PROJECTED")
        header[COMMAND_WAIT_CONTEXT].assert().isEqualTo("context")
        header[COMMAND_WAIT_PROCESSOR].assert().isEqualTo("processor")
        header[COMMAND_WAIT_FUNCTION].assert().isEqualTo("function")
    }

    @Test
    fun propagateIfBlank() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage()
        WaitingForStage.sent(upstreamMessage.commandId).propagate("wait-endpoint", upstreamMessage.header)
        WaitStrategyMessagePropagator().propagate(header, upstreamMessage)
        header[COMMAND_WAIT_ENDPOINT].assert().isEqualTo(upstreamMessage.header[COMMAND_WAIT_ENDPOINT])
        header[COMMAND_WAIT_STAGE].assert().isEqualTo(upstreamMessage.header[COMMAND_WAIT_STAGE])
        header[COMMAND_WAIT_CONTEXT].assert().isNull()
        header[COMMAND_WAIT_PROCESSOR].assert().isNull()
        header[COMMAND_WAIT_FUNCTION].assert().isNull()
    }

    @Test
    fun propagateIfDomainEvent() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockAggregateCreated(generateGlobalId())
                .toDomainEvent(generateGlobalId(), generateGlobalId(), generateGlobalId())
        WaitingForStage.sent(upstreamMessage.commandId).propagate("wait-endpoint", upstreamMessage.header)
        WaitStrategyMessagePropagator().propagate(header, upstreamMessage)
        header[COMMAND_WAIT_ENDPOINT].assert().isNull()
    }
}
