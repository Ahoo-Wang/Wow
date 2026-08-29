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

package me.ahoo.wow.query.mask

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

class StateObjectNodeMaskerRegistryTest {
    @Test
    fun `registries should isolate state and event stream maskers`() {
        val stateRegistry = StateObjectNodeMaskerRegistry()
        val eventRegistry = EventStreamObjectNodeMaskerRegistry()
        val state = object : StateObjectNodeMasker {
            override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
            override fun mask(node: ObjectNode): ObjectNode = node
        }
        val event = object : EventStreamObjectNodeMasker {
            override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
            override fun mask(node: ObjectNode): ObjectNode = node
        }

        stateRegistry.register(state)
        eventRegistry.register(event)
        stateRegistry.getMasker(MOCK_AGGREGATE_METADATA).maskers.single().assert().isSameAs(state)
        eventRegistry.getMasker(MOCK_AGGREGATE_METADATA).maskers.single().assert().isSameAs(event)

        stateRegistry.unregister(state)
        eventRegistry.unregister(event)
        stateRegistry.getMasker(MOCK_AGGREGATE_METADATA).maskers.assert().isEmpty()
        eventRegistry.getMasker(MOCK_AGGREGATE_METADATA).maskers.assert().isEmpty()
    }
}
