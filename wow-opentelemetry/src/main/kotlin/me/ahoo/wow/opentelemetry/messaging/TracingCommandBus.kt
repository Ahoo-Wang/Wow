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
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange

class TracingLocalCommandBus(
    override val delegate: LocalCommandBus,
    override val producerInstrumenter: Instrumenter<CommandMessage<*>, Unit> = CommandProducerInstrumenter.INSTRUMENTER
) : TracingMessageBus<CommandMessage<*>, ServerCommandExchange<*>, LocalCommandBus>,
    LocalCommandBus {
    override fun subscriberCount(namedAggregate: NamedAggregate): Int {
        return delegate.subscriberCount(namedAggregate)
    }
}

class TracingDistributedCommandBus(
    override val delegate: DistributedCommandBus,
    override val producerInstrumenter: Instrumenter<CommandMessage<*>, Unit> = CommandProducerInstrumenter.INSTRUMENTER
) :
    TracingMessageBus<CommandMessage<*>, ServerCommandExchange<*>, DistributedCommandBus>,
    DistributedCommandBus
