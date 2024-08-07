package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.messaging.compensation.CompensationMatcher.compensationId
import me.ahoo.wow.messaging.compensation.CompensationMatcher.match
import me.ahoo.wow.messaging.compensation.CompensationMatcher.withCompensation
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(command.header.compensationId, equalTo(target.id))
        assertThat(command.header[COMPENSATION_CONTEXT], equalTo(contextName))
        assertThat(command.header[COMPENSATION_PROCESSOR], equalTo(processorName))
        assertThat(command.match(function), equalTo(true))
    }

    @Test
    fun matchIfNull() {
        assertThat(DefaultHeader.empty().match(function), equalTo(true))
    }

    @Test
    fun matchIfFunctionNull() {
        val header = DefaultHeader.empty()
        header[COMPENSATION_ID] = GlobalIdGenerator.generateAsString()
        header[COMPENSATION_CONTEXT] = contextName
        assertThat(header.match(function), equalTo(false))
    }
}
