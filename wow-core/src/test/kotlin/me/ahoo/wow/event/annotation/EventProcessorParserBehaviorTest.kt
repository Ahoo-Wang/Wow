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

package me.ahoo.wow.event.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test

class EventProcessorParserBehaviorTest {

    @Test
    fun `processor parser finds event and state event handlers`() {
        val metadata = eventProcessorMetadata<FixtureEventProcessor>()
        val functions = metadata.functionRegistry

        metadata.processorType.assert().isEqualTo(FixtureEventProcessor::class.java)
        functions.size.assert().isEqualTo(2)
        functions.map { it.functionKind }.toSet().assert().isEqualTo(
            setOf(FunctionKind.EVENT, FunctionKind.STATE_EVENT)
        )
        functions.map { it.supportedType }.toSet().assert().isEqualTo(
            setOf(MockAggregateCreated::class.java, MockAggregateChanged::class.java)
        )
    }
}

private class FixtureEventProcessor {
    fun onEvent(created: MockAggregateCreated) = Unit

    @OnStateEvent
    fun onChanged(changed: MockAggregateChanged) = Unit

    fun ignored(value: String) = Unit
}
