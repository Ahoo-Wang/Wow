package me.ahoo.wow.messaging.compensation

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class EventCompensateSupporterTest {

    @Test
    fun compensateEvent() {
        val domainEventCompensator = mockk<DomainEventCompensator> {
            every { compensate(any(), any(), any()) } returns Mono.just(1)
        }
        val eventCompensateSupporter = EventCompensateSupporter(domainEventCompensator, mockk())
        val aggregateId = mockk<AggregateId>()
        val target = mockk<CompensationTarget> {
            every { function } returns mockk {
                every { functionKind } returns FunctionKind.EVENT
            }
        }
        eventCompensateSupporter.compensate(aggregateId, 1, target).test()
            .expectNext(1)
            .verifyComplete()
    }

    @Test
    fun compensateStateEvent() {
        val stateEventCompensator = mockk<StateEventCompensator> {
            every { compensate(any(), any(), any()) } returns Mono.just(1)
        }
        val eventCompensateSupporter = EventCompensateSupporter(mockk(), stateEventCompensator)
        val aggregateId = mockk<AggregateId>()
        val target = mockk<CompensationTarget> {
            every { function } returns mockk {
                every { functionKind } returns FunctionKind.STATE_EVENT
            }
        }
        eventCompensateSupporter.compensate(aggregateId, 1, target).test()
            .expectNext(1)
            .verifyComplete()
    }

    @Test
    fun compensateIfWrongFunctionKind() {
        val eventCompensateSupporter = EventCompensateSupporter(mockk(), mockk())
        eventCompensateSupporter.compensate(
            mockk(),
            1,
            mockk {
                every { function } returns mockk {
                    every { functionKind } returns FunctionKind.COMMAND
                }
            }
        ).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }
}
