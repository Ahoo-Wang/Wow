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

package me.ahoo.wow.kafka

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import reactor.core.publisher.Mono
import reactor.kafka.receiver.ReceiverOffset
import java.util.concurrent.ConcurrentHashMap

data class KafkaServerCommandExchange<C : Any>(
    override val message: CommandMessage<C>,
    private val receiverOffset: ReceiverOffset,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : ServerCommandExchange<C> {
    override fun acknowledge(): Mono<Void> {
        return Mono.fromRunnable {
            receiverOffset.acknowledge()
        }
    }
}
