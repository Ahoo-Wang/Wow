/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.ahoo.wow.messaging.function

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.infra.accessor.method.SimpleMethodAccessor
import me.ahoo.wow.messaging.function.FunctionMetadataParser.asFunctionMetadata
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.MockAggregate
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class MethodFunctionMetadataTest {

    @Test
    fun asCommandFunctionMetadata() {
        val metadata = MockCommandAggregate::class.java.getDeclaredMethod(
            "onCommand",
            MockCreateAggregate::class.java,
        ).asFunctionMetadata<MockAggregate, Any>()
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
                SimpleMethodAccessor::class.java,
            ),
        )
        assertThat(metadata.firstParameterKind, equalTo(FirstParameterKind.MESSAGE_BODY))
        assertThat(metadata.supportedTopics, hasSize(0))
        assertThat(metadata.processorType, equalTo(MockCommandAggregate::class.java))
        assertThat(metadata.contextName, equalTo("wow-tck"))
        assertThat(metadata.processorName, equalTo("MockCommandAggregate"))
        assertThat(metadata.name, equalTo("MockCommandAggregate.MockCreateAggregate"))
    }

    @Test
    fun asEventFunctionMetadata() {
        val metadata =
            MockFunction::class.java.getDeclaredMethod("onEvent", MockEventBody::class.java)
                .asFunctionMetadata<Any, Any>()
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
    fun asEventFunctionMetadataWithMultiAggregate() {
        val metadata =
            MockWithMultiAggregateNameFunction::class.java.getDeclaredMethod("onEvent", MockEventBody::class.java)
                .asFunctionMetadata<Any, Any>()
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
    fun asOnStateEventFunctionMetadata() {
        val metadata =
            MockOnStateEventFunction::class.java.getDeclaredMethod("onStateEvent", DomainEvent::class.java)
                .asFunctionMetadata<Any, Any>()
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
    fun asFunctionMetadataWhenWrapped() {
        val metadata =
            MockWithWrappedFunction::class.java.getDeclaredMethod("onEvent", DomainEvent::class.java)
                .asFunctionMetadata<MockAggregate, Any>()
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
    fun asFunctionMetadataWithNoneParameter() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            MethodFunctionMetadataTest::class.java.getDeclaredMethod("asFunctionMetadataWithNoneParameter")
                .asFunctionMetadata<MethodFunctionMetadataTest, Any>()
        }
    }
}
