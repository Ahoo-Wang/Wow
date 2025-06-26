package me.ahoo.wow.modeling.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.Retry
import org.junit.jupiter.api.Test

class DefaultDeleteAggregateFunctionTest {

    @Test
    fun getAnnotation() {
        val commandAggregate = mockk<CommandAggregate<Any, Any>> {
            every { contextName } returns "context"
            every { aggregateName } returns "aggregate"
            every { commandRoot } returns "root"
        }
        DefaultDeleteAggregateFunction(
            commandAggregate = commandAggregate,
            afterCommandFunctions = emptyList()
        ).getAnnotation(Retry::class.java).assert().isNull()
    }
}
