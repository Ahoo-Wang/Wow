package me.ahoo.wow.saga.stateless

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.SagaVerifier
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class StatelessSagaFunctionRegistrarTest {

    @Test
    fun register() {
        val functionRegistrar = StatelessSagaFunctionRegistrar(SagaVerifier.defaultCommandGateway())
        functionRegistrar.registerProcessor(MockSaga())
        assertThat(functionRegistrar.getFunctions(MockAggregateCreated::class.java), hasSize(1))
    }
}

@StatelessSaga
class MockSaga {

    @Suppress("UnusedParameter")
    @OnEvent
    fun onCreated(event: MockAggregateCreated) = Unit
}
