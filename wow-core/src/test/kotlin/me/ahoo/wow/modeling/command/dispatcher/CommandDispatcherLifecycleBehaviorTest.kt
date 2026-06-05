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

package me.ahoo.wow.modeling.command.dispatcher

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicBoolean

class CommandDispatcherLifecycleBehaviorTest {

    @Test
    fun `stop gracefully stops aggregate scheduler supplier`() {
        val schedulerSupplier = RecordingAggregateSchedulerSupplier()
        val commandDispatcher = CommandDispatcher(
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
            commandBus = NoOpCommandBus,
            commandHandler = NoOpCommandHandler,
            schedulerSupplier = schedulerSupplier,
        )

        commandDispatcher.start()
        StepVerifier.create(commandDispatcher.stopGracefully())
            .verifyComplete()

        schedulerSupplier.stopped.get().assert().isTrue()
    }

    private object NoOpCommandBus : CommandBus {
        override fun send(message: CommandMessage<*>): Mono<Void> = Mono.empty()

        override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> = Flux.never()
    }

    private object NoOpCommandHandler : CommandHandler {
        override fun handle(context: ServerCommandExchange<*>): Mono<Void> = Mono.empty()
    }

    private class RecordingAggregateSchedulerSupplier : AggregateSchedulerSupplier {
        val stopped = AtomicBoolean()
        private val scheduler = Schedulers.newSingle("recording-command-dispatcher")

        override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler = scheduler

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                stopped.set(true)
                scheduler.dispose()
            }
    }
}
