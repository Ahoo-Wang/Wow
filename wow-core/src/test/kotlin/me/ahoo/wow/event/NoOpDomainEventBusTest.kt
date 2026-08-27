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

package me.ahoo.wow.event

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.messaging.MessageSubscription
import org.junit.jupiter.api.Test
import reactor.test.StepVerifier

class NoOpDomainEventBusTest {

    @Test
    fun `send completes without publishing`() {
        StepVerifier.create(NoOpDomainEventBus.send(mockk()))
            .verifyComplete()
    }

    @Test
    fun `receive is empty`() {
        StepVerifier.create(NoOpDomainEventBus.receive(MessageSubscription(emptySet())))
            .verifyComplete()
    }

    @Test
    fun `topic kind is event stream`() {
        NoOpDomainEventBus.topicKind.assert().isEqualTo(TopicKind.EVENT_STREAM)
    }
}
