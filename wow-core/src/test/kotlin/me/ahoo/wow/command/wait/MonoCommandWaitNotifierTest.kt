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
import org.junit.jupiter.api.Test
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
    fun `source cancellation does not emit signal`() {
        val notifier = RecordingCommandWaitNotifier()
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)

        StepVerifier.create(Mono.never<Void>().thenNotifyAndForget(notifier, CommandStage.PROCESSED, exchange))
            .thenCancel()
            .verify()

        notifier.notifications.assert().isEmpty()
    }
}
