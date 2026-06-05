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

package me.ahoo.wow.serialization.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

internal class StateAggregateSerializationBehaviorTest {

    @Test
    fun `state aggregate should round trip through WowModule serializer`() {
        val stateAggregate = stateAggregate()

        val decoded = stateAggregate.toJsonString().toObject<StateAggregate<*>>()

        decoded.aggregateId.assert().isEqualTo(stateAggregate.aggregateId)
        decoded.version.assert().isEqualTo(stateAggregate.version)
        decoded.state.assert().isEqualTo(stateAggregate.state)
    }

    @Test
    fun `snapshot should round trip state aggregate data and snapshot time`() {
        val snapshot: Snapshot<MockStateAggregate> = SimpleSnapshot(
            delegate = stateAggregate(),
            snapshotTime = 123456789L,
        )

        val decoded = snapshot.toJsonString().toObject<Snapshot<*>>()

        decoded.assert().isEqualTo(snapshot)
        decoded.snapshotTime.assert().isEqualTo(123456789L)
    }

    private fun stateAggregate(): StateAggregate<MockStateAggregate> {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1", tenantId = "tenant-1")
        return ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
    }
}
