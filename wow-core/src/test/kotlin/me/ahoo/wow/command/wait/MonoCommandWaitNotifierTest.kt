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

package me.ahoo.wow.command.wait

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.saga.stateless.DefaultCommandStream
import me.ahoo.wow.saga.stateless.setCommandStream
import org.junit.jupiter.api.Test
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MonoCommandWaitNotifierTest {

    @Test
    fun `source completes without notification when header has no wait plan`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange()
        exchange.message.header.clear()

        StepVerifier.create(Mono.empty<Void>().thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .verifyComplete()

        notifier.notifications.assert().isEmpty()
    }

    @Test
    fun `source completion emits matching wait signal`() {
        val waitCommandId = "wait-command-id"
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(waitCommandId = waitCommandId, stage = CommandStage.PROCESSED)
        val function = testFunction(kind = FunctionKind.COMMAND)
        exchange.setFunction(function)
        exchange.setCommandResult("answer", 42)

        StepVerifier.create(Mono.empty<Void>().thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        val notification = notifier.notifications.single()
        notification.endpoint.assert().isEqualTo(TEST_ENDPOINT)
        notification.signal.waitCommandId.assert().isEqualTo(waitCommandId)
        notification.signal.commandId.assert().isEqualTo(exchange.message.commandId)
        notification.signal.stage.assert().isEqualTo(CommandStage.PROCESSED)
        notification.signal.function.assert().isEqualTo(function)
        notification.signal.result.assert().isEqualTo(mapOf("answer" to 42))
    }

    @Test
    fun `notification happens before downstream completion`() {
        val order = mutableListOf<String>()
        val notifier = orderedNotifier(order)
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)

        Mono.empty<Void>()
            .thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange)
            .doOnSuccess {
                order += "downstream"
            }.block()

        order.assert().containsExactly("notify", "downstream")
    }

    @Test
    fun `notification happens before downstream error`() {
        val order = mutableListOf<String>()
        val notifier = orderedNotifier(order)
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)
        val error = IllegalStateException("boom")

        StepVerifier.create(
            Mono.error<Void>(error)
                .thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange)
                .doOnError {
                    order += "downstream"
                },
        )
            .expectErrorMatches { it === error }
            .verify()

        order.assert().containsExactly("notify", "downstream")
    }

    @Test
    fun `source completes without notification when processing stage is not needed`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)

        StepVerifier.create(Mono.empty<Void>().thenNotifyAndForget(notifier, CommandStage.PROJECTED, exchange))
            .verifyComplete()

        notifier.notifications.assert().isEmpty()
    }

    @Test
    fun `signal function mismatch skips notification`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testDomainEventExchange(
            stage = CommandStage.PROJECTED,
            function = testNamedFunction(name = "expected"),
        )
        exchange.setFunction(testFunction(name = "actual"))

        StepVerifier.create(
            Mono.empty<Void>().thenNotifyAndForget(
                notifier,
                CommandStage.PROJECTED,
                exchange,
            ),
        ).verifyComplete()

        notifier.notifications.assert().isEmpty()
    }

    @Test
    fun `each subscription reads the current wait headers`() {
        val notifier = RecordingCommandWaitNotifier()
        val header = DefaultHeader.empty()
        val exchange = SimpleServerCommandExchange(TestCommandMessage(header = header))
        val notified = Mono.empty<Void>()
            .thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange)

        StepVerifier.create(notified).verifyComplete()
        CommandWait.processed(exchange.message.commandId)
            .propagate(
                object : CommandWaitEndpoint {
                    override val endpoint: String = TEST_ENDPOINT
                },
                header,
            )
        StepVerifier.create(notified).verifyComplete()

        notifier.notifications.assert().hasSize(1)
        notifier.notifications.single().endpoint.assert().isEqualTo(TEST_ENDPOINT)
    }

    @Test
    fun `source sees downstream subscriber context`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)
        var observedContext: String? = null
        val source = Mono.deferContextual<Void> {
            observedContext = it.get("context-key")
            Mono.empty()
        }

        StepVerifier.create(
            source
                .thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange)
                .contextWrite {
                    it.put("context-key", "context-value")
                },
        ).verifyComplete()

        observedContext.assert().isEqualTo("context-value")
    }

    @Test
    fun `source error emits failed signal and propagates error`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)
        val error = IllegalStateException("boom")

        StepVerifier.create(Mono.error<Void>(error).thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .expectErrorMatches { it === error }
            .verify()

        notifier.notifications.assert().hasSize(1)
        notifier.notifications.single().signal.succeeded.assert().isFalse()
    }

    @Test
    fun `retry exhausted source error unwraps cause before notifying`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)
        val cause = IllegalStateException("boom")
        val retryExhausted = Exceptions.retryExhausted("retries exhausted", cause)

        StepVerifier.create(
            Mono.error<Void>(retryExhausted).thenNotifyAndForget(
                notifier,
                CommandStage.PROCESSED,
                exchange,
            )
        )
            .expectErrorMatches { it === cause }
            .verify()

        notifier.notifications.assert().hasSize(1)
        notifier.notifications.single().signal.errorMsg.assert().contains("boom")
    }

    @Test
    fun `source completion emits exchange error when present`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)
        exchange.setError(IllegalStateException("handled failed"))

        StepVerifier.create(Mono.empty<Void>().thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        notifier.notifications.single().signal.succeeded.assert().isFalse()
        notifier.notifications.single().signal.errorMsg.assert().contains("handled failed")
    }

    @Test
    fun `source completion uses unknown function when exchange function is absent`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)

        StepVerifier.create(Mono.empty<Void>().thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        val function = notifier.notifications.single().signal.function
        function.functionKind.assert().isEqualTo(FunctionKind.ERROR)
        function.contextName.assert().isEqualTo(exchange.message.contextName)
    }

    @Test
    fun `saga handled source completion includes generated command ids`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testDomainEventExchange(stage = CommandStage.SAGA_HANDLED, function = testNamedFunction())
        exchange.setFunction(testFunction())
        exchange.setCommandStream(
            DefaultCommandStream(
                domainEventId = exchange.message.id,
                commands = listOf(TestCommandMessage(id = "generated-command-id")),
            )
        )

        StepVerifier.create(Mono.empty<Void>().thenNotifyAndForget(notifier, CommandStage.SAGA_HANDLED, exchange))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        notifier.notifications.single().signal.commands.assert().containsExactly("generated-command-id")
    }

    @Test
    fun `source cancellation does not emit signal`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)

        StepVerifier.create(Mono.never<Void>().thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .thenCancel()
            .verify()

        notifier.notifications.assert().isEmpty()
    }

    private fun orderedNotifier(order: MutableList<String>): CommandWaitNotifier =
        object : CommandWaitNotifier {
            override fun notify(
                commandWaitEndpoint: String,
                waitSignal: WaitSignal,
            ): Mono<Void> = Mono.empty()

            override fun notifyAndForget(
                commandWaitEndpoint: String,
                waitSignal: WaitSignal,
            ) {
                order += "notify"
            }
        }
}
