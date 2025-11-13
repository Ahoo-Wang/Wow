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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import reactor.core.publisher.Mono

/**
 * Default version offset for snapshot creation.
 * Snapshots are created when the version difference reaches this threshold.
 */
const val DEFAULT_VERSION_OFFSET = 5

/**
 * Snapshot strategy that creates snapshots based on version offset.
 * A snapshot is created when the current aggregate version exceeds the last snapshot version by the specified offset.
 * This helps balance between snapshot frequency and storage efficiency.
 *
 * @param versionOffset The minimum version difference required to trigger a snapshot (default: DEFAULT_VERSION_OFFSET).
 * @param snapshotRepository The repository to save snapshots to.
 */
class VersionOffsetSnapshotStrategy(
    private val versionOffset: Int = DEFAULT_VERSION_OFFSET,
    private val snapshotRepository: SnapshotRepository
) : SnapshotStrategy {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Processes a state event and creates a snapshot if the version offset threshold is met.
     * Compares the current event version with the last snapshot version to determine if a snapshot should be created.
     *
     * @param stateEventExchange The state event exchange containing the event and aggregate state.
     * @return A Mono that completes when the snapshot operation (if any) is done.
     */
    override fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void> {
        val stateEvent = stateEventExchange.message
        return snapshotRepository.getVersion(stateEvent.aggregateId)
            .flatMap { currentVersion ->
                val currentVersionOffset = (stateEvent.version - currentVersion)
                val matched = currentVersionOffset >= versionOffset
                log.debug {
                    "[${stateEvent.aggregateId}] Current version offset:[$currentVersionOffset] - expected offset:[$versionOffset] matched:[$matched]."
                }
                if (!matched) {
                    return@flatMap Mono.empty<Void>()
                }
                val snapshot = SimpleSnapshot(stateEvent)
                snapshotRepository.save(snapshot)
            }
    }
}
