package me.ahoo.wow.webflux.route.state

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.command.IllegalAccessOwnerAggregateException
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.server.ServerRequest

class OwnerAggregatePreconditionTest {

    @Test
    fun checkNever() {
        val request = mockk<ServerRequest>()
        val stateAggregate = mockk<StateAggregate<Any>>()
        OwnerAggregatePrecondition(request, AggregateRoute.Owner.NEVER).check(stateAggregate)
    }

    @Test
    fun check() {
        val customerId = generateGlobalId()
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.OWNER_ID] } returns customerId
        }

        val stateAggregate = mockk<StateAggregate<Any>> {
            every { ownerId } returns customerId
        }

        OwnerAggregatePrecondition(request, AggregateRoute.Owner.ALWAYS).check(stateAggregate)
    }

    @Test
    fun checkFailed() {
        val request = mockk<ServerRequest> {
            every { pathVariables()[MessageRecords.OWNER_ID] } returns generateGlobalId()
        }

        val stateAggregate = mockk<StateAggregate<Any>> {
            every { ownerId } returns generateGlobalId()
            every { aggregateId } returns "test.test".toNamedAggregate().aggregateId()
        }
        Assertions.assertThrows(IllegalAccessOwnerAggregateException::class.java) {
            OwnerAggregatePrecondition(request, AggregateRoute.Owner.ALWAYS).check(stateAggregate)
        }
    }
}
