package me.ahoo.wow.messaging.function

import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.infra.accessor.function.SimpleFunctionAccessor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
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
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.supportedType,
            equalTo(
                MockCreateAggregate::class.java,
            ),
        )
        assertThat(
            metadata.functionKind,
            equalTo(
                FunctionKind.COMMAND
            ),
        )
        assertThat(metadata.injectParameters, arrayWithSize(0))
        assertThat(
            metadata.accessor,
            instanceOf(
                SimpleFunctionAccessor::class.java,
            ),
        )
        assertThat(metadata.firstParameterKind, equalTo(FirstParameterKind.MESSAGE_BODY))
        assertThat(metadata.supportedTopics, hasSize(0))
        assertThat(metadata.processorType, equalTo(MockCommandAggregate::class.java))
        assertThat(metadata.contextName, equalTo("wow-tck"))
        assertThat(metadata.processorName, equalTo("MockCommandAggregate"))
        assertThat(metadata.name, equalTo("onCommand"))
    }

    @Test
    fun toEventFunctionMetadata() {
        val metadata = MockFunction::onEvent.toFunctionMetadata<Any, Any>()
        assertThat(
            metadata.supportedTopics,
            hasItem(MaterializedNamedAggregate("wow-core-test", "messaging_aggregate")),
        )
        assertThat(
            metadata.functionKind,
            equalTo(
                FunctionKind.EVENT,
            ),
        )
    }

    @Test
    fun toEventFunctionMetadataWithMultiAggregate() {
        val metadata = MockWithMultiAggregateNameFunction::onEvent
            .toFunctionMetadata<Any, Any>()
        assertThat(
            metadata.supportedTopics,
            hasItems(
                MaterializedNamedAggregate("wow-core-test", "aggregate1"),
                MaterializedNamedAggregate("wow-core-test", "aggregate2"),
            ),
        )
        assertThat(
            metadata.functionKind,
            equalTo(
                FunctionKind.EVENT
            ),
        )
    }

    @Test
    fun toOnStateEventFunctionMetadata() {
        val metadata = MockOnStateEventFunction::onStateEvent.toFunctionMetadata<Any, Any>()
        assertThat(
            metadata.supportedTopics,
            hasItems(
                MaterializedNamedAggregate("wow-core-test", "aggregate1")
            ),
        )
        assertThat(
            metadata.functionKind,
            equalTo(
                FunctionKind.STATE_EVENT
            ),
        )
    }

    @Test
    fun toFunctionMetadataWhenWrapped() {
        val metadata = MockWithWrappedFunction::onEvent.toFunctionMetadata<MockAggregate, Any>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.supportedType,
            equalTo(
                MockEventBody::class.java,
            ),
        )
        assertThat(metadata.injectParameters, arrayWithSize(0))
        assertThat(metadata.firstParameterKind, equalTo(FirstParameterKind.MESSAGE))
        assertThat(
            metadata.functionKind,
            equalTo(
                FunctionKind.EVENT
            ),
        )
    }

    @Test
    fun toFunctionMetadataWithNoneParameter() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            FunctionMetadataParserTest::toFunctionMetadataWithNoneParameter.toFunctionMetadata<FunctionMetadataParserTest, Unit>()
        }
    }
}
