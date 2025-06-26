package me.ahoo.wow.messaging.compensation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.CompensationMatcher.compensationId
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test

class CompensationMatcherTest {
    private val contextName = "context"
    private val processorName = "processor"
    private val functionName = "functionName"
    private val function = FunctionInfoData(
        contextName = contextName,
        processorName = processorName,
        name = functionName,
        functionKind = FunctionKind.STATE_EVENT
    )

    @Test
    fun withCompensation() {
        val target = CompensationTarget(function = function)
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
        ).toCommandMessage().withCompensation(target)
        command.header.compensationId.assert().isEqualTo(target.id)
        command.header[COMPENSATION_CONTEXT].assert().isEqualTo(contextName)
        command.header[COMPENSATION_PROCESSOR].assert().isEqualTo(processorName)
        command.match(function).assert().isEqualTo(true)
    }

    @Test
    fun matchIfNull() {
        DefaultHeader.empty().match(function).assert().isEqualTo(true)
    }

    @Test
    fun matchIfFunctionNull() {
        val header = DefaultHeader.empty()
        header[COMPENSATION_ID] = GlobalIdGenerator.generateAsString()
        header[COMPENSATION_CONTEXT] = contextName
        header.match(function).assert().isEqualTo(false)
    }
}
