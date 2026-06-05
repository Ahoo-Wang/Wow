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
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.filter.FilterChain
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ProcessedNotifierFilterBehaviorTest {

    @Test
    fun `filter completes chain and notifies processed stage`() {
        val notifier = RecordingCommandWaitNotifier()
        val filter = ProcessedNotifierFilter(notifier)
        val exchange = testCommandExchange(stage = CommandStage.PROCESSED)
        val chain = FilterChain<ServerCommandExchange<*>> { Mono.empty() }

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        notifier.notifications.single().signal.stage.assert().isEqualTo(CommandStage.PROCESSED)
    }
}
