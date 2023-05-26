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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.messaging.function.logErrorResume
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

const val SNAPSHOT_KEY = "__SNAPSHOT__"
fun EventStreamExchange.setSnapshot(snapshot: Snapshot<Any>): EventStreamExchange {
    return setAttribute(SNAPSHOT_KEY, snapshot)
}

fun EventStreamExchange.getSnapshot(): Snapshot<Any>? {
    return getAttribute<Snapshot<Any>>(SNAPSHOT_KEY)
}

val MATCH_ALL: (Snapshot<Any>, DomainEventStream) -> Boolean =
    { _, _ ->
        true
    }

open class SimpleSnapshotStrategy(
    private val matcher: (Snapshot<Any>, DomainEventStream) -> Boolean = MATCH_ALL,
    private val snapshotRepository: SnapshotRepository,
    private val eventStore: EventStore,
    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory
) : SnapshotStrategy {
    companion object {
        private val log = LoggerFactory.getLogger(SimpleSnapshotStrategy::class.java)
    }

    override fun onEvent(eventStreamExchange: EventStreamExchange): Mono<Void> {
        val eventStream = eventStreamExchange.message
        val aggregateId = eventStream.aggregateId

        val aggregateMetadata =
            aggregateId.namedAggregate.asRequiredAggregateType<Any>().asAggregateMetadata<Any, Any>()

        return if (eventStream.isInitialVersion) {
            stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        } else {
            snapshotRepository.load(aggregateId)
        }.map {
            SimpleSnapshot(
                delegate = it,
                snapshotTime = System.currentTimeMillis(),
            )
        }.flatMap {
            sourcing(it, eventStream, aggregateId)
        }.flatMap {
            if (log.isDebugEnabled) {
                log.debug("Save snapshot ${it.aggregateId} version[${it.version}].")
            }
            eventStreamExchange.setSnapshot(it)
            snapshotRepository.save(it).logErrorResume()
        }
    }

    private fun sourcing(
        snapshot: Snapshot<Any>,
        eventStream: DomainEventStream,
        aggregateId: AggregateId
    ): Mono<Snapshot<Any>> {
        if (snapshot.version >= eventStream.version) {
            if (log.isWarnEnabled) {
                log.warn(
                    "Ignore this event stream[${eventStream.id}].The current snapshot[${snapshot.aggregateId}] version:[${snapshot.version}] is greater than or equal to the event stream version[${eventStream.version}].",
                )
            }
            return Mono.empty()
        }
        if (!matcher(snapshot, eventStream)) {
            return Mono.empty()
        }
        if (snapshot.expectedNextVersion == eventStream.version) {
            return Mono.fromCallable {
                snapshot.onSourcing(eventStream)
                snapshot
            }
        }
        return eventStore.load(aggregateId, snapshot.expectedNextVersion, eventStream.version - 1)
            .map {
                snapshot.onSourcing(it)
                snapshot
            }
            .switchIfEmpty(
                IllegalStateException(
                    "$aggregateId No event streams found - headVersion:[${snapshot.expectedNextVersion}] - tailVersion:[${eventStream.version - 1}]",
                ).toMono(),
            )
            .last().map {
                snapshot.onSourcing(eventStream)
                snapshot
            }
    }
}
