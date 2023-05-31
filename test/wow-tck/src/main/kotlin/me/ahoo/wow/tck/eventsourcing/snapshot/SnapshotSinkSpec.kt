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

import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotSink
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

abstract class SnapshotSinkSpec {

    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA
    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
    lateinit var snapshotSink: SnapshotSink

    @BeforeEach
    fun setup() {
        this.snapshotSink = createSnapshotSink()
    }

    @AfterEach
    fun destroy() {
        if (this::snapshotSink.isInitialized) {
            this.snapshotSink.close()
        }
    }

    abstract fun createSnapshotSink(): SnapshotSink

    @Test
    fun sink() {
        val stateAggregate =
            stateAggregateFactory.create(
                aggregateMetadata.state,
                aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString()),
            ).block()!!
        val snapshot = SimpleSnapshot(stateAggregate)
        snapshotSink.sink(snapshot).block()
    }
}
