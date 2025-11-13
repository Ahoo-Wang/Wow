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
 * Strategy for managing snapshots of state aggregates.
 * Snapshot strategies determine when and how to create snapshots based on state events.
 * This helps optimize aggregate loading by reducing the number of events that need to be replayed.
 *
 * Implementations can define custom logic for snapshot creation, such as:
 * - Taking snapshots after a certain number of events
 * - Taking snapshots at specific time intervals
 * - Taking snapshots based on aggregate state changes
 *
 * @author ahoo wang
 */
interface SnapshotStrategy {
    /**
     * Processes a state event exchange to determine if a snapshot should be created.
     * Implementations should analyze the event and potentially trigger snapshot creation.
     *
     * @param stateEventExchange The state event exchange containing the event and aggregate state.
     * @return A Mono that completes when the snapshot strategy processing is done.
     */
    fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void>

    /**
     * No-operation implementation of SnapshotStrategy that never creates snapshots.
     * Useful for testing or when snapshots are not needed.
     */
    companion object NoOp : SnapshotStrategy {
        /**
         * Does nothing and returns an empty Mono.
         *
         * @param stateEventExchange The state event exchange (ignored).
         * @return An empty Mono indicating no action taken.
         */
        override fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void> = Mono.empty()
    }
}
