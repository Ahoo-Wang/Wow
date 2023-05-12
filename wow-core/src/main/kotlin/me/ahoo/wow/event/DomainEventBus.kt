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

import me.ahoo.wow.messaging.ReceiveMessageBus
import me.ahoo.wow.messaging.SendMessageBus

/**
 * Domain Event Bus.
 *
 * 1. 领域事件发布有序性(per AggregateId)
 * 2. 领域事件处理有序性
 *
 */
interface DomainEventBus : SendMessageBus<DomainEventStream>, ReceiveMessageBus<EventStreamExchange>
