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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.infra.accessor.method.SimpleMethodAccessor
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.tck.modeling.ChangeAggregate
import me.ahoo.wow.tck.modeling.CreateAggregate
import me.ahoo.wow.tck.modeling.MockAggregate
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.arrayWithSize
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class MethodFunctionMetadataTest {

    @Test
    fun asHandlerMetadata() {
        val metadata = MockAggregate::class.java.getDeclaredMethod(
            "onCommand",
            CreateAggregate::class.java,
        ).asFunctionMetadata<MockAggregate, Any>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.supportedType,
            equalTo(
                CreateAggregate::class.java,
            ),
        )
        assertThat(metadata.injectParameterTypes, arrayWithSize(0))
        assertThat(
            metadata.accessor,
            instanceOf(
                SimpleMethodAccessor::class.java,
            ),
        )
        assertThat(metadata.firstParameterKind, equalTo(FirstParameterKind.MESSAGE_BODY))
        assertThat(metadata.supportedTopics, hasSize(0))
    }

    @Test
    fun asEventHandlerMetadata() {
        val metadata =
            MockFunction::class.java.getDeclaredMethod("onEvent", Body::class.java)
                .asFunctionMetadata<Any, Any>()
        assertThat(
            metadata.supportedTopics,
            hasItem(MaterializedNamedAggregate("wow-core-test", "messaging_aggregate")),
        )
    }

    @Test
    fun asEventHandlerMetadataWithMultiAggregate() {
        val metadata =
            MockWithMultiAggregateNameFunction::class.java.getDeclaredMethod("onEvent", Body::class.java)
                .asFunctionMetadata<Any, Any>()
        assertThat(metadata.supportedTopics, hasItem(MaterializedNamedAggregate("wow-core-test", "aggregate1")))
        assertThat(metadata.supportedTopics, hasItem(MaterializedNamedAggregate("wow-core-test", "aggregate2")))
    }

    @Test
    fun asHandlerMetadataWhenWrapped() {
        val metadata =
            MockAggregate::class.java.getDeclaredMethod("onCommand", CommandMessage::class.java)
                .asFunctionMetadata<MockAggregate, Any>()
        assertThat(metadata, notNullValue())
        assertThat(
            metadata.supportedType,
            equalTo(
                ChangeAggregate::class.java,
            ),
        )
        assertThat(metadata.injectParameterTypes, arrayWithSize(0))
        assertThat(metadata.firstParameterKind, equalTo(FirstParameterKind.MESSAGE))
    }

    @Test
    fun asHandlerMetadataWithNoneParameter() {
        Assertions.assertThrows(IllegalStateException::class.java) {
            MethodFunctionMetadataTest::class.java.getDeclaredMethod("asHandlerMetadataWithNoneParameter")
                .asFunctionMetadata<MethodFunctionMetadataTest, Any>()
        }
    }
}
