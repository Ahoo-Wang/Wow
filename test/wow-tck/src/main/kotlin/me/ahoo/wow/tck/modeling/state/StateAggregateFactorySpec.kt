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
package me.ahoo.wow.tck.modeling.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

abstract class StateAggregateFactorySpec {

    protected abstract fun createStateAggregateFactory(): StateAggregateFactory

    private fun <C : Any, S : Any> createStateAggregate(
        aggregateMetadata: AggregateMetadata<C, S>,
        verify: (StateAggregate<S>) -> Unit = {}
    ) {
        val aggregateFactory = createStateAggregateFactory()
        val aggregateId = aggregateMetadata.aggregateId(id = GlobalIdGenerator.generateAsString())
        aggregateFactory.createAsMono(aggregateMetadata.state, aggregateId)
            .test()
            .consumeNextWith { stateAggregate: StateAggregate<S> ->
                stateAggregate.assert().isNotNull()
                stateAggregate.aggregateId.id.assert().isEqualTo(aggregateId.id)
                verify(stateAggregate)
            }
            .verifyComplete()
    }

    @Test
    fun create() {
        val aggregateMetadata = aggregateMetadata<MockCommandAggregate, MockStateAggregate>()
        createStateAggregate(aggregateMetadata) {
            it.state.id.assert().isEqualTo(it.aggregateId.id)
        }
    }

    @Test
    fun createWithTenantId() {
        val aggregateMetadata = aggregateMetadata<MockCommandAggregateWithTenantId, MockStateAggregateWithTenantId>()
        createStateAggregate(aggregateMetadata) {
            it.state.id.assert().isEqualTo(it.aggregateId.id)
            it.state.tenantId.assert().isEqualTo(it.aggregateId.tenantId)
        }
    }
}

class MockCommandAggregateWithTenantId(val state: MockStateAggregateWithTenantId)

data class MockStateAggregateWithTenantId(
    override val id: String,
    override val tenantId: String
) : Identifier, TenantId
