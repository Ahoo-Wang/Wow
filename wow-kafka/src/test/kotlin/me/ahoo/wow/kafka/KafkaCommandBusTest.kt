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

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.tck.command.CommandBusSpec
import org.junit.jupiter.api.BeforeAll
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

internal class KafkaCommandBusTest : CommandBusSpec() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun waitLauncher() {
            KafkaLauncher.isRunning
        }
    }

    override fun createMessageBus(): CommandBus {
        return KafkaCommandBus(
            senderOptions = KafkaLauncher.senderOptions,
            receiverOptions = KafkaLauncher.receiverOptions
        )
    }

    override fun Flux<ServerCommandExchange<*>>.onReceive(onReady: Sinks.Empty<Void>): Flux<ServerCommandExchange<*>> {
        return contextWrite {
            it.writeReceiverOptionsCustomizer { receiverOptions ->
                receiverOptions.addAssignListener {
                    it.forEach { receiverPartition ->
                        receiverPartition.seekToEnd()
                    }
                    onReady.tryEmitEmpty()
                }
            }
        }
    }
}
