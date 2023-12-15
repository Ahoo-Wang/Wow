package me.ahoo.wow.compensation.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.EventId
import me.ahoo.wow.compensation.domain.ExecutionFailed
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.snapshot.SNAPSHOT_PROCESSOR
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.asNamedAggregate
import me.ahoo.wow.test.SagaVerifier.sagaVerifier
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class CompensationSagaTest {
    companion object {
        private val LOCAL_AGGREGATE = aggregateMetadata<ExecutionFailed, ExecutionFailedState>()
    }

    @Test
    fun onCompensationPreparedWhenNotLocal() {
        val compensationPrepared = mockk<CompensationPrepared> {
            every { eventId.aggregateId } returns "test.not_local".asNamedAggregate().asAggregateId()
        }
        sagaVerifier<CompensationSaga>()
            .inject(mockk<DomainEventCompensator>())
            .inject(mockk<StateEventCompensator>())
            .`when`(compensationPrepared)
            .expectNoCommand()
            .verify()
    }

    @Test
    fun onCompensationPreparedWhenEvent() {
        val eventId = EventId(GlobalIdGenerator.generateAsString(), LOCAL_AGGREGATE.asAggregateId(), 1)
        val compensationPrepared = CompensationPrepared(eventId, SNAPSHOT_PROCESSOR, FunctionKind.EVENT)
        val domainEventCompensator = mockk<DomainEventCompensator> {
            every {
                compensate(
                    eventId.aggregateId,
                    eventId.version,
                    any()
                )
            } returns Mono.empty()
        }
        sagaVerifier<CompensationSaga>()
            .inject(domainEventCompensator)
            .inject(mockk<StateEventCompensator>())
            .`when`(compensationPrepared)
            .expectNoCommand()
            .verify()

        verify {
            domainEventCompensator.compensate(
                eventId.aggregateId,
                eventId.version,
                any()
            )
        }
    }

    @Test
    fun onCompensationPreparedWhenStateEvent() {
        val eventId = EventId(GlobalIdGenerator.generateAsString(), LOCAL_AGGREGATE.asAggregateId(), 1)
        val compensationPrepared = CompensationPrepared(eventId, SNAPSHOT_PROCESSOR, FunctionKind.STATE_EVENT)
        val stateEventCompensator = mockk<StateEventCompensator> {
            every {
                compensate(
                    eventId.aggregateId,
                    eventId.version,
                    any()
                )
            } returns Mono.empty()
        }
        sagaVerifier<CompensationSaga>()
            .inject(stateEventCompensator)
            .inject(mockk<DomainEventCompensator>())
            .`when`(compensationPrepared)
            .expectNoCommand()
            .verify()

        verify {
            stateEventCompensator.compensate(
                eventId.aggregateId,
                eventId.version,
                any()
            )
        }
    }

    @Test
    fun onCompensationPreparedWhenOther() {
        val eventId = EventId(GlobalIdGenerator.generateAsString(), LOCAL_AGGREGATE.asAggregateId(), 1)
        val compensationPrepared = CompensationPrepared(eventId, SNAPSHOT_PROCESSOR, FunctionKind.ERROR)
        sagaVerifier<CompensationSaga>()
            .inject(mockk<StateEventCompensator>())
            .inject(mockk<DomainEventCompensator>())
            .`when`(compensationPrepared)
            .expectNoCommand()
            .verify()
    }
}