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

package me.ahoo.wow.opentelemetry.messaging

import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange

class TracingLocalStateEventBus(
    override val delegate: LocalStateEventBus,
    override val producerInstrumenter: Instrumenter<StateEvent<*>, Unit> = StateEventProducerInstrumenter.INSTRUMENTER,
) :
    TracingMessageBus<StateEvent<*>, StateEventExchange<*>, LocalStateEventBus>,
    LocalStateEventBus

class TracingDistributedStateEventBus(
    override val delegate: DistributedStateEventBus,
    override val producerInstrumenter: Instrumenter<StateEvent<*>, Unit> = StateEventProducerInstrumenter.INSTRUMENTER,
) :
    TracingMessageBus<StateEvent<*>, StateEventExchange<*>, DistributedStateEventBus>,
    DistributedStateEventBus
