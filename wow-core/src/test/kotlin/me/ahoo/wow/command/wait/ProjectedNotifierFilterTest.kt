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
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.filter.FilterChain
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class ProjectedNotifierFilterTest {

    @Test
    fun `filter completes chain and notifies projected stage`() {
        val notifier = RecordingCommandWaitNotifier()
        val filter = ProjectedNotifierFilter(notifier)
        val function = testNamedFunction(contextName = TEST_CONTEXT, processorName = "", name = "")
        val exchange = testDomainEventExchange(stage = CommandStage.PROJECTED, function = function)
        exchange.setFunction(testFunction())
        val chain = FilterChain<DomainEventExchange<Any>> { Mono.empty() }

        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete()

        notifier.notifications.assert().hasSize(1)
        val signal = notifier.notifications.single().signal
        signal.stage.assert().isEqualTo(CommandStage.PROJECTED)
        signal.isLastProjection.assert().isTrue()
    }
}
