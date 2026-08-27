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
import me.ahoo.wow.api.Version.Companion.UNINITIALIZED_VERSION
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class SnapshotStoreNamingTest {

    @Test
    fun `no op snapshot store should expose stable name and empty behavior`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("no-op-snapshot-store")
        val stateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(
            MockStateAggregate(aggregateId.id),
            version = 1,
        )

        NoOpSnapshotStore.name.assert().isEqualTo("no_op")
        StepVerifier.create(NoOpSnapshotStore.getVersion(aggregateId))
            .expectNext(UNINITIALIZED_VERSION)
            .verifyComplete()
        StepVerifier.create(NoOpSnapshotStore.load<Any>(aggregateId))
            .verifyComplete()
        StepVerifier.create(NoOpSnapshotStore.save(SimpleSnapshot(stateAggregate)))
            .verifyComplete()
    }

    @Test
    fun `in memory snapshot store should save and load snapshots`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("in-memory-snapshot-store")
        val stateAggregate = MOCK_AGGREGATE_METADATA.toStateAggregate(
            MockStateAggregate(aggregateId.id),
            version = 1,
        )
        val snapshotStore = InMemorySnapshotStore()

        StepVerifier.create(snapshotStore.save(SimpleSnapshot(stateAggregate)))
            .verifyComplete()
        StepVerifier.create(snapshotStore.load<MockStateAggregate>(aggregateId))
            .assertNext {
                it.aggregateId.assert().isEqualTo(aggregateId)
                it.state.id.assert().isEqualTo(aggregateId.id)
                it.version.assert().isEqualTo(1)
            }
            .verifyComplete()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated snapshot store names should remain source compatible`() {
        val store: SnapshotRepository = InMemorySnapshotRepository()
        val noOp: SnapshotRepository = NoOpSnapshotRepository

        store.name.assert().isEqualTo(InMemorySnapshotStore.NAME)
        noOp.name.assert().isEqualTo(NoOpSnapshotStore.NAME)
        NoOpSnapshotRepository.NAME.assert().isEqualTo(NoOpSnapshotStore.NAME)
    }
}
