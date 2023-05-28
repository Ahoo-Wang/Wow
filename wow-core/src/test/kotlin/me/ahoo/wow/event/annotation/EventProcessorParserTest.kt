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

import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

internal class EventProcessorParserTest {

    @Test
    fun eventProcessorMetadata() {
        val eventProcessorMetadata = eventProcessorMetadata<MockEventProcessor>()
        assertThat(eventProcessorMetadata.contextName, equalTo("wow-core-test"))
        assertThat(eventProcessorMetadata.processorType, equalTo(MockEventProcessor::class.java))
        assertThat(
            eventProcessorMetadata.functionRegistry.map { it.supportedType }.toSet(),
            hasItems(MockAggregateCreated::class.java, MockAggregateChanged::class.java),
        )
    }
}

internal class MockEventProcessor {
    fun onEvent(created: MockAggregateCreated) = Unit
    fun onEvent(changed: MockAggregateChanged) = Unit
}
