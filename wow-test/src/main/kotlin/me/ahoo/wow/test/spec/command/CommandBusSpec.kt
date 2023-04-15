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
package me.ahoo.wow.test.spec.command

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.configuration.asRequiredNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.writeReceiverGroup
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Duration

/**
 * Command Bus Implementation Specification.
 */
abstract class CommandBusSpec {
    protected val namedAggregateForSend = MockSendCommand::class.java.asRequiredNamedAggregate()
    protected val namedAggregateForReceive = MockReceiveCommand::class.java.asRequiredNamedAggregate()
    protected abstract fun createCommandBus(): CommandBus

    @Test
    fun send() {
        val commandBus = createCommandBus()
        val commandMessage = MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        Schedulers.single().schedule {
            commandBus
                .receive(setOf(namedAggregateForSend)).subscribe()
        }
        commandBus.send(commandMessage)
            .test()
            .verifyComplete()
    }

    @Test
    fun receive() {
        val commandBus = createCommandBus()
        commandBus.receive(setOf(namedAggregateForReceive))
            .writeReceiverGroup(GlobalIdGenerator.generateAsString())
            .test()
            .consumeSubscriptionWith {
                Flux.range(0, 10)
                    .publishOn(Schedulers.boundedElastic())
                    .map {
                        val commandMessage = MockReceiveCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
                        commandBus.send(commandMessage).subscribe()
                    }
                    .delaySubscription(Duration.ofSeconds(1))
                    .subscribe()
            }
            .expectNextCount(10)
            .verifyTimeout(Duration.ofSeconds(2))
    }
}
