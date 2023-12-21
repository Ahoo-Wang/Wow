package me.ahoo.wow.compensation.server.webhook

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class TemplateEngineTest {

    @Test
    fun render() {
        val executionFailedCreated = mockk<ExecutionFailedCreated> {
            every { processor } returns ProcessorInfoData("context", "name")
        }
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<ExecutionFailedCreated>> {
            every { name }  returns "execution_failed_created"
            every { body } returns executionFailedCreated
        }
        val state = mockk<ReadOnlyStateAggregate<IExecutionFailedState>>()
        val rendered = TemplateEngine.renderOnEvent(domainEvent, state)
        assertThat(rendered, notNullValue())
    }
}