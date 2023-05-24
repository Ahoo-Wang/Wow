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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import org.slf4j.LoggerFactory

class VersionOffsetSnapshotStrategy(
    private val versionOffset: Int,
    snapshotRepository: SnapshotRepository,
    eventStore: EventStore,
    stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
) : SimpleSnapshotStrategy(
    { snapshot, eventStream ->
        val currentVersionOffset = (eventStream.version - snapshot.version)
        val matched = currentVersionOffset >= versionOffset

        if (log.isDebugEnabled) {
            log.debug(
                "[{}] Current version offset:[{}] - expected offset:[{}] matched:[{}].",
                eventStream.aggregateId,
                currentVersionOffset,
                versionOffset,
                matched,
            )
        }
        matched
    },
    snapshotRepository,
    eventStore,
    stateAggregateFactory,
) {
    companion object {
        private val log = LoggerFactory.getLogger(VersionOffsetSnapshotStrategy::class.java)
    }
}

class TimeOffsetSnapshotStrategy(
    private val timeOffset: Long,
    snapshotRepository: SnapshotRepository,
    eventStore: EventStore,
    stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
) : SimpleSnapshotStrategy(
    { snapshot, eventStream ->
        val currentTimeOffset = (eventStream.createTime - snapshot.snapshotTime)
        val matched = currentTimeOffset >= timeOffset

        if (log.isDebugEnabled) {
            log.debug(
                "[{}] Current time offset:[{}] - expected offset:[{}] matched:[{}].",
                eventStream.aggregateId,
                currentTimeOffset,
                timeOffset,
                matched,
            )
        }
        matched
    },
    snapshotRepository,
    eventStore,
    stateAggregateFactory,
) {
    companion object {
        private val log = LoggerFactory.getLogger(TimeOffsetSnapshotStrategy::class.java)
    }
}
