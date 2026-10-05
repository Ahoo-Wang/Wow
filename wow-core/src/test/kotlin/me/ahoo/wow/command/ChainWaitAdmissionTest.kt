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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.RecordingCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.command.wait.testNamedFunction
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/** B6: a chain wait must wait on the command it is sent with; the gateway checks it at admission. */
class ChainWaitAdmissionTest {
    private val sent = mutableListOf<CommandMessage<*>>()
    private val waitCoordinator = DefaultWaitCoordinator()
    private val gateway = DefaultCommandGateway(
        commandWaitEndpoint = SimpleCommandWaitEndpoint("endpoint"),
        commandBus = object : CommandBus {
            override fun send(message: CommandMessage<*>): Mono<Void> = Mono.fromRunnable { sent += message }

            override fun receiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
                MessageReceiver(Flux.empty())
        },
        validator = NoOpValidator,
        requestIdChecker = RequestIdChecker { _, _ -> Mono.just(true) },
        waitCoordinator = waitCoordinator,
        commandWaitNotifier = RecordingCommandWaitNotifier(),
    )

    @Test
    fun `a chain wait on another command is rejected before anything is sent`() {
        val waitPlan = CommandWait.chain(
            waitCommandId = "another-command",
            function = testNamedFunction(),
            tailStage = CommandStage.PROCESSED,
            tailFunction = testNamedFunction(),
        )

        StepVerifier.create(gateway.sendAndWait(TestCommandMessage(id = "command-id"), waitPlan))
            .expectError(IllegalArgumentException::class.java)
            .verify()

        sent.assert().isEmpty()
        waitCoordinator.contains("another-command").assert().isFalse()
    }
}
