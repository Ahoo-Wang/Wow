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

const val DEFAULT_VERSION_OFFSET = 5

class VersionOffsetSnapshotStrategy(
    private val versionOffset: Int = DEFAULT_VERSION_OFFSET,
    private val snapshotRepository: SnapshotRepository
) : SnapshotStrategy {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(VersionOffsetSnapshotStrategy::class.java)
    }

    override fun onEvent(stateEventExchange: StateEventExchange<*>): Mono<Void> {
        val stateEvent = stateEventExchange.message
        return snapshotRepository.getVersion(stateEvent.aggregateId)
            .flatMap { currentVersion ->
                val currentVersionOffset = (stateEvent.version - currentVersion)
                val matched = currentVersionOffset >= versionOffset
                if (log.isDebugEnabled) {
                    log.debug(
                        "[{}] Current version offset:[{}] - expected offset:[{}] matched:[{}].",
                        stateEvent.aggregateId,
                        currentVersionOffset,
                        versionOffset,
                        matched,
                    )
                }
                if (!matched) {
                    return@flatMap Mono.empty<Void>()
                }
                val snapshot = SimpleSnapshot(stateEvent)
                snapshotRepository.save(snapshot)
            }
    }
}
