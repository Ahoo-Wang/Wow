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

package me.ahoo.wow.eventsourcing.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class SnapshotMaterializerTest {

    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("snapshot-aggregate")
    private val stateAggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
        aggregateId = aggregateId,
        state = MockStateAggregate("snapshot-aggregate"),
        version = 7,
        ownerId = "owner-1",
        spaceId = "space-1",
        eventId = "event-1",
        firstOperator = "first-operator",
        operator = "operator",
        firstEventTime = 100,
        eventTime = 200,
        deleted = true,
    )
    private val snapshot = SimpleSnapshot(stateAggregate, snapshotTime = 300)

    @Test
    fun `materialize projects full snapshot fields`() {
        val materialized = snapshot.materialize { it.data }

        materialized.contextName.assert().isEqualTo(aggregateId.contextName)
        materialized.aggregateName.assert().isEqualTo(aggregateId.aggregateName)
        materialized.tenantId.assert().isEqualTo(aggregateId.tenantId)
        materialized.ownerId.assert().isEqualTo("owner-1")
        materialized.aggregateId.assert().isEqualTo("snapshot-aggregate")
        materialized.version.assert().isEqualTo(7)
        materialized.eventId.assert().isEqualTo("event-1")
        materialized.firstOperator.assert().isEqualTo("first-operator")
        materialized.operator.assert().isEqualTo("operator")
        materialized.firstEventTime.assert().isEqualTo(100)
        materialized.eventTime.assert().isEqualTo(200)
        materialized.state.assert().isEqualTo(snapshot.state.data)
        materialized.snapshotTime.assert().isEqualTo(300)
        materialized.deleted.assert().isTrue()
    }

    @Test
    fun `small and medium projections expose their documented fields`() {
        val small = snapshot.toSmall { it.id }
        val medium = snapshot.toMedium { it.id }

        small.version.assert().isEqualTo(7)
        small.firstEventTime.assert().isEqualTo(100)
        small.state.assert().isEqualTo("snapshot-aggregate")
        medium.tenantId.assert().isEqualTo(aggregateId.tenantId)
        medium.ownerId.assert().isEqualTo("owner-1")
        medium.spaceId.assert().isEqualTo(snapshot.spaceId)
        medium.version.assert().isEqualTo(7)
        medium.eventId.assert().isEqualTo("event-1")
        medium.firstOperator.assert().isEqualTo("first-operator")
        medium.operator.assert().isEqualTo("operator")
        medium.firstEventTime.assert().isEqualTo(100)
        medium.eventTime.assert().isEqualTo(200)
        medium.state.assert().isEqualTo("snapshot-aggregate")
    }
}
