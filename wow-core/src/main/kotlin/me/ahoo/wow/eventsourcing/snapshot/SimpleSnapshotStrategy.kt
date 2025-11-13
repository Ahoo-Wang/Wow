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

import me.ahoo.wow.eventsourcing.state.StateEventExchange
import reactor.core.publisher.Mono

/**
 * Simple implementation of SnapshotStrategy that creates a snapshot for every state event.
 * This strategy saves a snapshot immediately after each state event is processed.
 *
 * @param snapshotRepository the repository to save snapshots to
 */
class SimpleSnapshotStrategy(
    private val snapshotRepository: SnapshotRepository
) : SnapshotStrategy {
    /**
     * Handles a state event by creating and saving a snapshot.
     *
     * @param stateEventExchange the state event exchange to process
     * @return a Mono that completes when the snapshot is saved
     */
    override fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void> {
        val stateEvent = stateEventExchange.message
        val snapshot = SimpleSnapshot(stateEvent)
        return snapshotRepository.save(snapshot)
    }
}
