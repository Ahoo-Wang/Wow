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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class StateDataMaskerRegistryTest {

    @Test
    fun `should register and unregister state maskers`() {
        val stateDataMaskerRegistry = StateDataMaskerRegistry()
        val masker = MockStateMasker(MOCK_AGGREGATE_METADATA.namedAggregate)
        stateDataMaskerRegistry.unregister(masker)
        stateDataMaskerRegistry.register(masker)
        val aggregateDataMasker = stateDataMaskerRegistry.getAggregateDataMasker(MOCK_AGGREGATE_METADATA.namedAggregate)
        aggregateDataMasker.maskers.size.assert().isOne()
        aggregateDataMasker.maskers.first().assert().isSameAs(masker)
        stateDataMaskerRegistry.register(masker)
        val aggregateDataMasker2 =
            stateDataMaskerRegistry.getAggregateDataMasker(MOCK_AGGREGATE_METADATA.namedAggregate)
        aggregateDataMasker2.maskers.assert().hasSize(2)
        stateDataMaskerRegistry.unregister(masker)
        stateDataMaskerRegistry.unregister(masker)
    }

    @Test
    fun `should return empty masker for unknown aggregate`() {
        val stateDataMaskerRegistry = StateDataMaskerRegistry()
        val namedAggregate = "${generateGlobalId()}.${generateGlobalId()}".toNamedAggregate()
        val aggregateDataMasker = stateDataMaskerRegistry.getAggregateDataMasker(namedAggregate)
        aggregateDataMasker.maskers.assert().isEmpty()
    }

    @Test
    fun `should register and unregister event stream maskers`() {
        val eventStreamMaskerRegistry = EventStreamMaskerRegistry()
        val masker = MockEventStreamMasker(MOCK_AGGREGATE_METADATA.namedAggregate)
        eventStreamMaskerRegistry.register(masker)
        val aggregateDataMasker = eventStreamMaskerRegistry.getAggregateDataMasker(MOCK_AGGREGATE_METADATA.namedAggregate)
        aggregateDataMasker.maskers.size.assert().isOne()
        eventStreamMaskerRegistry.unregister(masker)
        val aggregateDataMasker2 = eventStreamMaskerRegistry.getAggregateDataMasker(MOCK_AGGREGATE_METADATA.namedAggregate)
        aggregateDataMasker2.maskers.assert().isEmpty()
    }

    private class MockStateMasker(override val namedAggregate: NamedAggregate) : StateDynamicDocumentMasker {
        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
            return dynamicDocument
        }
    }

    private class MockEventStreamMasker(override val namedAggregate: NamedAggregate) : EventStreamDynamicDocumentMasker {
        override fun mask(dynamicDocument: DynamicDocument): DynamicDocument {
            return dynamicDocument
        }
    }
}
