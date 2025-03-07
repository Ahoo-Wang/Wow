package me.ahoo.wow.modeling.command.after

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.MockDefaultAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.UpdateCmd
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunctionMetadata
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class AfterCommandFunctionMetadataTest {
    private val afterCommandFunctionMetadata = MockAfterCommandAggregate::onAfterCommand
        .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
        .toAfterCommandFunctionMetadata()

    @Test
    fun getInclude() {
        assertThat(afterCommandFunctionMetadata.include, containsInAnyOrder(CreateCmd::class.java))
    }

    @Test
    fun getExclude() {
        assertThat(afterCommandFunctionMetadata.exclude, containsInAnyOrder(UpdateCmd::class.java))
    }

    @Test
    fun supportCommand() {
        assertThat(afterCommandFunctionMetadata.supportCommand(CreateCmd::class.java), equalTo(true))
        assertThat(afterCommandFunctionMetadata.supportCommand(UpdateCmd::class.java), equalTo(false))
    }

    @Test
    fun supportCommandEmpty() {
        val funMetadata = MockDefaultAfterCommandAggregate::afterCommand
            .toMonoFunctionMetadata<MockDefaultAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()
        assertThat(funMetadata.supportCommand(CreateCmd::class.java), equalTo(true))
    }

    @Test
    fun getFunction() {
        assertThat(afterCommandFunctionMetadata.function.functionKind, equalTo(FunctionKind.COMMAND))
    }

    @Test
    fun getOrder() {
        assertThat(afterCommandFunctionMetadata.order.value, equalTo(0))
    }

    @Test
    fun fistOrder() {
        val funMetadata = MockAfterCommandAggregate::firstAfterCommand
            .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()
        assertThat(funMetadata.order.value, equalTo(ORDER_FIRST))
    }
}
