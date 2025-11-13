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

package me.ahoo.wow.eventsourcing.state

import me.ahoo.wow.messaging.LocalFirstMessageBus

/**
 * State event bus that prioritizes local message delivery before distributed delivery.
 * Messages are first sent to local subscribers within the same JVM instance,
 * then forwarded to the distributed bus for cross-instance communication.
 * This ensures low-latency local processing while maintaining consistency across instances.
 *
 * @param distributedBus The distributed state event bus for cross-instance messaging.
 * @param localBus The local state event bus for same-instance messaging (default: InMemoryStateEventBus).
 */
class LocalFirstStateEventBus(
    override val distributedBus: DistributedStateEventBus,
    override val localBus: LocalStateEventBus = InMemoryStateEventBus()
) : StateEventBus,
    LocalFirstMessageBus<StateEvent<*>, StateEventExchange<*>>
