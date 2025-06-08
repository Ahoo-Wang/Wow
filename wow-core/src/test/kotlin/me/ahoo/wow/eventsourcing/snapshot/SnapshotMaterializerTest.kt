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
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

class SnapshotMaterializerTest {
    val stateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(MockStateAggregate("1"), 1)
    val snapshot = SimpleSnapshot(stateAggregate)

    @Test
    fun materialize() {
        val materializedSnapshot = snapshot.materialize {
            it
        }
        materializedSnapshot.contextName.assert().isEqualTo(snapshot.aggregateId.contextName)
        materializedSnapshot.aggregateName.assert().isEqualTo(snapshot.aggregateId.aggregateName)
        materializedSnapshot.tenantId.assert().isEqualTo(snapshot.aggregateId.tenantId)
        materializedSnapshot.aggregateId.assert().isEqualTo(snapshot.aggregateId.id)
        materializedSnapshot.version.assert().isEqualTo(snapshot.version)
        materializedSnapshot.eventId.assert().isEqualTo(snapshot.eventId)
        materializedSnapshot.firstOperator.assert().isEqualTo(snapshot.firstOperator)
        materializedSnapshot.operator.assert().isEqualTo(snapshot.operator)
        materializedSnapshot.firstEventTime.assert().isEqualTo(snapshot.firstEventTime)
        materializedSnapshot.eventTime.assert().isEqualTo(snapshot.eventTime)
        materializedSnapshot.state.assert().isEqualTo(snapshot.state)
        materializedSnapshot.snapshotTime.assert().isEqualTo(snapshot.snapshotTime)
        materializedSnapshot.deleted.assert().isEqualTo(snapshot.deleted)
    }

    @Test
    fun toSmall() {
        val smallSnapshot = snapshot.toSmall { it }
        smallSnapshot.version.assert().isEqualTo(snapshot.version)
        smallSnapshot.firstEventTime.assert().isEqualTo(snapshot.firstEventTime)
        smallSnapshot.state.assert().isEqualTo(snapshot.state)
    }

    @Test
    fun toMedium() {
        val mediumSnapshot = snapshot.toMedium { it }
        mediumSnapshot.tenantId.assert().isEqualTo(snapshot.aggregateId.tenantId)
        mediumSnapshot.ownerId.assert().isEqualTo(snapshot.ownerId)
        mediumSnapshot.version.assert().isEqualTo(snapshot.version)
        mediumSnapshot.eventId.assert().isEqualTo(snapshot.eventId)
        mediumSnapshot.firstOperator.assert().isEqualTo(snapshot.firstOperator)
        mediumSnapshot.operator.assert().isEqualTo(snapshot.operator)
        mediumSnapshot.firstEventTime.assert().isEqualTo(snapshot.firstEventTime)
        mediumSnapshot.eventTime.assert().isEqualTo(snapshot.eventTime)
        mediumSnapshot.state.assert().isEqualTo(snapshot.state)
    }
}
