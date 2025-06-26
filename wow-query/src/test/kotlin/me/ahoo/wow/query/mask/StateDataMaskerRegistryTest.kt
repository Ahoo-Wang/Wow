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
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test

class StateDataMaskerRegistryTest {

    @Test
    fun main() {
        val stateDataMaskerRegistry = StateDataMaskerRegistry()
        val masker = MockStateDataMasker(MOCK_AGGREGATE_METADATA)
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
    fun getEmpty() {
        val stateDataMaskerRegistry = StateDataMaskerRegistry()
        val namedAggregate = "${generateGlobalId()}.${generateGlobalId()}".toNamedAggregate()
        val aggregateDataMasker = stateDataMaskerRegistry.getAggregateDataMasker(namedAggregate)
        aggregateDataMasker.maskers.assert().isEmpty()
    }
}
