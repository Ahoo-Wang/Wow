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
package me.ahoo.wow.tck.eventsourcing.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.VersionedSnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

@Suppress("UnnecessaryAbstractClass")
abstract class VersionedSnapshotStoreSpec : SnapshotStoreSpec() {
    abstract override fun createSnapshotStore(): VersionedSnapshotStore

    @Test
    fun loadAtOrBeforeSelectsGreatestEligibleCheckpoint() {
        val store = createSnapshotStore()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        store.saveCheckpoint(snapshot(aggregateId, 5, "v5")).block()
        store.saveCheckpoint(snapshot(aggregateId, 10, "v10")).block()
        store.saveCheckpoint(snapshot(aggregateId, 15, "v15")).block()

        store.loadAtOrBefore<MockStateAggregate>(aggregateId, 12)
            .test()
            .consumeNextWith { checkpoint ->
                checkpoint.version.assert().isEqualTo(10)
                checkpoint.state.data.assert().isEqualTo("v10")
            }
            .verifyComplete()
        store.loadAtOrBefore<MockStateAggregate>(aggregateId, 4)
            .test()
            .verifyComplete()
    }

    @Test
    fun checkpointIsFirstWinsAndDoesNotReplaceLatestSnapshot() {
        val store = createSnapshotStore()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        store.save(snapshot(aggregateId, 20, "latest")).block()
        store.saveCheckpoint(snapshot(aggregateId, 10, "first")).block()
        store.saveCheckpoint(snapshot(aggregateId, 10, "replacement")).block()

        store.loadAtOrBefore<MockStateAggregate>(aggregateId, 10)
            .test()
            .consumeNextWith { checkpoint ->
                checkpoint.state.data.assert().isEqualTo("first")
            }
            .verifyComplete()
        store.load<MockStateAggregate>(aggregateId)
            .test()
            .consumeNextWith { latest ->
                latest.version.assert().isEqualTo(20)
                latest.state.data.assert().isEqualTo("latest")
            }
            .verifyComplete()
    }

    private fun snapshot(
        aggregateId: AggregateId,
        version: Int,
        data: String,
    ): Snapshot<MockStateAggregate> {
        val state = MockStateAggregate(aggregateId.id)
        state.javaClass.getDeclaredField("data").apply {
            isAccessible = true
            set(state, data)
        }
        val aggregate = aggregateMetadata.state.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = version,
        )
        return SimpleSnapshot(aggregate, snapshotTime = version.toLong())
    }
}
