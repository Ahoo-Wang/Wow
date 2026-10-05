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

package me.ahoo.wow.messaging.transport

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.tck.command.CommandBusSpec
import me.ahoo.wow.tck.event.DomainEventBusSpec
import me.ahoo.wow.tck.eventsourcing.state.StateEventBusSpec

private val NAMING = TopicNaming { "${it.contextName}.${it.aggregateName}" }

internal class InMemoryTransportCommandBusTest : CommandBusSpec() {
    override fun createMessageBus(): CommandBus = TransportCommandBus(InMemoryTransport(), NAMING)
}

internal class InMemoryTransportDomainEventBusTest : DomainEventBusSpec() {
    override fun createMessageBus(): DomainEventBus = TransportDomainEventBus(InMemoryTransport(), NAMING)
}

internal class InMemoryTransportStateEventBusTest : StateEventBusSpec() {
    override fun createMessageBus(): StateEventBus = TransportStateEventBus(InMemoryTransport(), NAMING)
}
