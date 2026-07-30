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
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CommandDispatcherLifecycleTest {

    @Test
    fun `runtime waits for command receiver readiness before starting`() {
        val readiness = Sinks.empty<Void>()
        val subscribed = AtomicBoolean()
        val processingOpened = AtomicBoolean()
        val commandBus = object : CommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.empty()

            override fun receive(
                subscription: MessageSubscription,
            ): Flux<ServerCommandExchange<*>> = Flux.never()

            override fun receiver(
                subscription: MessageSubscription,
            ): MessageReceiver<ServerCommandExchange<*>> =
                MessageReceiver(
                    messages = Flux.never<ServerCommandExchange<*>>()
                        .doOnSubscribe {
                            subscribed.set(true)
                        },
                    readiness = readiness.asMono(),
                    processingAdmission = {
                        processingOpened.set(true)
                    },
                )
        }
        val runtime = WowRuntime(
            components = listOf(
                CommandDispatcher(
                    namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
                    commandBus = commandBus,
                    commandHandler = NoOpCommandHandler,
                    schedulerSupplier = RecordingAggregateSchedulerSupplier(),
                ),
            ),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        val startup = runtime.start().toFuture()
        subscribed.get().assert().isTrue()
        processingOpened.get().assert().isFalse()
        startup.isDone.assert().isFalse()

        readiness.tryEmitEmpty().orThrow()
        startup.get(1, TimeUnit.SECONDS)
        runtime.isRunning.assert().isTrue()
        processingOpened.get().assert().isTrue()
        StepVerifier.create(runtime.stopGracefully()).verifyComplete()
    }

    @Test
    fun `force stop before start does not open command processing`() {
        val processingAdmissions = AtomicInteger()
        val subscribed = AtomicBoolean()
        val cancelled = CountDownLatch(1)
        val commandBus = object : CommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.empty()

            override fun receive(
                subscription: MessageSubscription,
            ): Flux<ServerCommandExchange<*>> = Flux.never()

            override fun receiver(
                subscription: MessageSubscription,
            ): MessageReceiver<ServerCommandExchange<*>> =
                MessageReceiver(
                    messages = Flux.never<ServerCommandExchange<*>>()
                        .doOnSubscribe { subscribed.set(true) }
                        .doOnCancel(cancelled::countDown),
                    processingAdmission = processingAdmissions::incrementAndGet,
                )
        }
        val schedulerSupplier = RecordingAggregateSchedulerSupplier()
        val commandDispatcher = CommandDispatcher(
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
            commandBus = commandBus,
            commandHandler = NoOpCommandHandler,
            schedulerSupplier = schedulerSupplier,
        )
        commandDispatcher.prepare(DefaultRuntimeContext()).block()
        subscribed.get().assert().isTrue()

        commandDispatcher.forceStop()
        commandDispatcher.start()

        processingAdmissions.get().assert().isZero()
        cancelled.await(1, TimeUnit.SECONDS).assert().isTrue()
        schedulerSupplier.stopped.get().assert().isTrue()
    }

    @Test
    fun `stop gracefully stops aggregate scheduler supplier`() {
        val schedulerSupplier = RecordingAggregateSchedulerSupplier()
        val commandDispatcher = CommandDispatcher(
            namedAggregates = setOf(MOCK_AGGREGATE_METADATA),
            commandBus = NoOpCommandBus,
            commandHandler = NoOpCommandHandler,
            schedulerSupplier = schedulerSupplier,
        )
        val runtime = WowRuntime(
            components = listOf(commandDispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )

        runtime.start().block()
        StepVerifier.create(runtime.stopGracefully())
            .verifyComplete()

        schedulerSupplier.stopped.get().assert().isTrue()
    }

    private object NoOpCommandBus : CommandBus {
        override fun send(message: CommandMessage<*>): Mono<Void> = Mono.empty()

        override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.never()
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

        override fun forceStop() {
            stopped.set(true)
            scheduler.dispose()
        }
    }
}
