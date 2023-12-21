package me.ahoo.wow.modeling.command

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.annotation.Retry
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DefaultDeleteAggregateFunctionTest {

    @Test
    fun getAnnotation() {
        val commandAggregate = mockk<CommandAggregate<Any, Any>> {
            every { contextName } returns "context"
            every { commandRoot } returns "root"
        }
        assertThat(DefaultDeleteAggregateFunction(commandAggregate).getAnnotation(Retry::class.java), nullValue())
    }
}
