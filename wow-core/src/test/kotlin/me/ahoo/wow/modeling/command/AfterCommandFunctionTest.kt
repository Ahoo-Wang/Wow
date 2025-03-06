package me.ahoo.wow.modeling.command

import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.messaging.function.toMessageFunction
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class AfterCommandFunctionTest {
    private val funMetadata =
        MockAfterCommandAggregate::afterCommand.toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
    private val messageFunction = funMetadata.toMessageFunction(MockAfterCommandAggregate(generateGlobalId()))
    private val afterCommandFunction = AfterCommandFunction(messageFunction)

    @Test
    fun matchCommand() {
        assertThat(afterCommandFunction.matchCommand(CreateCmd::class.java), equalTo(true))
    }

    @Test
    fun mergeEvents() {
        assertThat(AfterCommandFunction.mergeEvents(Any(), Any()).size, equalTo(2))
        assertThat(AfterCommandFunction.mergeEvents(listOf(Any()), Any()).size, equalTo(2))
        assertThat(AfterCommandFunction.mergeEvents(arrayOf(Any()), Any()).size, equalTo(2))
    }

    @Test
    fun getDelegate() {
        assertThat(afterCommandFunction.delegate, sameInstance(messageFunction))
    }
}
