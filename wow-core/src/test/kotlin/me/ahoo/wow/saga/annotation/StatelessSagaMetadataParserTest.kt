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

@file:Suppress("unused")

package me.ahoo.wow.saga.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test

class StatelessSagaMetadataParserTest {

    @Test
    fun `parser includes event and state event functions only`() {
        val metadata = statelessSagaMetadata<FixtureStatelessSaga>()
        val functions = metadata.functionRegistry

        metadata.name.assert().isEqualTo("FixtureStatelessSaga")
        metadata.processorType.assert().isEqualTo(FixtureStatelessSaga::class.java)
        functions.associate {
            it.name to (it.functionKind to it.supportedType)
        }.assert().isEqualTo(
            mapOf(
                "onEvent" to (FunctionKind.EVENT to MockAggregateCreated::class.java),
                "annotatedEvent" to (FunctionKind.EVENT to MockAggregateChanged::class.java),
                "onStateEvent" to (FunctionKind.STATE_EVENT to MockAggregateChanged::class.java),
                "annotatedStateEvent" to (FunctionKind.STATE_EVENT to MockAggregateCreated::class.java),
            )
        )
    }
}

private class FixtureStatelessSaga {
    fun onEvent(created: MockAggregateCreated) = Unit

    @OnEvent
    fun annotatedEvent(changed: MockAggregateChanged) = Unit

    fun onStateEvent(changed: MockAggregateChanged) = Unit

    @OnStateEvent
    fun annotatedStateEvent(created: MockAggregateCreated) = Unit

    fun ignored(value: String) = Unit
}
