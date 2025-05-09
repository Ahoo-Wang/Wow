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
package me.ahoo.wow.eventsourcing

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

abstract class AbstractEventStore : EventStore {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun append(eventStream: DomainEventStream): Mono<Void> {
        log.debug {
            "Append ${eventStream.aggregateId} - version[${eventStream.version}]"
        }
        return appendStream(eventStream)
            .onErrorMap(EventVersionConflictException::class.java) {
                if (it.eventStream.version == Version.INITIAL_VERSION) {
                    DuplicateAggregateIdException(it.eventStream, cause = it)
                } else {
                    it
                }
            }
    }

    protected abstract fun appendStream(eventStream: DomainEventStream): Mono<Void>
    override fun load(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        log.debug {
            "Load $aggregateId - headVersion[$headVersion] - tailVersion[$tailVersion]."
        }
        require(headVersion > -1) {
            "$aggregateId headVersion[$headVersion] must be greater than -1!"
        }
        require(tailVersion >= headVersion) {
            "$aggregateId headEventTime[$tailVersion] must be greater than or equal to headEventTime[$headVersion]!"
        }
        return loadStream(aggregateId, headVersion, tailVersion)
    }

    override fun load(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        require(tailEventTime >= headEventTime) {
            "$aggregateId headEventTime[$headEventTime] must be greater than or equal to headEventTime[$headEventTime]!"
        }
        return loadStream(aggregateId, headEventTime, tailEventTime)
    }

    protected abstract fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream>

    protected abstract fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream>
}
