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
package me.ahoo.wow.tck.command

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.configuration.asRequiredNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.metrics.Metrics.metrizable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Duration

/**
 * Command Bus Implementation Specification.
 */
abstract class CommandBusSpec {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(CommandBusSpec::class.java)
    }

    protected val namedAggregateForSend = MockSendCommand::class.java.asRequiredNamedAggregate()
    protected val namedAggregateForReceive = MockReceiveCommand::class.java.asRequiredNamedAggregate()
    protected abstract fun createCommandBus(): CommandBus

    @Test
    fun send() {
        val commandBus = createCommandBus().metrizable()
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
        val commandBus = createCommandBus().metrizable()
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

    @Test
    fun sendPerformance() {
        val commandBus = createCommandBus().metrizable()
        val duration = sendLoop(commandBus = commandBus)
            .test()
            .verifyComplete()
        log.info("[${this.javaClass.simpleName}] sendPerformance - duration:{}", duration)
    }

    private fun sendLoop(commandBus: CommandBus, maxCount: Int = 2000): Mono<Void> {
        return Flux.range(0, maxCount)
            .publishOn(Schedulers.boundedElastic())
            .map {
                MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
            }.flatMap {
                commandBus.send(it)
            }.then()
    }

    @DisabledIfEnvironmentVariable(named = "CI", matches = ".*")
    @Test
    fun receivePerformance() {
        val commandBus = createCommandBus().metrizable()
        val maxCount: Long = 2000
        val duration = commandBus.receive(setOf(namedAggregateForSend))
            .writeReceiverGroup(GlobalIdGenerator.generateAsString())
            .test()
            .consumeSubscriptionWith {
                sendLoop(commandBus = commandBus, maxCount = maxCount.toInt())
                    .delaySubscription(Duration.ofSeconds(1))
                    .subscribe()
            }
            .expectNextCount(maxCount)
            .verifyTimeout(Duration.ofSeconds(2))
        log.info("[${this.javaClass.simpleName}] receivePerformance - duration:{}", duration)
    }
}
