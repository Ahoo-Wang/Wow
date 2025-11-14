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

package me.ahoo.wow.event

import me.ahoo.wow.messaging.LocalFirstMessageBus

/**
 * Domain event bus that prioritizes local processing before distributed publishing.
 *
 * This implementation extends LocalFirstMessageBus to provide a hybrid event bus
 * that first processes events locally (for immediate consistency) and then
 * publishes them to a distributed bus (for cross-service communication).
 *
 * @property distributedBus The distributed event bus for cross-service communication
 * @property localBus The local event bus for in-process handling (default: InMemoryDomainEventBus)
 *
 * @constructor Creates a new LocalFirstDomainEventBus with the specified buses
 *
 * @param distributedBus The distributed event bus
 * @param localBus The local event bus (default: InMemoryDomainEventBus)
 *
 * @see DomainEventBus
 * @see LocalFirstMessageBus
 * @see DistributedDomainEventBus
 * @see LocalDomainEventBus
 */
class LocalFirstDomainEventBus(
    override val distributedBus: DistributedDomainEventBus,
    override val localBus: LocalDomainEventBus = InMemoryDomainEventBus()
) : DomainEventBus,
    LocalFirstMessageBus<DomainEventStream, EventStreamExchange>
