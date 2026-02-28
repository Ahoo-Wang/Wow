package me.ahoo.wow.compensation.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.domain.ExecutionFailed
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class CompensationEventProcessorTest {
    companion object {
        internal val LOCAL_AGGREGATE = aggregateMetadata<ExecutionFailed, ExecutionFailedState>()
    }

    @Test
    fun onCompensationPreparedWhenNotLocal() {
        val compensationPrepared = mockk<CompensationPrepared> {
            every { eventId.aggregateId } returns "test.not_local".toNamedAggregate().aggregateId()
        }
        sagaVerifier<CompensationEventProcessor>()
            .inject {
                register(mockk<EventCompensateSupporter>())
            }
            .whenEvent(compensationPrepared)
            .expectNoCommand()
            .verify()
    }

    @Test
    fun onCompensationPrepared() {
        val eventId = EventId(GlobalIdGenerator.generateAsString(), LOCAL_AGGREGATE.aggregateId(), 1)
        val function = FunctionInfoData(FunctionKind.EVENT, "context", "processor", "function")
        val compensationPrepared = CompensationPrepared(eventId, function, mockk())
        val eventCompensateSupporter = mockk<EventCompensateSupporter> {
            every {
                compensate(
                    eventId.aggregateId,
                    eventId.version,
                    any()
                )
            } returns Mono.empty()
        }
        sagaVerifier<CompensationEventProcessor>()
            .inject {
                register(eventCompensateSupporter)
            }
            .whenEvent(compensationPrepared)
            .expectNoCommand()
            .verify()

        verify {
            eventCompensateSupporter.compensate(
                eventId.aggregateId,
                eventId.version,
                any()
            )
        }
    }
}
