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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.shouldHandle
import me.ahoo.wow.messaging.MessageDispatcher
import me.ahoo.wow.messaging.dispatcher.AbstractMessageDispatcher
import me.ahoo.wow.messaging.writeReceiverGroup
import reactor.core.scheduler.Schedulers

private const val SNAPSHOT_PROCESSOR_NAME = "snapshot"

class SnapshotDispatcher(
    /**
     * named like `applicationName.SnapshotDispatcher`
     */
    override val name: String,
    override val topics: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet(),
    private val snapshotHandler: SnapshotHandler,
    private val domainEventBus: DomainEventBus,
) : AbstractMessageDispatcher<Void>(), MessageDispatcher {

    override fun start() {
        domainEventBus.receive(topics)
            .writeReceiverGroup(name)
            .filter {
                it.message.shouldHandle(SNAPSHOT_PROCESSOR_NAME)
            }
            .parallel()
            .runOn(Schedulers.boundedElastic())
            .flatMap { snapshotHandler.handle(it) }
            .subscribe(this)
    }
}
