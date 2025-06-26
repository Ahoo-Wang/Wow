package me.ahoo.wow.messaging.function

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.infra.accessor.function.SimpleFunctionAccessor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.kotlinFunction

class FunctionMetadataParserTest {

    @Test
    fun toCommandFunctionMetadata() {
        @Suppress("UNCHECKED_CAST")
        val onCommandFun = MockCommandAggregate::class.java.getDeclaredMethod(
            "onCommand",
            MockCreateAggregate::class.java,
        ).kotlinFunction as KFunction<Any>
        val metadata = onCommandFun.toFunctionMetadata<MockAggregate, Any>()
        metadata.supportedType.assert().isEqualTo(MockCreateAggregate::class.java)
        metadata.functionKind.assert().isEqualTo(FunctionKind.COMMAND)
        metadata.injectParameters.assert().isEmpty()
        metadata.accessor.assert().isInstanceOf(SimpleFunctionAccessor::class.java)
        metadata.firstParameterKind.assert().isEqualTo(FirstParameterKind.MESSAGE_BODY)
        metadata.supportedTopics.assert().isEmpty()
        metadata.processorType.assert().isEqualTo(MockCommandAggregate::class.java)
        metadata.contextName.assert().isEqualTo("wow-tck")
        metadata.processorName.assert().isEqualTo("MockCommandAggregate")
        metadata.name.assert().isEqualTo("onCommand")
    }

    @Test
    fun toEventFunctionMetadata() {
        val metadata = MockFunction::onEvent.toFunctionMetadata<Any, Any>()
        metadata.supportedTopics.assert().contains(MaterializedNamedAggregate("wow-core-test", "messaging_aggregate"))
        metadata.functionKind.assert().isEqualTo(FunctionKind.EVENT)
    }

    @Test
    fun toEventFunctionMetadataWithMultiAggregate() {
        val metadata = MockWithMultiAggregateNameFunction::onEvent
            .toFunctionMetadata<Any, Any>()
        metadata.supportedTopics.assert().containsExactly(
            MaterializedNamedAggregate("wow.messaging", "aggregate1"),
            MaterializedNamedAggregate("wow.messaging", "aggregate2"),
        )
        metadata.functionKind.assert().isEqualTo(FunctionKind.EVENT)
    }

    @Test
    fun toOnStateEventFunctionMetadata() {
        val metadata = MockOnStateEventFunction::onStateEvent.toFunctionMetadata<Any, Any>()
        metadata.supportedTopics.assert().containsExactly(
            MaterializedNamedAggregate("wow.messaging", "aggregate1")
        )
        metadata.functionKind.assert().isEqualTo(FunctionKind.STATE_EVENT)
    }

    @Test
    fun toFunctionMetadataWhenWrapped() {
        val metadata = MockWithWrappedFunction::onEvent.toFunctionMetadata<MockAggregate, Any>()
        metadata.supportedType.assert().isEqualTo(MockEventBody::class.java)
        metadata.injectParameters.assert().isEmpty()
        metadata.firstParameterKind.assert().isEqualTo(FirstParameterKind.MESSAGE)
        metadata.functionKind.assert().isEqualTo(FunctionKind.EVENT)
    }

    @Test
    fun toFunctionMetadataWithNoneParameter() {
        assertThrownBy<IllegalStateException> {
            FunctionMetadataParserTest::toFunctionMetadataWithNoneParameter.toFunctionMetadata<FunctionMetadataParserTest, Unit>()
        }
    }
}
