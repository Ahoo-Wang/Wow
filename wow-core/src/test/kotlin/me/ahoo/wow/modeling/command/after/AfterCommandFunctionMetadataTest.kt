package me.ahoo.wow.modeling.command.after

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.MockDefaultAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.UpdateCmd
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunctionMetadata
import org.junit.jupiter.api.Test

class AfterCommandFunctionMetadataTest {
    private val afterCommandFunctionMetadata = MockAfterCommandAggregate::onAfterCommand
        .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
        .toAfterCommandFunctionMetadata()

    @Test
    fun getInclude() {
        afterCommandFunctionMetadata.include.assert().containsExactly(CreateCmd::class.java)
    }

    @Test
    fun getExclude() {
        afterCommandFunctionMetadata.exclude.assert().containsExactly(UpdateCmd::class.java)
    }

    @Test
    fun supportCommand() {
        afterCommandFunctionMetadata.supportCommand(CreateCmd::class.java).assert().isEqualTo(true)
        afterCommandFunctionMetadata.supportCommand(UpdateCmd::class.java).assert().isEqualTo(false)
    }

    @Test
    fun supportCommandEmpty() {
        val funMetadata = MockDefaultAfterCommandAggregate::afterCommand
            .toMonoFunctionMetadata<MockDefaultAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()
        funMetadata.supportCommand(CreateCmd::class.java).assert().isEqualTo(true)
    }

    @Test
    fun getFunction() {
        afterCommandFunctionMetadata.function.functionKind.assert().isEqualTo(FunctionKind.COMMAND)
    }

    @Test
    fun getOrder() {
        afterCommandFunctionMetadata.order.value.assert().isEqualTo(0)
    }

    @Test
    fun fistOrder() {
        val funMetadata = MockAfterCommandAggregate::firstAfterCommand
            .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()
        funMetadata.order.value.assert().isEqualTo(ORDER_FIRST)
    }
}
