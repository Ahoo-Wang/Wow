package me.ahoo.wow.compensation.server.webhook

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.RetrySpec
import me.ahoo.wow.compensation.api.RetryState
import me.ahoo.wow.compensation.domain.ExecutionFailed
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class TemplateEngineTest {
    private val executionFailedAggregate = aggregateMetadata<ExecutionFailed, ExecutionFailedState>()

    @Test
    fun render() {
        val executionFailedState = ExecutionFailedState(GlobalIdGenerator.generateAsString())
        executionFailedState.onCreated(
            ExecutionFailedCreated(
                eventId = EventId("eventId", executionFailedAggregate.aggregateId(), 1),
                processor = ProcessorInfoData("context", "name"),
                functionKind = FunctionKind.EVENT,
                error = ErrorDetails("errorCode", "errorMsg", "stackTrace"),
                executeAt = 0,
                retrySpec = RetrySpec(10, 1, 1),
                retryState = RetryState(0, 0, 0, 0)
            )
        )
        val stateAggregate = mockk<ReadOnlyStateAggregate<IExecutionFailedState>>() {
            every { state } returns executionFailedState
        }
        val executionFailedCreated = mockk<ExecutionFailedCreated> {
            every { processor } returns ProcessorInfoData("context", "name")
        }
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<ExecutionFailedCreated>> {
            every { name } returns "execution_failed_created"
            every { body } returns executionFailedCreated
        }

        val rendered = TemplateEngine.renderOnEvent(domainEvent, stateAggregate, "http://localhost:8080")
        assertThat(rendered, notNullValue())
    }
}