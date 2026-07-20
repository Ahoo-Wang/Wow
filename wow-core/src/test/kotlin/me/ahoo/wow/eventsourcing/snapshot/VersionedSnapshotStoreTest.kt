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
import me.ahoo.wow.metrics.MetricSnapshotStore
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.test.StepVerifier

class VersionedSnapshotStoreTest {
    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("versioned-snapshot")

    @Test
    fun `load at or before chooses the greatest eligible checkpoint`() {
        val store: VersionedSnapshotStore = InMemorySnapshotStore()
        listOf(5, 10, 15).forEach { version ->
            store.saveCheckpoint(snapshot(version, "v$version")).block()
        }

        StepVerifier.create(store.loadAtOrBefore<MockStateAggregate>(aggregateId, 12))
            .assertNext { checkpoint ->
                checkpoint.version.assert().isEqualTo(10)
                checkpoint.state.data.assert().isEqualTo("v10")
            }
            .verifyComplete()
        StepVerifier.create(store.loadAtOrBefore<MockStateAggregate>(aggregateId, 4))
            .verifyComplete()
    }

    @Test
    fun `checkpoint writes are first wins and do not replace latest snapshots`() {
        val store: VersionedSnapshotStore = InMemorySnapshotStore()
        store.save(snapshot(20, "latest")).block()
        store.saveCheckpoint(snapshot(10, "first")).block()
        store.saveCheckpoint(snapshot(10, "replacement")).block()

        StepVerifier.create(store.loadAtOrBefore<MockStateAggregate>(aggregateId, 10))
            .assertNext { checkpoint ->
                checkpoint.state.data.assert().isEqualTo("first")
            }
            .verifyComplete()
        StepVerifier.create(store.load<MockStateAggregate>(aggregateId))
            .assertNext { latest ->
                latest.version.assert().isEqualTo(20)
                latest.state.data.assert().isEqualTo("latest")
            }
            .verifyComplete()
    }

    @Test
    fun `routing and metric decorators preserve checkpoint operations`() {
        val routing = RoutingSnapshotStore(
            AggregateSnapshotStoreRegistry(
                defaultSnapshotStore = InMemorySnapshotStore(),
                routes = emptyMap(),
            ),
        )
        val store: VersionedSnapshotStore = MetricSnapshotStore(routing)

        store.saveCheckpoint(snapshot(10, "checkpoint")).block()

        StepVerifier.create(store.loadAtOrBefore<MockStateAggregate>(aggregateId, 10))
            .assertNext { checkpoint ->
                checkpoint.version.assert().isEqualTo(10)
                checkpoint.state.data.assert().isEqualTo("checkpoint")
            }
            .verifyComplete()
    }

    @Test
    fun `rejects invalid checkpoint versions`() {
        val store = InMemorySnapshotStore()

        assertThrows<IllegalArgumentException> {
            store.loadAtOrBefore<MockStateAggregate>(aggregateId, -1)
        }
        assertThrows<IllegalArgumentException> {
            store.saveCheckpoint(snapshot(0, "invalid"))
        }
    }

    private fun snapshot(version: Int, data: String): Snapshot<MockStateAggregate> {
        val state = MockStateAggregate(aggregateId.id)
        state.javaClass.getDeclaredField("data").apply {
            isAccessible = true
            set(state, data)
        }
        val aggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = version,
        )
        return SimpleSnapshot(aggregate, snapshotTime = version.toLong())
    }
}
