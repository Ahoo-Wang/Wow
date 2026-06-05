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

package me.ahoo.wow.modeling.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.stateAggregateMetadata
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class StateAggregateFactoryBehaviorTest {

    @Test
    fun `constructor factory creates state roots using supported constructor shapes`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1", tenantId = "tenant-1")

        ConstructorStateAggregateFactory.create(stateAggregateMetadata<NoArgState>(), aggregateId)
            .state.assert().isInstanceOf(NoArgState::class.java)

        ConstructorStateAggregateFactory.create(stateAggregateMetadata<IdState>(), aggregateId)
            .state.id.assert().isEqualTo("aggregate-1")

        ConstructorStateAggregateFactory.create(stateAggregateMetadata<TenantState>(), aggregateId)
            .state.tenantId.assert().isEqualTo("tenant-1")
    }

    @Test
    fun `constructor factory createAsMono defers state aggregate creation`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1")

        StepVerifier.create(
            ConstructorStateAggregateFactory.createAsMono(stateAggregateMetadata<IdState>(), aggregateId)
        )
            .assertNext {
                it.state.id.assert().isEqualTo("aggregate-1")
                it.version.assert().isEqualTo(Version.UNINITIALIZED_VERSION)
            }
            .verifyComplete()
    }

    @Test
    fun `explicit state factory method copies aggregate metadata and wires read only awareness`() {
        val metadata = stateAggregateMetadata<AwareState>()
        val aggregateId = MOCK_AGGREGATE_METADATA.namedAggregate.aggregateId("aggregate-1")
        val state = AwareState("aggregate-1")

        val aggregate = ConstructorStateAggregateFactory.create(
            metadata = metadata,
            aggregateId = aggregateId,
            state = state,
            version = 7,
            ownerId = "owner-1",
            spaceId = "space-1",
            deleted = true,
        )

        aggregate.version.assert().isEqualTo(7)
        aggregate.ownerId.assert().isEqualTo("owner-1")
        aggregate.spaceId.assert().isEqualTo("space-1")
        aggregate.deleted.assert().isTrue()
        state.readOnlyStateAggregate.assert().isSameAs(aggregate)
    }
}
