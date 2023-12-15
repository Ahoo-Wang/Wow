package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.command.asCommandMessage
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
    private val processor = ProcessorInfoData(contextName = contextName, processorName = processorName)

    @Test
    fun withCompensation() {
        val target = CompensationTarget(processor = processor)
        val command = MockCreateAggregate(
            GlobalIdGenerator.generateAsString(),
            GlobalIdGenerator.generateAsString(),
        ).asCommandMessage().withCompensation(target)
        assertThat(command.header.compensationId, equalTo(target.id))
        assertThat(command.header[COMPENSATION_CONTEXT], equalTo(contextName))
        assertThat(command.header[COMPENSATION_PROCESSOR], equalTo(processorName))
        assertThat(command.match(processor), equalTo(true))
    }

    @Test
    fun matchIfNull() {
        assertThat(DefaultHeader.empty().match(processor), equalTo(true))
    }

    @Test
    fun matchIfProcessorNull() {
        val header = DefaultHeader.empty()
        header[COMPENSATION_ID] = GlobalIdGenerator.generateAsString()
        header[COMPENSATION_CONTEXT] = contextName
        assertThat(header.match(processor), equalTo(false))
    }
}
