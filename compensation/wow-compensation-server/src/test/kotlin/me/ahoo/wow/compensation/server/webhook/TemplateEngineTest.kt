package me.ahoo.wow.compensation.server.webhook

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.RecoverableMarked
import me.ahoo.wow.compensation.api.RetrySpec
import me.ahoo.wow.compensation.api.RetryState
import me.ahoo.wow.compensation.domain.ExecutionFailed
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.compensation.server.webhook.QuickNavigation.toNavAsMarkdown
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.naming.annotation.toName
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class TemplateEngineTest {
    private val executionFailedAggregate = aggregateMetadata<ExecutionFailed, ExecutionFailedState>()
    private val executionFailedState = ExecutionFailedState(GlobalIdGenerator.generateAsString())
    private val stateAggregate = mockk<ReadOnlyStateAggregate<IExecutionFailedState>> {
        every { state } returns executionFailedState
    }
    private val host = "http://localhost:8080"

    init {
        executionFailedState.onCreated(
            ExecutionFailedCreated(
                eventId = EventId("eventId", executionFailedAggregate.aggregateId(), 1),
                function = FunctionInfoData(FunctionKind.EVENT, "context", "processor", "function"),
                error = ErrorDetails("errorCode", "errorMsg", "stackTrace"),
                executeAt = 0,
                retrySpec = RetrySpec(10, 1, 1),
                retryState = RetryState(0, 0, 0, 0)
            )
        )
    }

    @Test
    fun renderExecutionFailedCreated() {
        val eventBody = mockk<ExecutionFailedCreated> {
            every { error } returns ErrorDetails("errorCode", "errorMsg", "stackTrace")
        }
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<ExecutionFailedCreated>> {
            every { name } returns ExecutionFailedCreated::class.java.toName()
            every { body } returns eventBody
        }

        val rendered = TemplateEngine.renderOnEvent(domainEvent, stateAggregate, host)
        rendered.assert().isNotNull()
    }

    @Test
    fun renderExecutionFailedApplied() {
        val eventBody = mockk<ExecutionFailedApplied> {
            every { error } returns ErrorDetails("errorCode", "errorMsg", "stackTrace")
        }
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<ExecutionFailedApplied>> {
            every { name } returns ExecutionFailedApplied::class.java.toName()
            every { body } returns eventBody
        }

        val rendered = TemplateEngine.renderOnEvent(domainEvent, stateAggregate, host)
        rendered.assert().isNotNull()
    }

    @Test
    fun renderExecutionSuccessApplied() {
        val eventBody = mockk<ExecutionSuccessApplied>()
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<ExecutionSuccessApplied>> {
            every { name } returns ExecutionSuccessApplied::class.java.toName()
            every { body } returns eventBody
        }

        val rendered = TemplateEngine.renderOnEvent(domainEvent, stateAggregate, host)
        rendered.assert().isNotNull()
    }

    @Test
    fun renderCompensationPrepared() {
        val eventBody = mockk<CompensationPrepared>()
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<CompensationPrepared>> {
            every { name } returns CompensationPrepared::class.java.toName()
            every { body } returns eventBody
        }

        val rendered = TemplateEngine.renderOnEvent(domainEvent, stateAggregate, host)
        rendered.assert().isNotNull()
    }

    @Test
    fun renderRecoverableMarked() {
        val eventBody = mockk<RecoverableMarked>()
        val domainEvent = mockk<me.ahoo.wow.api.event.DomainEvent<RecoverableMarked>> {
            every { name } returns RecoverableMarked::class.java.toName()
            every { body } returns eventBody
        }

        val rendered = TemplateEngine.renderOnEvent(domainEvent, stateAggregate, host)
        rendered.assert().isNotNull()
    }

    @Test
    fun toNavAsMarkdown() {
        val emptyHostNav = stateAggregate.state.toNavAsMarkdown("")
        assertThat(emptyHostNav, equalTo("`${executionFailedState.id}`"))
        val hostNav = stateAggregate.state.toNavAsMarkdown(host)
        hostNav.assert().isEqualTo("[${executionFailedState.id}]($host/to-retry?id=${executionFailedState.id})")
    }
}
