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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.shouldHandle
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.dispatcher.HandledSignal
import me.ahoo.wow.messaging.writeReceiverGroup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.GroupedFlux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration

private const val SNAPSHOT_PROCESSOR_NAME = "snapshot"

class SnapshotDispatcher(
    /**
     * named like `applicationName.SnapshotDispatcher`
     */
    override val name: String,
    override val topics: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val snapshotHandler: SnapshotHandler,
    private val domainEventBus: DomainEventBus,
) : AbstractMessageDispatcher<HandledSignal>(), MessageDispatcher {
    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
        val log: Logger = LoggerFactory.getLogger(SnapshotDispatcher::class.java)
    }

    private val scheduler: Scheduler = Schedulers.newParallel(SnapshotDispatcher::class.java.simpleName)
    override fun start() {
        domainEventBus.receive(topics)
            .writeReceiverGroup(name)
            .groupBy { it.message.aggregateId }
            .flatMap { handle(it) }
            .subscribe(this)
    }

    private fun handle(it: GroupedFlux<AggregateId, EventStreamExchange>): Mono<HandledSignal> {
        if (log.isDebugEnabled) {
            log.debug(
                "[$name] Create {} GroupedFlux - Timeout {}.",
                it.key(),
                DEFAULT_TIMEOUT,
            )
        }
        return it.publishOn(scheduler)
            .timeout(
                DEFAULT_TIMEOUT,
                Mono.defer {
                    if (log.isDebugEnabled) {
                        log.debug(
                            "[$name] Clear {} group: has not received events for {}.",
                            it.key(),
                            DEFAULT_TIMEOUT,
                        )
                    }
                    Mono.empty()
                },
            )
            .filter {
                it.message.shouldHandle(SNAPSHOT_PROCESSOR_NAME)
            }
            .concatMap {
                snapshotHandler.handle(it)
            }.then(Mono.just(HandledSignal))
    }
}
