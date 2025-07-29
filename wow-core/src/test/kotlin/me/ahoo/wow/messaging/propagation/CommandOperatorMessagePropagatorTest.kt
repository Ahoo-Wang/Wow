package me.ahoo.wow.messaging.propagation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandOperator.operator
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class CommandOperatorMessagePropagatorTest {

    @Test
    fun propagate() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        upstreamMessage.header.withOperator("operator")
        CommandOperatorMessagePropagator().propagate(header, upstreamMessage)
        header.operator.assert().isEqualTo("operator")
    }

    @Test
    fun propagateIfNull() {
        val header = DefaultHeader.empty()
        val upstreamMessage =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage()
        CommandOperatorMessagePropagator().propagate(header, upstreamMessage)
        header.operator.assert().isNull()
    }
}
